package com.betteraudio.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import org.json.JSONArray
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
import com.betteraudio.util.AppLog
import com.betteraudio.widget.WidgetRender
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

    // Saves the current playback position to the DB every 2 s while playing. Runs on
    // serviceScope (Main dispatcher) so ExoPlayer's currentPosition is safe to read.
    private var positionSaverJob: Job? = null

    // Silence-skipping audio processor — lives in the decode→sink chain (separate from the
    // session-id audio effects above). Toggled per book; its tuning follows the settings live.
    private var silenceProcessor: LiveSilenceSkippingProcessor? = null

    // Custom-widget sleep timer (independent of the in-app AudioSettingsSheet's PlayerController
    // timer): owned here so a widget PendingIntent → startService can drive it without depending
    // on a MediaController round-trip. Both ultimately just pause the same ExoPlayer.
    private var widgetSleepTimerJob: Job? = null
    private var widgetSleepTimerRemainingMs: Long = 0L

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

        private const val SPEED_STEP = 0.1f
        private const val BOOST_STEP_MB = 300 // 3 dB
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
                        DefaultAudioSink.DefaultAudioProcessorChain(silence, SonicAudioProcessor())
                    )
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
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
                if (isPlaying) startPositionSaver()
                else { stopPositionSaver(); saveCurrentPosition() }
                broadcastWidgetUpdate()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                broadcastWidgetUpdate()
            }
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
        serviceScope.launch(Dispatchers.IO) {
            AppLog.i("Player", "saveCurrentPosition book=$bookId file=$fileId pos=${positionMs}ms")
            repository.updatePosition(bookId, fileId, positionMs)
        }
    }

    private fun startPositionSaver() {
        if (positionSaverJob?.isActive == true) return
        positionSaverJob = serviceScope.launch {
            while (isActive) {
                delay(5_000)
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
                    broadcastWidgetUpdate()
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
                broadcastWidgetUpdate()
            }
            ACTION_CHAPTER_BACK -> exoPlayer?.let {
                if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0L)
                broadcastWidgetUpdate()
            }
            ACTION_SPEED_UP -> player?.let {
                val newSpeed = (it.playbackParameters.speed + SPEED_STEP).coerceIn(0.5f, 3.0f)
                it.setPlaybackSpeed(newSpeed)
                broadcastWidgetUpdate()
            }
            ACTION_SPEED_DOWN -> player?.let {
                val newSpeed = (it.playbackParameters.speed - SPEED_STEP).coerceIn(0.5f, 3.0f)
                it.setPlaybackSpeed(newSpeed)
                broadcastWidgetUpdate()
            }
            ACTION_BOOST_UP -> {
                applyBoost((boostMb + BOOST_STEP_MB).coerceIn(0, 2400))
                broadcastWidgetUpdate()
            }
            ACTION_BOOST_DOWN -> {
                applyBoost((boostMb - BOOST_STEP_MB).coerceIn(0, 2400))
                broadcastWidgetUpdate()
            }
            ACTION_QUICK_BOOKMARK -> addQuickBookmark()
            ACTION_CLOSE_BOOK -> closeBook()
            ACTION_SLEEP_TIMER_TOGGLE -> {
                val durationMs = intent.getLongExtra(EXTRA_SLEEP_DURATION_MS, 15 * 60_000L)
                toggleWidgetSleepTimer(durationMs)
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
        broadcastWidgetUpdate()
    }

    private fun toggleWidgetSleepTimer(durationMs: Long) {
        if (widgetSleepTimerJob?.isActive == true) {
            widgetSleepTimerJob?.cancel()
            widgetSleepTimerJob = null
            widgetSleepTimerRemainingMs = 0L
            broadcastWidgetUpdate()
            return
        }
        widgetSleepTimerRemainingMs = durationMs
        broadcastWidgetUpdate()
        widgetSleepTimerJob = serviceScope.launch {
            var remaining = durationMs
            while (remaining > 0 && isActive) {
                delay(1_000)
                remaining -= 1_000
                widgetSleepTimerRemainingMs = remaining.coerceAtLeast(0L)
                broadcastWidgetUpdate()
            }
            widgetSleepTimerRemainingMs = 0L
            exoPlayer?.pause()
            broadcastWidgetUpdate()
        }
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
            val book = repository.getBookById(bookId).first() ?: return@launch
            val files = repository.getAudioFilesOnce(bookId)
                .sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            if (files.isEmpty()) return@launch
            val progress = repository.getProgressForBookOnce(bookId)
            val startIndex = files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0)
            val startPos = if (progress?.isCompleted == true) 0L else (progress?.positionMs ?: 0L)
            val speed = progress?.playbackSpeed ?: settings.currentDefaultSpeed
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
                                putLong("groupId", -1L)
                            })
                            .build()
                    )
                    .build()
            }
            player.setMediaItems(items, startIndex, startPos)
            player.setPlaybackSpeed(speed)
            player.prepare()
            player.play()
            // Restore this book's saved boost/EQ (mb = dB * 100) + skip-silence preference.
            applyBoost((progress?.boostDb ?: 0) * 100)
            applyEq(progress?.eqBandsJson)
            applySkipSilence(book.skipSilenceEnabled)
            repository.touchLastPlayed(bookId)
            broadcastWidgetUpdate()
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
        stopPositionSaver()
        saveCurrentPosition()
        widgetSleepTimerJob?.cancel()
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

    fun broadcastWidgetUpdate() {
        val player = mediaSession?.player ?: return
        val meta = player.currentMediaItem?.mediaMetadata
        val isPlaying = player.isPlaying
        val title = meta?.albumTitle?.toString() ?: ""
        val author = meta?.artist?.toString() ?: ""
        val coverArtUri = meta?.artworkUri?.toString() ?: ""
        val chapterTitle = meta?.title?.toString() ?: ""
        val speed = player.playbackParameters.speed
        val boostDb = boostMb / 100
        val sleepRemaining = widgetSleepTimerRemainingMs
        val bookId = meta?.extras?.getLong("bookId", -1L) ?: -1L

        serviceScope.launch(Dispatchers.IO) {
            var seriesName = ""
            var bookCoverPath: String? = null
            var seriesCoverPath: String? = null
            if (bookId != -1L) {
                val book = repository.getBookById(bookId).first()
                bookCoverPath = book?.coverArtPath
                seriesName = book?.seriesName ?: ""
                val seriesId = book?.seriesId
                if (seriesId != null) {
                    seriesCoverPath = seriesRepository.getSeriesOnce(seriesId)?.coverArtPath
                }
            }
            val intent = Intent(WidgetRender.ACTION_UPDATE_WIDGET).apply {
                setPackage(packageName)
                putExtra(WidgetRender.EXTRA_IS_PLAYING, isPlaying)
                putExtra(WidgetRender.EXTRA_BOOK_TITLE, title)
                putExtra(WidgetRender.EXTRA_BOOK_AUTHOR, author)
                putExtra(WidgetRender.EXTRA_COVER_ART_URI, coverArtUri)
                putExtra(WidgetRender.EXTRA_CHAPTER_TITLE, chapterTitle)
                putExtra(WidgetRender.EXTRA_SERIES_NAME, seriesName)
                putExtra(WidgetRender.EXTRA_BOOK_COVER_PATH, bookCoverPath ?: "")
                putExtra(WidgetRender.EXTRA_SERIES_COVER_PATH, seriesCoverPath ?: "")
                putExtra(WidgetRender.EXTRA_SPEED, speed)
                putExtra(WidgetRender.EXTRA_BOOST_DB, boostDb)
                putExtra(WidgetRender.EXTRA_SLEEP_REMAINING_MS, sleepRemaining)
            }
            sendBroadcast(intent)
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
            if (event.action != KeyEvent.ACTION_DOWN) return false
            return when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    session.player.seekTo(session.player.currentPosition + settings.currentSkipForwardMs)
                    true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    session.player.seekTo(maxOf(0L, session.player.currentPosition - settings.currentSkipBackMs))
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
