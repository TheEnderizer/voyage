package com.betteraudio.playback

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.util.AppLog
import com.betteraudio.widget.WidgetUpdater
import com.betteraudio.widget.model.WidgetSnapshot
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
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var repository: AudiobookRepository
    @Inject lateinit var seriesRepository: SeriesRepository
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var widgetUpdater: WidgetUpdater
    @Inject lateinit var jumpRestoreStore: JumpRestoreStore
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
    // actually pauses playback.
    private var sleepMode: String = SLEEP_MODE_OFF
    private var sleepEndAtElapsedMs: Long = 0L      // COUNTDOWN target, SystemClock.elapsedRealtime()
    private var sleepTargetBookPositionMs: Long = 0L  // END_OF_CHAPTER target, absolute book-level ms
    private var sleepResetMs: Long = 15 * 60_000L   // duration re-armed on shake-to-extend
    private var sleepRemainingMsForWidget: Long = 0L
    private var sleepTickJob: Job? = null
    private var sleepFadeActive: Boolean = false
    private var sleepPreFadeVolume: Float = 1f
    private var sleepGraceJob: Job? = null
    private var sleepWakeLock: android.os.PowerManager.WakeLock? = null
    private val shakeDetector: ShakeDetector by lazy { ShakeDetector(this) { onShakeDetected() } }

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
    // Guards the schedule from re-arming every time playback resumes within one listening
    // session; reset when a fresh set of media items is loaded (a genuinely new session).
    private var scheduleArmedThisSession = false

    // ── Headset multi-press mapping ──────────────────────────────────────────────
    private val headsetGestureMapper: HeadsetGestureMapper by lazy {
        HeadsetGestureMapper(serviceScope, settings) { action -> performHeadsetAction(action) }
    }

    // ── Bluetooth/headphone auto-resume ──────────────────────────────────────────
    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

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
        private const val SHAKE_ARM_WINDOW_MS = 30_000L         // start listening this close to firing
        private const val SHAKE_GRACE_WINDOW_MS = 30_000L       // keep listening this long after firing
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("Service", "onCreate — building player (skipSilence min=${settings.currentSkipSilenceMinMs}ms keep=${settings.currentSkipSilencePaddingMs}ms thr=${settings.currentSkipSilenceThreshold})")
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

        // Data source that understands SkipHeadDataSource.wrapUri()-marked URIs, letting the
        // controller retry a corrupt-headed file with its damaged leading bytes hidden.
        val skipHeadFactory = DataSource.Factory {
            SkipHeadDataSource(DefaultDataSource.Factory(this).createDataSource())
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(LargeFileMediaSourceFactory(skipHeadFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = player

        // Assign a known audio session up front so the LoudnessEnhancer can attach
        // immediately and reliably, rather than depending only on the callback (which can
        // fire late, with an unset id, or be missed entirely on some devices).
        val sid = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        } catch (_: Exception) { AudioManager.ERROR }
        if (sid != AudioManager.ERROR && sid != C.AUDIO_SESSION_ID_UNSET) {
            try { player.setAudioSessionId(sid) } catch (e: Exception) {
                Log.e("PlaybackService", "setAudioSessionId failed", e)
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
                if (isPlaying) { startPositionSaver(); maybeAutoArmScheduledSleep() }
                else { stopPositionSaver(); saveCurrentPosition() }
                pushWidgetState()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    scheduleArmedThisSession = false
                }
                // Persist the file change immediately, not just on the next periodic tick — the
                // plan for widening that tick's interval (see startPositionSaver) assumed pause/
                // stop/file-transition already flushed, but transition never did. Without this, a
                // kill shortly after auto-advancing to the next file could resume from a stale
                // (previous) file instead of just an old position within the right one. Calls
                // repository.updatePosition directly rather than saveCurrentPosition(), which
                // skips a positionMs <= 0 read — exactly the position a fresh transition starts at.
                mediaItem?.let { item ->
                    val bookId = item.mediaMetadata.extras?.getLong("bookId", -1L) ?: -1L
                    val fileId = item.mediaId.toLongOrNull()
                    if (bookId != -1L && fileId != null) {
                        val posMs = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L
                        serviceScope.launch(Dispatchers.IO) { repository.updatePosition(bookId, fileId, posMs) }
                    }
                }
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

        registerBtAutoResume()
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
                        if (sleepMode != SLEEP_MODE_OFF) widgetUpdater.requestRender()
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
            Log.e("PlaybackService", "LoudnessEnhancer attach failed for session $audioSessionId", e)
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
            Log.e("PlaybackService", "Equalizer attach failed for session $audioSessionId", e)
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
            Log.e("PlaybackService", "applyEqBands failed", e)
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
        AppLog.i("Service", "applySkipSilence=$enabled")
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
                Log.e("PlaybackService", "applyBoost($boostMb) failed", e)
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
            AppLog.i("Player", "saveCurrentPosition book=$bookId file=$fileId pos=${positionMs}ms")
            repository.updatePosition(bookId, fileId, positionMs)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player
        intent?.action?.let { if (it.startsWith("com.betteraudio")) AppLog.i("Widget", "action=$it loaded=${player?.mediaItemCount ?: 0}") }
        when (intent?.action) {
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
                if (sleepMode != SLEEP_MODE_OFF) {
                    setSleepTimer(SLEEP_MODE_OFF, 0L, 0L)
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
                    setSleepTimer(SLEEP_MODE_COUNTDOWN, durationMs, 0L)
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

    // ── Bluetooth/headphone auto-resume ──────────────────────────────────────────
    // AudioDeviceCallback (no runtime permission needed) rather than BluetoothDevice broadcasts —
    // ACTION_ACL_CONNECTED fires for ANY paired device including watches and car head units doing
    // phonebook sync, which would false-trigger a resume; this only fires for devices Android
    // itself considers audio sinks.
    private fun registerBtAutoResume() {
        if (audioDeviceCallback != null) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val callback = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>) {
                if (!settings.currentBtAutoResumeEnabled) return
                if (addedDevices.any { isAudioSinkDevice(it) }) maybeAutoResumeOnDeviceConnect()
            }
        }
        am.registerAudioDeviceCallback(callback, android.os.Handler(mainLooper))
        audioDeviceCallback = callback
    }

    private fun unregisterBtAutoResume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
    }

    private fun isAudioSinkDevice(info: android.media.AudioDeviceInfo): Boolean = when (info.type) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
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
                AppLog.i("Player", "BT/headphone connected — auto-resuming book=$bookId (paused ${elapsed / 1000}s ago)")
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
        saveCurrentPosition()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        pushWidgetState()
    }

    // ── Sleep timer engine ──────────────────────────────────────────────────────
    // Single authority for the widget's timer and the in-app player's (see the field comment).
    // COUNTDOWN counts real wall-clock time down from [sleepEndAtElapsedMs] regardless of
    // play/pause state (matches the pre-existing behaviour); END_OF_CHAPTER instead compares the
    // current book-level position against a fixed target and fires when playback reaches it.

    private fun setSleepTimer(mode: String, durationMs: Long, targetBookPositionMs: Long) {
        sleepTickJob?.cancel()
        sleepGraceJob?.cancel()
        sleepGraceJob = null
        shakeDetector.stop()
        releaseSleepWakeLock()
        if (sleepFadeActive) {
            exoPlayer?.volume = sleepPreFadeVolume
            sleepFadeActive = false
        }

        sleepMode = mode
        if (durationMs > 0L) sleepResetMs = durationMs
        when (mode) {
            SLEEP_MODE_COUNTDOWN -> {
                sleepEndAtElapsedMs = android.os.SystemClock.elapsedRealtime() + durationMs
                startSleepTick()
            }
            SLEEP_MODE_END_OF_CHAPTER -> {
                sleepTargetBookPositionMs = targetBookPositionMs
                startSleepTick()
            }
            else -> {
                sleepRemainingMsForWidget = 0L
            }
        }
        pushWidgetState()
        broadcastSleepState()
    }

    /** Pushes the current sleep-timer mode + remaining time to every connected controller (the
     *  in-app player). Needed because a shake-extend or a scheduled auto-arm happens entirely
     *  service-side — a controller has no way to notice either just by polling its own state. */
    private fun broadcastSleepState() {
        val session = mediaSession ?: return
        session.broadcastCustomCommand(
            SessionCommand(CMD_SLEEP_STATE_CHANGED, Bundle.EMPTY),
            Bundle().apply {
                putString(KEY_SLEEP_MODE, sleepMode)
                putLong(KEY_SLEEP_REMAINING_MS, sleepRemainingMsForWidget)
            }
        )
    }

    private fun startSleepTick() {
        sleepTickJob = serviceScope.launch {
            while (isActive && sleepMode != SLEEP_MODE_OFF) {
                val remaining = computeSleepRemainingMs()
                sleepRemainingMsForWidget = remaining.coerceAtLeast(0L)
                maybeArmShakeListening(remaining)
                val fadeMs = settings.currentSleepFadeSeconds * 1_000L
                if (remaining in 1..fadeMs && exoPlayer?.isPlaying == true) {
                    applySleepFade(remaining, fadeMs)
                }
                if (remaining <= 0L) {
                    fireSleepTimer()
                    return@launch
                }
                // Screen off = no one can see the widget. Skip the render/push entirely — the
                // screen-on receiver forces one immediate correct re-render the moment it matters
                // again, so nothing is ever left stale, only un-rendered while unobserved.
                if (screenOn) {
                    // COUNTDOWN mode needs no new data each tick — the widget recomputes remaining
                    // time at paint time from the already-persisted sleepEndAtElapsedMs deadline, so
                    // a cheap tickCountdown() (re-renders only countdown-showing widgets) suffices.
                    // END_OF_CHAPTER has no fixed deadline (remaining shrinks with playback position,
                    // not just wall-clock time), so it still needs a state push each tick — via the
                    // metadata-cached variant, since the book/series behind it essentially never
                    // change between two ticks one second apart.
                    if (sleepMode == SLEEP_MODE_END_OF_CHAPTER) pushWidgetStateForSleepTick() else widgetUpdater.tickCountdown()
                }
                broadcastSleepState()
                delay(1_000)
            }
        }
    }

    private fun computeSleepRemainingMs(): Long = when (sleepMode) {
        SLEEP_MODE_COUNTDOWN -> sleepEndAtElapsedMs - android.os.SystemClock.elapsedRealtime()
        SLEEP_MODE_END_OF_CHAPTER -> {
            val player = exoPlayer
            if (player == null) 0L else {
                val currentAbs = bookPositionMsFor(player.currentMediaItemIndex, player.currentPosition)
                sleepTargetBookPositionMs - currentAbs
            }
        }
        else -> 0L
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
                "History",
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

    /** Ramps volume down to silence as [remainingMs] approaches 0 within [fadeMs]. Captures the
     *  pre-fade volume once so it can be restored exactly (fade or cancel). Skips starting a NEW
     *  fade while the volume already reads reduced (< 99%) — most likely a transient audio-focus
     *  duck in progress, which this shouldn't fight or stomp on completion. */
    private fun applySleepFade(remainingMs: Long, fadeMs: Long) {
        val player = exoPlayer ?: return
        if (!sleepFadeActive) {
            if (player.volume < 0.99f) return
            sleepPreFadeVolume = player.volume
            sleepFadeActive = true
        }
        val fraction = (remainingMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
        player.volume = sleepPreFadeVolume * fraction
    }

    private fun fireSleepTimer() {
        exoPlayer?.pause()
        if (sleepFadeActive) {
            exoPlayer?.volume = sleepPreFadeVolume
            sleepFadeActive = false
        }
        sleepMode = SLEEP_MODE_OFF
        sleepRemainingMsForWidget = 0L
        AppLog.i("Player", "sleep timer fired, pausing")
        pushWidgetState()
        broadcastSleepState()
        if (settings.currentSleepShakeEnabled) armShakeGraceWindow()
    }

    private fun maybeArmShakeListening(remainingMs: Long) {
        if (!settings.currentSleepShakeEnabled) return
        if (remainingMs in 1..SHAKE_ARM_WINDOW_MS) shakeDetector.start()
    }

    /** After firing, keep listening for a shake a little longer (with a short wakelock so the
     *  sensor keeps delivering with the screen off) so "shake to resume" works right after the
     *  pause, not only in the countdown's final seconds. Best-effort past this window — no
     *  wakelock beyond it, so delivery isn't guaranteed with the screen off. */
    private fun armShakeGraceWindow() {
        shakeDetector.start()
        acquireSleepWakeLock()
        sleepGraceJob = serviceScope.launch {
            delay(SHAKE_GRACE_WINDOW_MS)
            shakeDetector.stop()
            releaseSleepWakeLock()
            sleepGraceJob = null
        }
    }

    private fun onShakeDetected() {
        AppLog.i("Player", "shake detected — extending sleep timer by ${sleepResetMs / 60_000}min")
        sleepGraceJob?.cancel()
        sleepGraceJob = null
        releaseSleepWakeLock()
        if (exoPlayer?.isPlaying == false) exoPlayer?.play()
        setSleepTimer(SLEEP_MODE_COUNTDOWN, sleepResetMs, 0L)
    }

    private fun acquireSleepWakeLock() {
        if (sleepWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return
        sleepWakeLock = try {
            pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Voyage:SleepShakeGrace").apply {
                setReferenceCounted(false)
                acquire(SHAKE_GRACE_WINDOW_MS)
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "sleep wakelock acquire failed", e)
            null
        }
    }

    private fun releaseSleepWakeLock() {
        sleepWakeLock?.let { if (it.isHeld) it.release() }
        sleepWakeLock = null
    }

    /** Auto-arms the default sleep timer once per listening session when playback starts inside
     *  the configured schedule window (handles a window that wraps past midnight). */
    private fun maybeAutoArmScheduledSleep() {
        if (!settings.currentSleepScheduleEnabled) return
        if (sleepMode != SLEEP_MODE_OFF) return
        if (scheduleArmedThisSession) return
        val cal = java.util.Calendar.getInstance()
        val nowMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val start = settings.currentSleepScheduleStartMinutes
        val end = settings.currentSleepScheduleEndMinutes
        val inWindow = if (start <= end) nowMinutes in start until end else (nowMinutes >= start || nowMinutes < end)
        if (!inWindow) return
        scheduleArmedThisSession = true
        AppLog.i("Player", "auto-arming scheduled sleep timer (${settings.currentSleepScheduleDefaultMinutes}min)")
        setSleepTimer(SLEEP_MODE_COUNTDOWN, settings.currentSleepScheduleDefaultMinutes * 60_000L, 0L)
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
            val startIndex = files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0)
            val rawPos = if (progress?.isCompleted == true) 0L else (progress?.positionMs ?: 0L)
            // Same auto-rewind as every other resume path (PlayerViewModel.play() etc.) — a cold
            // widget tap shouldn't behave differently just because no Activity is open yet.
            val rewind = AudioCascade.autoRewindMs(settings, progress?.lastPausedAt ?: 0L)
            val startPos = if (rawPos >= rewind) rawPos - rewind else rawPos
            AppLog.i("Player", "widget loadLastPlayedAndPlay book=$bookId" +
                " dbFile=${progress?.currentFileId} dbPos=${progress?.positionMs}ms isCompleted=${progress?.isCompleted}" +
                " → startIdx=$startIndex startPos=${startPos}ms")

            val items = files.map { file ->
                MediaItem.Builder()
                    .setMediaId(file.id.toString())
                    // Uri.fromFile percent-encodes; "file://$path" breaks on '%' or '#' in a name.
                    .setUri(Uri.fromFile(java.io.File(file.filePath)))
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(Uri.fromFile(java.io.File(file.filePath)))
                            .build()
                    )
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setAlbumTitle(book.title)
                            .setArtist(book.author)
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
            pushWidgetState()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents — save position directly from ExoPlayer (not through the
        // MediaController proxy, which can transiently report 0 during reconnect).
        val posMs = exoPlayer?.currentPosition ?: 0L
        if (posMs > 0L) {
            val item = exoPlayer?.currentMediaItem
            val bookId = item?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
            val fileId = item?.mediaId?.toLongOrNull()
            if (bookId != -1L && fileId != null) {
                runBlocking { repository.updatePosition(bookId, fileId, posMs) }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        widgetUpdater.pushPaused()
        stopPositionSaver()
        saveCurrentPosition()
        sleepTickJob?.cancel()
        sleepGraceJob?.cancel()
        shakeDetector.stop()
        releaseSleepWakeLock()
        headsetGestureMapper.cancel()
        unregisterBtAutoResume()
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

    /** Builds a WidgetSnapshot from live player state and hands it to WidgetUpdater, which
     *  persists it (so a cold-started widget always has real data) and renders every placed
     *  widget directly — no exported broadcast, so this can't be throttled or spoofed. */
    fun pushWidgetState() {
        val player = mediaSession?.player ?: return
        val meta = player.currentMediaItem?.mediaMetadata
        val isPlaying = player.isPlaying
        val title = meta?.albumTitle?.toString() ?: ""
        val author = meta?.artist?.toString() ?: ""
        val chapterTitle = meta?.title?.toString() ?: ""
        val speed = player.playbackParameters.speed
        val boostDbVal = boostMb / 100
        val sleepEnd = if (sleepMode == SLEEP_MODE_COUNTDOWN) sleepEndAtElapsedMs else 0L
        val sleepRemaining = sleepRemainingMsForWidget
        val bookId = meta?.extras?.getLong("bookId", -1L) ?: -1L
        val positionMs = bookPositionMsFor(player.currentMediaItemIndex, player.currentPosition)
        val chapterPositionMs = player.currentPosition
        val chapterDurationMs = player.duration.coerceAtLeast(0L)

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
                    chapterTitle = chapterTitle,
                    seriesName = seriesName,
                    isPlaying = isPlaying,
                    speed = speed,
                    boostDb = boostDbVal,
                    bookCoverPath = bookCoverPath,
                    seriesCoverPath = seriesCoverPath,
                    positionMs = positionMs,
                    bookDurationMs = bookDurationMs,
                    chapterPositionMs = chapterPositionMs,
                    chapterDurationMs = chapterDurationMs,
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
        val chapterTitle = meta?.title?.toString() ?: ""
        val speed = player.playbackParameters.speed
        val boostDbVal = boostMb / 100
        val sleepRemaining = sleepRemainingMsForWidget
        val bookId = meta?.extras?.getLong("bookId", -1L) ?: -1L
        val positionMs = bookPositionMsFor(player.currentMediaItemIndex, player.currentPosition)
        val chapterPositionMs = player.currentPosition
        val chapterDurationMs = player.duration.coerceAtLeast(0L)

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
                    chapterTitle = chapterTitle,
                    seriesName = eocMetaSeriesName,
                    isPlaying = isPlaying,
                    speed = speed,
                    boostDb = boostDbVal,
                    bookCoverPath = eocMetaBookCoverPath,
                    seriesCoverPath = eocMetaSeriesCoverPath,
                    positionMs = positionMs,
                    bookDurationMs = eocMetaBookDurationMs,
                    chapterPositionMs = chapterPositionMs,
                    chapterDurationMs = chapterDurationMs,
                    sleepEndAtElapsedMs = 0L,
                    sleepRemainingMs = sleepRemaining,
                    writtenAtElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                )
            )
        }
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
                    setSleepTimer(
                        mode = args.getString(KEY_SLEEP_MODE, SLEEP_MODE_OFF),
                        durationMs = args.getLong(KEY_SLEEP_DURATION_MS, 0L),
                        targetBookPositionMs = args.getLong(KEY_SLEEP_TARGET_POSITION_MS, 0L)
                    )
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                CMD_SET_CHANNEL_MIX -> {
                    applyChannelMix(args.getFloat(KEY_AUDIO_BALANCE, 0f), args.getBoolean(KEY_MONO_AUDIO, false))
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false

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
            val resolved = mediaItems.map { item ->
                item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
            }
            return Futures.immediateFuture(resolved)
        }
    }
}
