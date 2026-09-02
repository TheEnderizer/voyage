package com.betteraudio.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import org.json.JSONArray
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.betteraudio.R
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.sizeOnDisk
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import com.betteraudio.widget.WidgetUpdater
import com.betteraudio.widget.model.WidgetSnapshot
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var repository: AudiobookRepository
    @Inject lateinit var seriesRepository: SeriesRepository
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var jumpRestoreStore: JumpRestoreStore
    @Inject lateinit var diskMirror: com.betteraudio.data.diskstore.DiskMirror
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var mediaSession: MediaSession? = null
    // The real ExoPlayer (the MediaSession is fed a ForwardingPlayer wrapping it). Audio
    // effects and the audio-session id come from this, not from mediaSession.player.
    private var exoPlayer: ExoPlayer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Audio effects — must live on the real ExoPlayer's audio session, not on a
    // remote MediaController (which never receives onAudioSessionIdChanged).
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var attachedSessionId   = C.AUDIO_SESSION_ID_UNSET
    private var attachedEqSessionId = C.AUDIO_SESSION_ID_UNSET
    private var boostMb = 0      // millibels; 100 mb = 1 dB
    private var eqBandsJson: String? = null  // null = flat / bypass
    private var skipSilenceEnabled = false
    // Whether ensureStartedService() has converted this from a bound-only to a started service.
    private var selfStarted = false

    // Saves the current playback position to the DB every 5 s while playing. Runs on
    // serviceScope (Main dispatcher) so ExoPlayer's currentPosition is safe to read.
    private var positionSaverJob: Job? = null

    // Silence-skipping audio processor — lives in the decode→sink chain (separate from the
    // session-id audio effects above). Toggled per book; its tuning follows the settings live.
    private var silenceProcessor: LiveSilenceSkippingProcessor? = null

    // Stereo balance / mono downmix — also lives in the decode→sink chain. Global (not per-book),
    // applied per-buffer so a change takes effect immediately; see ChannelMixProcessor's doc.
    private val channelMix = ChannelMixProcessor()

    // ── Sleep timer — single authority for BOTH the widget's timer and the in-app player's, so
    // it fires even if the app process (and PlayerController's own coroutine scope) has died;
    // only the service's process (tied to the foreground notification) needs to survive.
    // PlayerController mirrors a local countdown purely for immediate UI display; this is what
    // actually pauses playback. See SleepTimerEngine for the mode/tick/fade/shake orchestration —
    // this class only wires it to the real player/session/widget/screen state.
    private val shakeDetector: ShakeDetector by lazy { ShakeDetector(this) { sleepTimerEngine.onShakeDetected() } }
    private val sleepTimerEngine: SleepTimerEngine by lazy {
        SleepTimerEngine(
            context = this,
            scope = serviceScope,
            settings = settings,
            shakeDetector = shakeDetector,
            player = object : SleepTimerEngine.Player {
                override fun isPlaying() = exoPlayer?.isPlaying == true
                override fun pause() { exoPlayer?.pause() }
                override fun play() { exoPlayer?.play() }
                override fun getVolume() = exoPlayer?.volume ?: 1f
                override fun setVolume(volume: Float) { exoPlayer?.volume = volume }
                override fun currentBookPositionMs(): Long {
                    val p = exoPlayer ?: return 0L
                    return bookPositionMsFor(p.currentMediaItemIndex, p.currentPosition)
                }
                override fun isScreenOn() = screenOn
                override fun tickCountdownWidget() = widgetUpdater.tickCountdown()
                override fun tickEndOfChapterWidget() = pushWidgetStateForSleepTick()
                override fun pushFullWidgetState() = pushWidgetState()
                override fun broadcastSleepState(mode: String, remainingMs: Long) =
                    this@PlaybackService.broadcastSleepState(mode, remainingMs)
            }
        )
    }

    // Screen off = nobody can see the widget. The 1 Hz sleep tick otherwise keeps re-rendering
    // it — a fresh bitmap, a full RemoteViews rebuild, a Binder call to the launcher — for the
    // entire duration of a nighttime timer with the screen dark, for no one. Everything ELSE in
    // the tick (remaining-time bookkeeping, shake-to-extend arming, the fade, firing the timer)
    // keeps running regardless — only the widget push/render is skipped.
    @Volatile private var screenOn = true
    private var screenStateReceiver: BroadcastReceiver? = null

    // Cached book/series metadata for the END_OF_CHAPTER sleep tick's once-a-second re-render —
    // see pushWidgetStateForSleepTick(). The book/series rarely change between two ticks one
    // second apart, so re-querying the DB every tick (as the general pushWidgetState() does) is
    // wasted work in this specific hot loop; a bookId mismatch (a new book started) refetches.
    @Volatile private var eocMetaBookId: Long = -1L
    @Volatile private var eocMetaSeriesName: String = ""
    @Volatile private var eocMetaBookCoverPath: String? = null
    @Volatile private var eocMetaSeriesCoverPath: String? = null
    @Volatile private var eocMetaBookDurationMs: Long = 0L

    // The chapter/file-boundary timeline for whichever book is currently loaded — see
    // playback/ChapterTimeline.kt. The service needs its own copy (rather than relying on a
    // controller pushing one) because it can start playback entirely on its own with no app
    // process/UI attached at all (loadLastPlayedAndPlay, a cold widget tap). @Volatile + whole-
    // object replacement, same discipline as eocMeta* above.
    @Volatile private var chapterTimeline: ChapterTimeline = ChapterTimeline.EMPTY
    @Volatile private var chapterTimelineLoadInFlightFor: Long = -1L

    /** Loads (or reloads) the chapter timeline for [bookId] unless it's already current, guarding
     *  against two concurrent callers duplicating the same DB fetch. No completion callback: every
     *  reader here (pushWidgetState/pushWidgetStateForSleepTick) just falls back to file-scoped
     *  data while a load is in flight, degrading gracefully rather than blocking on it. */
    private fun ensureChapterTimeline(bookId: Long) {
        if (bookId == -1L) return
        if (chapterTimeline.bookId == bookId) return
        if (chapterTimelineLoadInFlightFor == bookId) return
        chapterTimelineLoadInFlightFor = bookId
        serviceScope.launch(Dispatchers.IO) {
            val files = repository.getAudioFilesOnce(bookId)
            val chapters = repository.getChaptersForBookOnce(bookId)
            chapterTimeline = ChapterTimeline.build(files, chapters, bookId)
            if (chapterTimelineLoadInFlightFor == bookId) chapterTimelineLoadInFlightFor = -1L
        }
    }

    // ── Headset multi-press mapping ──────────────────────────────────────────────
    private val headsetGestureMapper: HeadsetGestureMapper by lazy {
        HeadsetGestureMapper(serviceScope, settings) { action -> performHeadsetAction(action) }
    }

    // ── Bluetooth/headphone auto-resume ──────────────────────────────────────────
    private val btAutoResumeWatcher: BtAutoResumeWatcher by lazy {
        BtAutoResumeWatcher(
            context = this,
            isEnabled = { settings.currentBtAutoResumeEnabled },
            onDeviceConnected = { maybeAutoResumeOnDeviceConnect() }
        )
    }

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.betteraudio.action.WIDGET_PLAY_PAUSE"
        const val ACTION_SKIP_FORWARD      = "com.betteraudio.action.WIDGET_SKIP_FORWARD"
        const val ACTION_SKIP_BACK         = "com.betteraudio.action.WIDGET_SKIP_BACK"
        const val ACTION_CHAPTER_FORWARD   = "com.betteraudio.action.WIDGET_CHAPTER_FORWARD"
        const val ACTION_CHAPTER_BACK      = "com.betteraudio.action.WIDGET_CHAPTER_BACK"
        const val ACTION_SPEED_UP          = "com.betteraudio.action.WIDGET_SPEED_UP"
        const val ACTION_SPEED_DOWN        = "com.betteraudio.action.WIDGET_SPEED_DOWN"
        const val ACTION_BOOST_UP          = "com.betteraudio.action.WIDGET_BOOST_UP"
        const val ACTION_BOOST_DOWN        = "com.betteraudio.action.WIDGET_BOOST_DOWN"
        const val ACTION_QUICK_BOOKMARK    = "com.betteraudio.action.WIDGET_QUICK_BOOKMARK"
        const val ACTION_CLOSE_BOOK        = "com.betteraudio.action.WIDGET_CLOSE_BOOK"
        /** Self-start marker — see [ensureStartedService]. Not a widget action, does nothing. */
        const val ACTION_KEEP_ALIVE        = "com.betteraudio.action.KEEP_ALIVE"
        const val ACTION_SLEEP_TIMER_TOGGLE = "com.betteraudio.action.WIDGET_SLEEP_TIMER_TOGGLE"
        const val EXTRA_SLEEP_DURATION_MS  = "extra_sleep_duration_ms"

        const val CMD_SET_BOOST      = "com.betteraudio.command.SET_BOOST"
        const val KEY_BOOST_MB       = "boost_mb"
        const val CMD_SET_EQ         = "com.betteraudio.command.SET_EQ"
        const val KEY_EQ_BANDS_JSON  = "eq_bands_json"  // "" = flat/bypass
        const val CMD_SET_SKIP_SILENCE = "com.betteraudio.command.SET_SKIP_SILENCE"
        const val KEY_SKIP_SILENCE     = "skip_silence_enabled"

        const val CMD_SET_CHANNEL_MIX = "com.betteraudio.command.SET_CHANNEL_MIX"
        const val KEY_AUDIO_BALANCE   = "audio_balance"
        const val KEY_MONO_AUDIO      = "mono_audio"

        const val CMD_SET_SLEEP_TIMER          = "com.betteraudio.command.SET_SLEEP_TIMER"
        const val KEY_SLEEP_MODE               = "sleep_mode"
        const val KEY_SLEEP_DURATION_MS        = "sleep_duration_ms"
        // Absolute book-level target for END_OF_CHAPTER, already resolved by PlayerController
        // (which has a reliable DB-loaded duration cache) — NOT a (index, offset) pair. Avoids
        // the service having to re-derive cumulative offsets from ExoPlayer's Timeline, whose
        // window durations for not-yet-prepared items can read as C.TIME_UNSET.
        const val KEY_SLEEP_TARGET_POSITION_MS = "sleep_target_position_ms"
        const val SLEEP_MODE_OFF            = "OFF"
        const val SLEEP_MODE_COUNTDOWN      = "COUNTDOWN"
        const val SLEEP_MODE_END_OF_CHAPTER = "END_OF_CHAPTER"

        // Session → controller push (the service is sole authority; a shake-extend or a
        // schedule auto-arm happens entirely service-side, so controllers can't just poll their
        // own local countdown — they need to be told). See MediaSession.broadcastCustomCommand /
        // MediaController.Listener.onCustomCommand.
        const val CMD_SLEEP_STATE_CHANGED = "com.betteraudio.command.SLEEP_STATE_CHANGED"
        const val KEY_SLEEP_REMAINING_MS  = "sleep_remaining_ms"

        private const val SPEED_STEP = 0.1f
        private const val BOOST_STEP_MB = 300 // 3 dB
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i(LogCat.PLAYBACK, "onCreate — building player (skipSilence min=${settings.currentSkipSilenceMinMs}ms keep=${settings.currentSkipSilencePaddingMs}ms thr=${settings.currentSkipSilenceThreshold})")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        // Silence-skipping sits in the audio pipeline ahead of Sonic (playback speed/pitch), starts
        // disabled, and is toggled per book via CMD_SET_SKIP_SILENCE. Unlike Media3's processor this
        // one stays in the sink's active chain permanently and re-reads its tuning on the fly, so
        // both the toggle and the Settings sliders take effect immediately (see the class doc).
        val silence = LiveSilenceSkippingProcessor()
        silence.setParams(
            minSilenceUs = settings.currentSkipSilenceMinMs * 1_000L,
            silenceKeepUs = settings.currentSkipSilencePaddingMs * 1_000L,
            thresholdLevel = settings.currentSkipSilenceThreshold.toShort()
        )
        silence.setEnabled(false)
        silenceProcessor = silence

        channelMix.balance = settings.currentAudioBalance
        channelMix.mono = settings.currentMonoAudio

        // Keep the processor's tuning in sync with Settings while audio is playing.
        // DataStore re-emits the full snapshot on EVERY write to ANY key, and navigation writes
        // prefs constantly (last-open-book, series-cover flag, ...) — so dedupe, and only nudge
        // the pipeline (a seekTo that audibly flushes/rewinds the sink) when the silence params
        // actually changed. Without both guards, plain navigation glitched live audio.
        serviceScope.launch {
            combine(
                settings.skipSilenceMinMs,
                settings.skipSilencePaddingMs,
                settings.skipSilenceThreshold
            ) { minMs, padMs, thr -> Triple(minMs, padMs, thr) }
                .distinctUntilChanged()
                .collect { (minMs, padMs, thr) ->
                    if (silence.setParams(minMs * 1_000L, padMs * 1_000L, thr.toShort())) {
                        nudgeAudioPipeline()
                    }
                }
        }

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(silence, SonicAudioProcessor(), channelMix)
                    )
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }

        // Damaged-file chain. Both wrappers are inert unless the URI carries their marker, so a
        // healthy file takes exactly the path it always did:
        //  - GapSkippingDataSource hides the byte ranges Mp3DamageScanner found ANYWHERE in the
        //    file, so the extractor never sees a run of garbage it can't resync past (ExoPlayer
        //    gives up after 128 KB; the gaps in a download-damaged audiobook are far bigger).
        //  - SkipHeadDataSource is the older, head-only special case, kept for the corrupt-header
        //    retry that fires before a full scan has run.
        val damageTolerantFactory = DataSource.Factory {
            GapSkippingDataSource(
                SkipHeadDataSource(DefaultDataSource.Factory(this).createDataSource())
            )
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(LargeFileMediaSourceFactory(damageTolerantFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = player

        // Hold a PARTIAL_WAKE_LOCK while actually playing. Without this, the only thing keeping
        // the CPU up for the audio pipeline is the foreground service itself — which is not
        // enough once the Activity is gone (app swiped from recents) and the screen goes off.
        // ExoPlayer takes the lock only while playWhenReady && state != IDLE/ENDED and drops it
        // on pause/release, so there is no idle drain. WAKE_MODE_LOCAL, not _NETWORK: every
        // source this app plays is a local file, so the WifiLock the latter adds is dead weight.
        player.setWakeMode(C.WAKE_MODE_LOCAL)

        // Assign a known audio session up front so the LoudnessEnhancer can attach
        // immediately and reliably, rather than depending only on the callback (which can
        // fire late, with an unset id, or be missed entirely on some devices).
        val sid = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        } catch (_: Exception) { AudioManager.ERROR }
        if (sid != AudioManager.ERROR && sid != C.AUDIO_SESSION_ID_UNSET) {
            try { player.setAudioSessionId(sid) } catch (e: Exception) {
                AppLog.e(LogCat.PLAYBACK, "setAudioSessionId failed", e)
            }
            attachLoudnessEnhancer(sid)
            attachEqualizer(sid)
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachLoudnessEnhancer(audioSessionId)
                attachEqualizer(audioSessionId)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { startPositionSaver(); sleepTimerEngine.maybeAutoArmScheduled() }
                else { stopPositionSaver(); saveCurrentPositionAndFlush() }
                pushWidgetState()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    sleepTimerEngine.resetScheduleArmedForNewSession()
                }
                // Persist the file change immediately, not just on the next periodic tick — the
                // plan for widening that tick's interval (see startPositionSaver) assumed pause/
                // stop/file-transition already flushed, but transition never did. Without this, a
                // kill shortly after auto-advancing to the next file could resume from a stale
                // (previous) file instead of just an old position within the right one. Calls
                // repository.updatePosition directly rather than saveCurrentPosition(), which
                // skips a positionMs <= 0 read — exactly the position a fresh transition starts at.
                mediaItem?.let { item ->
                    // A book is loaded, so from here on this service has to outlive the Activity.
                    // Same hook, same reasoning as ensureChapterTimeline below: PLAYLIST_CHANGED
                    // lands here for every load path there is.
                    ensureStartedService()
                    val bookId = item.mediaMetadata.extras?.getLong("bookId", -1L) ?: -1L
                    val fileId = item.mediaId.toLongOrNull()
                    if (bookId != -1L && fileId != null) {
                        val posMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        // Mirrors to disk too, not just the DB: a file transition is a real
                        // durability point for a listener who never pauses (overnight/driving) —
                        // without this, disk stays stuck on the file the session started on.
                        serviceScope.launch(Dispatchers.IO) {
                            repository.updatePosition(bookId, fileId, posMs)
                            diskMirror.flushBook(bookId)
                        }
                    }
                    // Covers every playlist-load path (PlayerController.playBook, SeriesPlayer,
                    // loadLastPlayedAndPlay) — Media3 fires this transition with
                    // MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED whenever setMediaItems lands.
                    if (bookId != -1L) ensureChapterTimeline(bookId)
                }
                // Read the real queue rather than trusting a null mediaItem: this is the one
                // signal that unambiguously means "book closed" (mini-bar fling, widget close).
                if (exoPlayer?.mediaItemCount == 0) releaseStartedService()
                pushWidgetState()
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = handleJumpDetection(oldPosition, newPosition, reason)
        })

        // External transport controls (headphones, Bluetooth, lock screen, notification) drive
        // the session's seek-to-next/previous commands, which by default jump whole audio files
        // (acting like chapter skip). Wrap the player so those commands become time-based skips
        // instead. The in-app "next/previous part" buttons use seekTo(index) directly, so they
        // bypass this and still change files.
        val skippingPlayer = object : ForwardingPlayer(player) {
            private fun skipBy(deltaMs: Long) {
                val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
                player.seekTo(target)
            }
            override fun seekToNext()              = skipBy(settings.currentSkipForwardMs)
            override fun seekToNextMediaItem()     = skipBy(settings.currentSkipForwardMs)
            override fun seekForward()             = skipBy(settings.currentSkipForwardMs)
            override fun seekToPrevious()          = skipBy(-settings.currentSkipBackMs)
            override fun seekToPreviousMediaItem() = skipBy(-settings.currentSkipBackMs)
            override fun seekBack()                = skipBy(-settings.currentSkipBackMs)
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_BACK)
                    .add(Player.COMMAND_SEEK_FORWARD)
                    .build()
        }

        mediaSession = MediaSession.Builder(this, skippingPlayer)
            .setCallback(SessionCallback())
            .build()

        // Because of that ForwardingPlayer, the notification's outer two buttons skip by time —
        // but Media3's defaults for those commands are the track-skip arrows (|<< / >>|), which
        // read as "previous/next file". Re-icon them with the player's own circular seek glyph so
        // the notification shows what the buttons actually do.
        setMediaNotificationProvider(SkipIconNotificationProvider(this))

        // Purely diagnostic. When the foreground-service promotion is refused (notification
        // permission denied, or a background-start restriction), Media3 swallows it and
        // isPlaybackOngoing() — which is what its onTaskRemoved consults, see below — is quietly
        // false forever after. That is one of the ways playback used to die on an app swipe with
        // nothing in the log to say why. Distinct from the MediaController.Listener in
        // PlayerController; this one is the service-side hook.
        setListener(object : MediaSessionService.Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                AppLog.e(
                    LogCat.PLAYBACK,
                    "Foreground service start NOT allowed — playback will not survive an app swipe"
                )
            }
        })

        btAutoResumeWatcher.register()
        registerScreenStateReceiver()
    }

    private fun registerScreenStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOn = false
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        // The widget bitmap only changes when we actually render it — force one
                        // immediate, fully correct re-render now rather than leaving it frozen at
                        // whatever value was showing when the screen went dark, until the sleep
                        // tick's next natural second ticks over.
                        if (sleepTimerEngine.mode != SLEEP_MODE_OFF) widgetUpdater.requestRender()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenStateReceiver = receiver
    }

    private fun attachLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (attachedSessionId == audioSessionId && loudnessEnhancer != null) return
        loudnessEnhancer?.release()
        loudnessEnhancer = try {
            LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(boostMb)
                enabled = boostMb > 0
            }.also { attachedSessionId = audioSessionId }
        } catch (e: Exception) {
            AppLog.e(LogCat.PLAYBACK, "LoudnessEnhancer attach failed for session $audioSessionId", e)
            attachedSessionId = C.AUDIO_SESSION_ID_UNSET
            null
        }
    }

    private fun attachEqualizer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        if (attachedEqSessionId == audioSessionId && equalizer != null) return
        equalizer?.release()
        equalizer = try {
            Equalizer(0, audioSessionId).apply {
                applyEqBands(this, eqBandsJson)
            }.also { attachedEqSessionId = audioSessionId }
        } catch (e: Exception) {
            AppLog.e(LogCat.PLAYBACK, "Equalizer attach failed for session $audioSessionId", e)
            attachedEqSessionId = C.AUDIO_SESSION_ID_UNSET
            null
        }
    }

    private fun applyEq(bandsJson: String?) {
        eqBandsJson = bandsJson?.takeIf { it.isNotEmpty() }
        if (equalizer == null) {
            val sid = exoPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
            attachEqualizer(sid)
        }
        equalizer?.let { applyEqBands(it, eqBandsJson) }
    }

    private fun applyEqBands(eq: Equalizer, bandsJson: String?) {
        try {
            if (bandsJson.isNullOrEmpty()) {
                for (i in 0 until eq.numberOfBands) eq.setBandLevel(i.toShort(), 0)
                eq.enabled = false
                return
            }
            val arr = JSONArray(bandsJson)
            for (i in 0 until minOf(arr.length(), eq.numberOfBands.toInt())) {
                eq.setBandLevel(i.toShort(), arr.getInt(i).toShort())
            }
            eq.enabled = true
        } catch (e: Exception) {
            AppLog.e(LogCat.PLAYBACK, "applyEqBands failed", e)
        }
    }

    /**
     * Toggle silence skipping for the current book. [LiveSilenceSkippingProcessor] stays in the
     * sink's active chain permanently, so the toggle only needs a flush (not a reconfigure) —
     * which is exactly what the seek nudge provides. No-op if the state is unchanged.
     */
    private fun applySkipSilence(enabled: Boolean) {
        val proc = silenceProcessor ?: return
        if (skipSilenceEnabled == enabled) return
        skipSilenceEnabled = enabled
        AppLog.i(LogCat.PLAYBACK, "applySkipSilence=$enabled")
        proc.setEnabled(enabled)
        nudgeAudioPipeline()
    }

    /** Force the audio sink to flush so a changed silence-skipping state takes effect now. */
    private fun nudgeAudioPipeline() {
        exoPlayer?.let { if (it.mediaItemCount > 0) it.seekTo(it.currentPosition) }
    }

    /** Global (not per-book) balance/mono — [ChannelMixProcessor] reads its `@Volatile` fields
     *  live on the audio-render thread, so this takes effect on the very next buffer; the nudge
     *  just shortens how long already-buffered audio plays out unchanged first. */
    private fun applyChannelMix(balance: Float, mono: Boolean) {
        val changed = channelMix.balance != balance || channelMix.mono != mono
        channelMix.balance = balance.coerceIn(-1f, 1f)
        channelMix.mono = mono
        if (changed) nudgeAudioPipeline()
    }

    private fun applyBoost(mb: Int) {
        boostMb = mb.coerceIn(0, 2400)
        // The enhancer may not be attached yet (audio session not initialized). Try to
        // attach now using the player's live session id before applying the gain.
        if (loudnessEnhancer == null) {
            val sid = exoPlayer?.audioSessionId ?: C.AUDIO_SESSION_ID_UNSET
            attachLoudnessEnhancer(sid)
        }
        loudnessEnhancer?.let {
            try {
                it.setTargetGain(boostMb)
                it.enabled = boostMb > 0
            } catch (e: Exception) {
                AppLog.e(LogCat.PLAYBACK, "applyBoost($boostMb) failed", e)
            }
        }
    }

    private fun saveCurrentPosition() {
        val player = exoPlayer ?: return
        val positionMs = player.currentPosition
        if (positionMs <= 0L) return
        val item = player.currentMediaItem ?: return
        val fileId = item.mediaId.toLongOrNull() ?: return
        val bookId = item.mediaMetadata.extras?.getLong("bookId", -1L) ?: -1L
        if (bookId == -1L) return
        // @ApplicationScope, not serviceScope: onDestroy calls this then cancels serviceScope
        // twenty lines later, and coroutine dispatch is async — a save launched on serviceScope
        // can lose the race and never run. Mirrors widgetUpdater.pushPaused()'s own durable scope.
        appScope.launch(Dispatchers.IO) {
            AppLog.i(LogCat.PLAYBACK, "saveCurrentPosition book=$bookId file=$fileId pos=${positionMs}ms")
            repository.updatePosition(bookId, fileId, positionMs)
        }
    }

    /** Like [saveCurrentPosition], but also flushes the disk mirror once the DB write lands —
     *  used at the cadence's actual flush points (pause, book-close), not the 30s continuous-
     *  playback tick, which deliberately only marks the book dirty (see startPositionSaver). The
     *  position must be read here, synchronously on the caller's thread (main — exoPlayer is not
     *  thread-safe), not inside the launched coroutine, which runs on Dispatchers.IO. */
    private fun saveCurrentPositionAndFlush() {
        val player = exoPlayer ?: return
        val positionMs = player.currentPosition
        if (positionMs <= 0L) return
        val item = player.currentMediaItem ?: return
        val fileId = item.mediaId.toLongOrNull() ?: return
        val bookId = item.mediaMetadata.extras?.getLong("bookId", -1L) ?: -1L
        if (bookId == -1L) return
        appScope.launch(Dispatchers.IO) {
            AppLog.i(LogCat.PLAYBACK, "saveCurrentPosition(flush) book=$bookId file=$fileId pos=${positionMs}ms")
            repository.updatePosition(bookId, fileId, positionMs)
            diskMirror.flushDirty()
        }
    }

    /** Persists a speed change made from the widget — without this, a widget speed-up/down is
     *  silently reverted the next time this book loads (only [com.betteraudio.ui.player.PlayerViewModel.setSpeed]
     *  used to persist it). */
    private fun persistCurrentSpeed(speed: Float) {
        val bookId = exoPlayer?.currentMediaItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
        if (bookId == -1L) return
        serviceScope.launch(Dispatchers.IO) { repository.updateSpeed(bookId, speed) }
    }

    private fun startPositionSaver() {
        if (positionSaverJob?.isActive == true) return
        positionSaverJob = serviceScope.launch {
            while (isActive) {
                // 30s, not 5s: pause/stop/file-transition/onTaskRemoved/onDestroy all flush
                // separately (see onMediaItemTransition above and G1-5/G1-9's fixes to the
                // others), so this interval only bounds how stale the position can get *within*
                // the current file during continuous, uninterrupted playback — not which file.
                // Each tick re-emits the entire home grid flow (G2-1/G2-2 made that emission
                // itself cheap; this is about not causing it as often in the first place).
                delay(30_000)
                saveCurrentPosition()
            }
        }
    }

    private fun stopPositionSaver() {
        positionSaverJob?.cancel()
        positionSaverJob = null
    }

    /**
     * Converts this service from bound-only to *started*, so that it outlives the Activity being
     * destroyed when the user swipes the app away from recents.
     *
     * [onTaskRemoved] declining to stop the service is not on its own enough. Media3 calls
     * `startForegroundService` itself only once playback actually *begins* — so a book that is
     * loaded but has never played (restored paused at launch, say, or paused before it was ever
     * started) leaves this service held open by nothing but PlayerController's MediaController
     * binding. `MainActivity.onDestroy` releases that binding on the swipe, and a bound-only
     * service dies with its last client no matter what onTaskRemoved decided. Starting it here,
     * the moment a book is loaded, closes that hole.
     *
     * Plain `startService`, not `startForegroundService`: the latter obliges us to post a
     * foreground notification within ~5 s or be killed with
     * `ForegroundServiceDidNotStartInTimeException`, and a merely-loaded, not-yet-playing book
     * has no notification to post. Media3 still does its own foreground promotion when playback
     * starts; this only supplies the "started" status, which is the part that survives an unbind.
     *
     * Background-start restrictions (API 26+) can refuse this if a book loads while the app is
     * already backgrounded — a series auto-advance, for instance. That case needs no rescue:
     * playback is ongoing there, so Media3 has started the service itself. Hence `runCatching`
     * and a log line rather than a crash on a path that is already covered.
     */
    private fun ensureStartedService() {
        if (selfStarted) return
        val result = runCatching {
            startService(Intent(this, PlaybackService::class.java).setAction(ACTION_KEEP_ALIVE))
        }
        selfStarted = result.isSuccess
        if (result.isSuccess) AppLog.i(LogCat.PLAYBACK, "Service self-started — will survive an app swipe")
        else AppLog.w(LogCat.PLAYBACK, "Service self-start refused (${result.exceptionOrNull()?.javaClass?.simpleName}) — relying on Media3's own foreground start")
    }

    /**
     * Undoes [ensureStartedService] once the queue is empty (book closed from the mini-bar fling
     * or the widget's close button). `stopSelf()` on a service that is still bound does not
     * destroy it — it only drops the "started" status, restoring exactly the pre-fix lifecycle
     * where the service goes away with its last client. Without this, closing a book would park
     * an empty service in memory indefinitely, which is a real regression rather than the point
     * of the change.
     */
    private fun releaseStartedService() {
        if (!selfStarted) return
        selfStarted = false
        runCatching { stopSelf() }
        AppLog.i(LogCat.PLAYBACK, "Queue empty — service no longer self-started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player
        intent?.action?.let {
            // KEEP_ALIVE is ours, not the widget's — logging it under WIDGET would read as a
            // phantom button press once per book load.
            if (it.startsWith("com.betteraudio") && it != ACTION_KEEP_ALIVE) {
                AppLog.i(LogCat.WIDGET, "action=$it loaded=${player?.mediaItemCount ?: 0}")
            }
        }
        when (intent?.action) {
            // Deliberately nothing. The delivery itself is the point: it is what makes this a
            // started service. See ensureStartedService.
            ACTION_KEEP_ALIVE -> Unit
            ACTION_TOGGLE_PLAY_PAUSE -> player?.let {
                // Cold widget tap: nothing loaded yet → load the last-played book and start,
                // entirely inside the service. No Activity needs to open.
                if (it.mediaItemCount == 0) loadLastPlayedAndPlay()
                else {
                    if (it.isPlaying) it.pause() else it.play()
                    pushWidgetState()
                }
            }
            ACTION_SKIP_FORWARD -> player?.let {
                it.seekTo(it.currentPosition + settings.currentSkipForwardMs)
            }
            ACTION_SKIP_BACK -> player?.let {
                it.seekTo(maxOf(0L, it.currentPosition - settings.currentSkipBackMs))
            }
            ACTION_CHAPTER_FORWARD -> exoPlayer?.let {
                if (it.hasNextMediaItem()) it.seekToNextMediaItem()
                pushWidgetState()
            }
            ACTION_CHAPTER_BACK -> exoPlayer?.let {
                if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L)
                pushWidgetState()
            }
            ACTION_SPEED_UP -> player?.let {
                val newSpeed = (it.playbackParameters.speed + SPEED_STEP).coerceIn(0.5f, 3.0f)
                it.setPlaybackSpeed(newSpeed)
                persistCurrentSpeed(newSpeed)
                pushWidgetState()
            }
            ACTION_SPEED_DOWN -> player?.let {
                val newSpeed = (it.playbackParameters.speed - SPEED_STEP).coerceIn(0.5f, 3.0f)
                it.setPlaybackSpeed(newSpeed)
                persistCurrentSpeed(newSpeed)
                pushWidgetState()
            }
            ACTION_BOOST_UP -> {
                applyBoost((boostMb + BOOST_STEP_MB).coerceIn(0, 2400))
                pushWidgetState()
            }
            ACTION_BOOST_DOWN -> {
                applyBoost((boostMb - BOOST_STEP_MB).coerceIn(0, 2400))
                pushWidgetState()
            }
            ACTION_QUICK_BOOKMARK -> addQuickBookmark()
            ACTION_CLOSE_BOOK -> closeBook()
            ACTION_SLEEP_TIMER_TOGGLE -> {
                if (sleepTimerEngine.mode != SLEEP_MODE_OFF) {
                    sleepTimerEngine.setTimer(SLEEP_MODE_OFF, 0L, 0L)
                } else {
                    // No explicit per-element duration on the intent → arm the SAME duration the
                    // player would (SettingsStore.currentSleepTimerMinutes, its synchronous
                    // snapshot of the user's last-chosen SLEEP_TIMER_MINUTES) so widget and
                    // player always agree, instead of a separate hardcoded 15-minute fallback.
                    val durationMs = if (intent.hasExtra(EXTRA_SLEEP_DURATION_MS)) {
                        intent.getLongExtra(EXTRA_SLEEP_DURATION_MS, 15 * 60_000L)
                    } else {
                        settings.currentSleepTimerMinutes * 60_000L
                    }
                    sleepTimerEngine.setTimer(SLEEP_MODE_COUNTDOWN, durationMs, 0L)
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // ── Headset multi-press mapping ──────────────────────────────────────────────
    private fun performHeadsetAction(action: String) {
        val player = mediaSession?.player ?: return
        when (action) {
            "play_pause" -> if (player.isPlaying) player.pause() else player.play()
            "skip_forward" -> player.seekTo(player.currentPosition + settings.currentSkipForwardMs)
            "skip_back" -> player.seekTo(maxOf(0L, player.currentPosition - settings.currentSkipBackMs))
            "next_chapter" -> exoPlayer?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
            "prev_chapter" -> exoPlayer?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L) }
            "bookmark" -> addQuickBookmark()
            "none" -> {}
        }
    }

    /** Resumes only if something is actually loaded, not already playing, and was paused within
     *  the configured window — otherwise a stale/irrelevant book could start blaring from a
     *  months-old pause the moment any headphones connect. Known limitation: only fires while
     *  this service process is alive (a fully killed app won't have anything loaded to resume). */
    private fun maybeAutoResumeOnDeviceConnect() {
        val player = exoPlayer ?: return
        if (player.isPlaying || player.mediaItemCount == 0) return
        val item = player.currentMediaItem ?: return
        val bookId = item.mediaMetadata.extras?.getLong("bookId", -1L) ?: -1L
        if (bookId == -1L) return
        serviceScope.launch {
            val progress = repository.getProgressForBookOnce(bookId) ?: return@launch
            if (progress.lastPausedAt <= 0L) return@launch
            val windowMs = settings.currentBtAutoResumeWindowMinutes * 60_000L
            val elapsed = System.currentTimeMillis() - progress.lastPausedAt
            if (elapsed in 0..windowMs) {
                AppLog.i(LogCat.PLAYBACK, "BT/headphone connected — auto-resuming book=$bookId (paused ${elapsed / 1000}s ago)")
                player.play()
            }
        }
    }

    /** Note-less bookmark at the current position, for the widget's quick-bookmark element. */
    private fun addQuickBookmark() {
        val player = exoPlayer ?: return
        val item = player.currentMediaItem ?: return
        val fileId = item.mediaId.toLongOrNull() ?: return
        val bookId = item.mediaMetadata.extras?.getLong("bookId", -1L) ?: -1L
        if (bookId == -1L) return
        val positionMs = player.currentPosition
        serviceScope.launch(Dispatchers.IO) {
            repository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    fileId = fileId,
                    positionInFileMs = positionMs,
                    absolutePositionMs = positionMs,
                    comment = "",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    /** Stops playback and saves position, mirroring the mini-bar fling-to-close gesture. */
    private fun closeBook() {
        saveCurrentPositionAndFlush()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        pushWidgetState()
    }

    // ── Sleep timer ──────────────────────────────────────────────────────────────
    // The mode/tick/fade/shake orchestration lives in SleepTimerEngine; this is just the glue
    // that talks to the real MediaSession (a controller has no way to notice a shake-extend or a
    // scheduled auto-arm just by polling its own state, since both happen entirely service-side).
    private fun broadcastSleepState(mode: String, remainingMs: Long) {
        val session = mediaSession ?: return
        session.broadcastCustomCommand(
            SessionCommand(CMD_SLEEP_STATE_CHANGED, Bundle.EMPTY),
            Bundle().apply {
                putString(KEY_SLEEP_MODE, mode)
                putLong(KEY_SLEEP_REMAINING_MS, remainingMs)
            }
        )
    }

    /** Live jump detection: runs on the service's own `Player.Listener`, attached to the real
     *  ExoPlayer, so it sees every discontinuity for both foreground and background/cold-widget
     *  playback (unlike `PlayerController`, which only connects once `MainActivity` is opened).
     *  Whitelist classification (see [JumpClassifier]) — only an unexplained INTERNAL move beyond
     *  threshold is ever flagged; a SEEK/SEEK_ADJUSTMENT means the user (or the app) genuinely
     *  navigated, so any pending restore offer for this book is no longer meaningful. */
    private fun handleJumpDetection(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val bookId = exoPlayer?.currentMediaItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
        if (bookId == -1L) return

        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            jumpRestoreStore.clear(bookId)
            return
        }
        if (reason != Player.DISCONTINUITY_REASON_INTERNAL) return
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
            // Cross-item INTERNAL discontinuities fall outside bookPositionMsFor's safe envelope
            // (not-yet-reached windows can still read as C.TIME_UNSET) — bail rather than risk a
            // mis-thresholded false flag.
            return
        }

        val oldBookPosMs = bookPositionMsFor(oldPosition.mediaItemIndex, oldPosition.positionMs)
        val newBookPosMs = bookPositionMsFor(newPosition.mediaItemIndex, newPosition.positionMs)
        if (JumpClassifier.classify(reason, oldBookPosMs, newBookPosMs) == JumpDecision.FLAG) {
            AppLog.i(
                LogCat.PLAYBACK,
                "Unexpected jump: $oldBookPosMs -> $newBookPosMs reason=INTERNAL deltaMs=${newBookPosMs - oldBookPosMs}; offering restore"
            )
            jumpRestoreStore.set(JumpRestore(oldBookPosMs, bookId, System.currentTimeMillis()))
        }
    }

    /** Absolute book-level position for (media-item index, position within that item), summing
     *  the durations of every *earlier* window from the live ExoPlayer timeline. Only ever called
     *  with the CURRENT index, so those windows are ones playback has already passed through and
     *  their durations are reliably resolved (unlike not-yet-reached windows, which can still read
     *  as C.TIME_UNSET) — deliberately never used to look up a not-yet-played target index. */
    private fun bookPositionMsFor(index: Int, positionInItemMs: Long): Long {
        val player = exoPlayer ?: return 0L
        if (index < 0) return 0L
        val timeline = player.currentTimeline
        val window = androidx.media3.common.Timeline.Window()
        var acc = 0L
        for (i in 0 until index) {
            if (i >= timeline.windowCount) break
            acc += timeline.getWindow(i, window).durationMs.coerceAtLeast(0L)
        }
        return acc + positionInItemMs
    }

    /**
     * Build the last-played book's playlist directly on the service player and start it. Used
     * when the widget's play button is pressed from cold (app killed, nothing loaded) so the
     * user can resume without opening the app. Mirrors [PlayerController.playBook]'s media-item
     * shape and per-book boost/EQ restore.
     */
    private fun loadLastPlayedAndPlay() {
        val player = mediaSession?.player ?: return
        serviceScope.launch {
            val bookId = settings.lastPlayedBookId.first()
            if (bookId == -1L) return@launch
            val book = repository.getBookOnce(bookId) ?: return@launch
            val files = repository.getAudioFilesOnce(bookId)
                .sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            if (files.isEmpty()) return@launch
            val progress = repository.getProgressForBookOnce(bookId)
            val series = book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
            val gPreset = repository.getDefaultAudioPreset()
            val audio = AudioCascade.resolve(book, progress, series, gPreset, settings.currentDefaultSpeed)
            // Same auto-rewind as every other resume path (PlayerViewModel.play() etc.) — a cold
            // widget tap shouldn't behave differently just because no Activity is open yet.
            // resolveStart forces file 0 / position 0 for a finished book — see its KDoc.
            val rewind = AudioCascade.autoRewindMs(settings, progress?.lastPausedAt ?: 0L)
            val (startIndex, startPos) = AudioCascade.resolveStart(files, progress, rewind, bookId, jumpRestoreStore)
            AppLog.i(LogCat.PLAYBACK, "widget loadLastPlayedAndPlay book=$bookId" +
                " dbFile=${progress?.currentFileId} dbPos=${progress?.positionMs}ms isCompleted=${progress?.isCompleted}" +
                " → rewind=${rewind}ms startIdx=$startIndex startPos=${startPos}ms")

            val items = files.map { file ->
                // Uri.fromFile percent-encodes; "file://$path" breaks on '%' or '#' in a name.
                val base = Uri.fromFile(java.io.File(file.filePath))
                // Apply any cached damage map, exactly as PlayerController.buildMediaItem does.
                // This path used to build plain file URIs, which meant a damaged file resumed from
                // the widget re-hit the failure the in-app player had already repaired around.
                val gaps = Mp3DamageScanner.decodeUsable(file.damageRangesJson, file.sizeOnDisk())
                val uri = if (gaps.isEmpty()) base else GapSkippingDataSource.wrapUri(base, gaps)
                MediaItem.Builder()
                    .setMediaId(file.id.toString())
                    .setUri(uri)
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(uri)
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setAlbumTitle(book.displayTitle)
                            .setArtist(book.displayAuthor)
                            .setTitle(file.chapterTitle ?: file.fileName)
                            .setArtworkUri(book.coverArtPath?.let { Uri.parse("file://$it") })
                            .setExtras(Bundle().apply {
                                putLong("bookId", book.id)
                            })
                            .build()
                    )
                    .build()
            }
            player.setMediaItems(items, startIndex, startPos)
            player.setPlaybackSpeed(audio.speed)
            player.prepare()
            player.play()
            // Restore the cascaded boost/EQ (mb = dB * 100) + skip-silence preference.
            applyBoost(audio.boostDb * 100)
            applyEq(audio.eqBandsJson)
            applySkipSilence(audio.skipSilence)
            repository.touchLastPlayed(bookId)
            // Belt-and-braces: onMediaItemTransition's own hook already covers this once Media3
            // fires the transition, but kick the load off here too so it's in flight the moment
            // the queue exists rather than waiting on that callback.
            ensureChapterTimeline(bookId)
            pushWidgetState()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * App swiped from recents. This is a **no-op for the player** — whatever it was doing, it
     * carries on doing. Playing keeps playing; paused stays paused-and-loaded, resumable from the
     * notification or the widget without a cold start. Closing the app is not a playback command.
     *
     * That means deliberately not calling through to [MediaSessionService.onTaskRemoved], whose
     * 1.10.0 implementation is:
     *
     *     if (!isPlaybackOngoing() || !isAnySessionPlaying()) pauseAllPlayersAndStopSelf();
     *
     * `isPlaybackOngoing()` is `mediaNotificationManager.isStartedInForeground()`, so the
     * superclass spares a session only when it is *both* promoted to a foreground service *and*
     * reporting isPlaying at this exact instant. Anything else — paused, buffering, a momentary
     * audio-focus duck, a refused FGS promotion (see the Listener in onCreate), an OEM that has
     * already frozen the process — took the stopSelf() path into onDestroy(), which releases the
     * player and flips the widget to paused. Even for a genuinely playing book that made survival
     * a race rather than a rule.
     *
     * The one case still handed to super is an **empty** service (no media items at all, e.g.
     * after the widget's close-book action). There is no player state to preserve there, so
     * letting it stop is invisible to the user and avoids parking an idle service in memory.
     *
     * The user's escape hatch is unchanged and explicit: dismiss the media notification, or fling
     * the mini bar down / use the widget's close action, all of which stop playback deliberately.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Read directly from ExoPlayer, not through the MediaController proxy, which can
        // transiently report 0 during reconnect. Main thread — ExoPlayer is not thread-safe.
        val player = exoPlayer
        val hasBookLoaded = (player?.mediaItemCount ?: 0) > 0
        AppLog.i(
            LogCat.PLAYBACK,
            "onTaskRemoved — hasBookLoaded=$hasBookLoaded isPlaying=${player?.isPlaying} " +
                "playWhenReady=${player?.playWhenReady} playbackOngoing=${isPlaybackOngoing()}"
        )

        // No runBlocking on either path. The process is staying alive, so the appScope coroutine
        // this launches finishes normally — and blocking the main thread on Room plus a
        // possibly-unmounted SD card inside the task-removal frame is exactly the kind of stall
        // that invites the system to kill us, which is the outcome this whole method exists to
        // avoid. (Even in the empty-service case below there is nothing to lose: no media item
        // means saveCurrentPositionAndFlush returns without writing.)
        saveCurrentPositionAndFlush()

        if (!hasBookLoaded) super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        AppLog.i(LogCat.PLAYBACK, "onDestroy — book=${exoPlayer?.currentMediaItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L} pos=${exoPlayer?.currentPosition ?: -1L}ms")
        widgetUpdater.pushPaused()
        stopPositionSaver()
        // Read synchronously here (main thread — exoPlayer is not thread-safe), not inside the
        // launch below, which runs on Dispatchers.IO. Not runBlocking: onDestroy must not stall
        // service teardown waiting on disk IO. appScope (not serviceScope, cancelled a few lines
        // below) is what keeps this coroutine alive long enough to finish — same reasoning as
        // saveCurrentPosition's own doc comment.
        val destroyPositionMs = exoPlayer?.currentPosition ?: 0L
        val destroyItem = exoPlayer?.currentMediaItem
        val destroyFileId = destroyItem?.mediaId?.toLongOrNull()
        val destroyBookId = destroyItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
        appScope.launch(Dispatchers.IO) {
            if (destroyPositionMs > 0L && destroyBookId != -1L && destroyFileId != null) {
                repository.updatePosition(destroyBookId, destroyFileId, destroyPositionMs)
            }
            diskMirror.flushDirty()
        }
        sleepTimerEngine.stop()
        headsetGestureMapper.cancel()
        btAutoResumeWatcher.unregister()
        screenStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenStateReceiver = null
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
        equalizer?.release()
        equalizer = null
        attachedEqSessionId = C.AUDIO_SESSION_ID_UNSET
        mediaSession?.run {
            player.release()   // ForwardingPlayer.release() releases the wrapped ExoPlayer
            release()
            mediaSession = null
        }
        exoPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private data class ChapterFieldsForWidget(val title: String, val positionMs: Long, val durationMs: Long)

    /**
     * Resolves the widget's per-chapter fields from [chapterTimeline] when it's loaded for
     * [bookId]; falls back to file-scoped values (the pre-fix behaviour — file title/position/
     * duration standing in for the chapter's) while a load is still in flight or the book has no
     * chapter data at all, so nothing regresses in that window.
     *
     * Deliberately does NOT use [bookPositionMsFor] here: that sums ExoPlayer *Timeline* window
     * durations, which read as `C.TIME_UNSET` for not-yet-prepared items (see its own doc) — wrong
     * input for a chapter lookup near a file boundary. [ChapterTimeline.startOfFileMs] is
     * Room-derived and always safe to add the current item's own (always-valid) position to.
     */
    private fun chapterFieldsForWidget(player: Player, bookId: Long, fallbackTitle: String): ChapterFieldsForWidget {
        val timeline = chapterTimeline
        val mediaId = player.currentMediaItem?.mediaId?.toLongOrNull()
        val fallback = ChapterFieldsForWidget(fallbackTitle, player.currentPosition, player.duration.coerceAtLeast(0L))
        if (timeline.bookId != bookId || timeline.isEmpty || mediaId == null) return fallback
        val absBookPos = timeline.startOfFileMs(mediaId) + player.currentPosition
        val mark = timeline.chapterAt(absBookPos) ?: return fallback
        return ChapterFieldsForWidget(mark.title, absBookPos - mark.startMs, mark.durationMs)
    }

    /** Builds a WidgetSnapshot from live player state and hands it to WidgetUpdater, which
     *  persists it (so a cold-started widget always has real data) and renders every placed
     *  widget directly — no exported broadcast, so this can't be throttled or spoofed. */
    fun pushWidgetState() {
        val player = mediaSession?.player ?: return
        val meta = player.currentMediaItem?.mediaMetadata
        val isPlaying = player.isPlaying
        val title = meta?.albumTitle?.toString() ?: ""
        val author = meta?.artist?.toString() ?: ""
        val fileTitle = meta?.title?.toString() ?: ""
        val speed = player.playbackParameters.speed
        val boostDbVal = boostMb / 100
        val sleepEnd = sleepTimerEngine.sleepEndAtElapsedMsForWidget
        val sleepRemaining = sleepTimerEngine.remainingMsForWidget
        val bookId = meta?.extras?.getLong("bookId", -1L) ?: -1L
        val positionMs = bookPositionMsFor(player.currentMediaItemIndex, player.currentPosition)
        val chapterFields = chapterFieldsForWidget(player, bookId, fileTitle)

        serviceScope.launch(Dispatchers.IO) {
            var seriesName = ""
            var bookCoverPath: String? = null
            var seriesCoverPath: String? = null
            var bookDurationMs = 0L
            if (bookId != -1L) {
                val book = repository.getBookOnce(bookId)
                bookCoverPath = book?.coverArtPath
                seriesName = book?.seriesName ?: ""
                bookDurationMs = book?.totalDurationMs ?: 0L
                val seriesId = book?.seriesId
                if (seriesId != null) {
                    seriesCoverPath = seriesRepository.getSeriesOnce(seriesId)?.coverArtPath
                }
            }
            widgetUpdater.push(
                WidgetSnapshot(
                    bookId = bookId,
                    title = title,
                    author = author,
                    chapterTitle = chapterFields.title,
                    seriesName = seriesName,
                    isPlaying = isPlaying,
                    speed = speed,
                    boostDb = boostDbVal,
                    bookCoverPath = bookCoverPath,
                    seriesCoverPath = seriesCoverPath,
                    positionMs = positionMs,
                    bookDurationMs = bookDurationMs,
                    chapterPositionMs = chapterFields.positionMs,
                    chapterDurationMs = chapterFields.durationMs,
                    sleepEndAtElapsedMs = sleepEnd,
                    sleepRemainingMs = sleepRemaining,
                    writtenAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                )
            )
        }
    }

    /** Cheaper twin of [pushWidgetState] for the END_OF_CHAPTER sleep tick's once-a-second
     *  re-render: reuses the book/series metadata cached in eocMeta* across ticks instead of
     *  re-querying the DB every second for values that essentially never change between two
     *  ticks one second apart. Only ever called while sleepMode == SLEEP_MODE_END_OF_CHAPTER, so
     *  the COUNTDOWN deadline field is always 0 here (matches pushWidgetState's own sleepEnd for
     *  that mode). */
    private fun pushWidgetStateForSleepTick() {
        val player = mediaSession?.player ?: return
        val meta = player.currentMediaItem?.mediaMetadata
        val isPlaying = player.isPlaying
        val title = meta?.albumTitle?.toString() ?: ""
        val author = meta?.artist?.toString() ?: ""
        val fileTitle = meta?.title?.toString() ?: ""
        val speed = player.playbackParameters.speed
        val boostDbVal = boostMb / 100
        val sleepRemaining = sleepTimerEngine.remainingMsForWidget
        val bookId = meta?.extras?.getLong("bookId", -1L) ?: -1L
        val positionMs = bookPositionMsFor(player.currentMediaItemIndex, player.currentPosition)
        val chapterFields = chapterFieldsForWidget(player, bookId, fileTitle)

        serviceScope.launch(Dispatchers.IO) {
            if (bookId != eocMetaBookId) {
                var seriesName = ""
                var bookCoverPath: String? = null
                var seriesCoverPath: String? = null
                var bookDurationMs = 0L
                if (bookId != -1L) {
                    val book = repository.getBookOnce(bookId)
                    bookCoverPath = book?.coverArtPath
                    seriesName = book?.seriesName ?: ""
                    bookDurationMs = book?.totalDurationMs ?: 0L
                    val seriesId = book?.seriesId
                    if (seriesId != null) {
                        seriesCoverPath = seriesRepository.getSeriesOnce(seriesId)?.coverArtPath
                    }
                }
                eocMetaSeriesName = seriesName
                eocMetaBookCoverPath = bookCoverPath
                eocMetaSeriesCoverPath = seriesCoverPath
                eocMetaBookDurationMs = bookDurationMs
                eocMetaBookId = bookId
            }
            widgetUpdater.push(
                WidgetSnapshot(
                    bookId = bookId,
                    title = title,
                    author = author,
                    chapterTitle = chapterFields.title,
                    seriesName = eocMetaSeriesName,
                    isPlaying = isPlaying,
                    speed = speed,
                    boostDb = boostDbVal,
                    bookCoverPath = eocMetaBookCoverPath,
                    seriesCoverPath = eocMetaSeriesCoverPath,
                    positionMs = positionMs,
                    bookDurationMs = eocMetaBookDurationMs,
                    chapterPositionMs = chapterFields.positionMs,
                    chapterDurationMs = chapterFields.durationMs,
                    sleepEndAtElapsedMs = 0L,
                    sleepRemainingMs = sleepRemaining,
                    writtenAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                )
            )
        }
    }

    /**
     * The stock notification provider, with the two seek buttons re-drawn as the app's time-based
     * skip control instead of Media3's track-skip arrows.
     *
     * Media3 builds those buttons around `COMMAND_SEEK_TO_{PREVIOUS,NEXT}_MEDIA_ITEM`, which the
     * session's [ForwardingPlayer] redefines as "skip back/forward by the configured interval" —
     * so only the icon (and its content description) was ever wrong. Matching on the button's
     * `icon` constant rather than its player command keeps this pinned to the two buttons whose
     * glyph is the problem, whichever command Media3 happens to wire them to.
     */
    private inner class SkipIconNotificationProvider(context: Context) :
        DefaultMediaNotificationProvider(context) {

        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean
        ): ImmutableList<CommandButton> {
            val buttons = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
            return ImmutableList.copyOf(buttons.map { button ->
                when (button.icon) {
                    CommandButton.ICON_PREVIOUS -> button.withSkipIcon(
                        R.drawable.ic_w_skip_back,
                        "Skip back ${settings.currentSkipBackMs / 1000} seconds"
                    )
                    CommandButton.ICON_NEXT -> button.withSkipIcon(
                        R.drawable.ic_w_skip_forward,
                        "Skip forward ${settings.currentSkipForwardMs / 1000} seconds"
                    )
                    else -> button
                }
            })
        }

        /** Rebuild [this] with a different drawable and label, carrying every other field over —
         *  `slots` in particular, since that is what fixes the button's position in the layout. */
        private fun CommandButton.withSkipIcon(iconRes: Int, name: String): CommandButton =
            CommandButton.Builder(icon)
                .setPlayerCommand(playerCommand)
                .setCustomIconResId(iconRes)
                .setDisplayName(name)
                .setExtras(extras)
                .setEnabled(isEnabled)
                .apply { if (slots.length() > 0) setSlots(*slots.toArray()) }
                .build()
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(SessionCommand(CMD_SET_BOOST, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_EQ, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_SKIP_SILENCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(CMD_SET_CHANNEL_MIX, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_SET_BOOST -> {
                    applyBoost(args.getInt(KEY_BOOST_MB, 0))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SET_EQ -> {
                    applyEq(args.getString(KEY_EQ_BANDS_JSON)?.takeIf { it.isNotEmpty() })
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SET_SKIP_SILENCE -> {
                    applySkipSilence(args.getBoolean(KEY_SKIP_SILENCE, false))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SET_SLEEP_TIMER -> {
                    sleepTimerEngine.setTimer(
                        newMode = args.getString(KEY_SLEEP_MODE, SLEEP_MODE_OFF),
                        durationMs = args.getLong(KEY_SLEEP_DURATION_MS, 0L),
                        targetBookPositionMsArg = args.getLong(KEY_SLEEP_TARGET_POSITION_MS, 0L)
                    )
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SET_CHANNEL_MIX -> {
                    applyChannelMix(args.getFloat(KEY_AUDIO_BALANCE, 0f), args.getBoolean(KEY_MONO_AUDIO, false))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            AppLog.w(LogCat.PLAYBACK, "onCustomCommand: unrecognized action '${customCommand.customAction}' from ${controller.packageName}")
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false

            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    session.player.seekTo(session.player.currentPosition + settings.currentSkipForwardMs)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    if (event.action != KeyEvent.ACTION_DOWN) return true
                    session.player.seekTo(maxOf(0L, session.player.currentPosition - settings.currentSkipBackMs))
                    return true
                }
            }

            // Multi-press mapping is opt-in: default "off" preserves today's zero-latency single
            // press (returning false here lets Media3's default play/pause/next/previous handling
            // fire immediately, same as before this feature existed). Many BT headsets already
            // debounce a double/triple click into a single MEDIA_NEXT/MEDIA_PREVIOUS keycode in
            // firmware — those never reach the HEADSETHOOK press-counting window below, so they're
            // treated as direct double-/triple-press equivalents instead.
            if (!settings.currentHeadsetMultiPressEnabled) return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // Count on ACTION_UP only — DOWN+UP both fire per physical click, and counting
                    // both would double every press.
                    if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                        headsetGestureMapper.onHeadsetHookPress()
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        performHeadsetAction(settings.currentHeadsetDoublePressAction)
                    }
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        performHeadsetAction(settings.currentHeadsetTriplePressAction)
                    }
                    true
                }
                else -> false
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            // A MediaController sends items across the IPC boundary with
            // MediaItem.toBundleIncludeLocalConfiguration(), so an explicitly-set uri DOES survive
            // and must be honoured — only fall back to requestMetadata.mediaUri when the item has
            // no uri of its own.
            //
            // This used to overwrite unconditionally. Harmless for the normal load path (both URIs
            // are the same file), but it silently broke corrupt-file HEAD recovery:
            // PlayerController.tryRecoverFromCorruptFile replaces the item with a
            // SkipHeadDataSource.wrapUri()'d uri (`?voyageSkipBytes=N`) to hide the damaged head
            // from the extractor, while requestMetadata.mediaUri still points at the original
            // unskipped path. Overwriting it here fed the extractor byte 0 again on every retry,
            // so all 14 escalating attempts (1s..30s) re-read the same corrupt head and the book
            // ended at "Couldn't skip past the damaged section" instead of playing.
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
            }
            return Futures.immediateFuture(resolved)
        }
    }
}
