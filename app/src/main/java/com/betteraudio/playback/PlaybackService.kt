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
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.betteraudio.data.repository.AudiobookRepository
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var repository: AudiobookRepository
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
    // session-id audio effects above). Configured once from settings; toggled per book.
    private var silenceProcessor: SilenceSkippingAudioProcessor? = null

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.betteraudio.action.WIDGET_PLAY_PAUSE"
        const val ACTION_SKIP_FORWARD      = "com.betteraudio.action.WIDGET_SKIP_FORWARD"
        const val ACTION_SKIP_BACK         = "com.betteraudio.action.WIDGET_SKIP_BACK"

        const val CMD_SET_BOOST      = "com.betteraudio.command.SET_BOOST"
        const val KEY_BOOST_MB       = "boost_mb"
        const val CMD_SET_EQ         = "com.betteraudio.command.SET_EQ"
        const val KEY_EQ_BANDS_JSON  = "eq_bands_json"  // "" = flat/bypass
        const val CMD_SET_SKIP_SILENCE = "com.betteraudio.command.SET_SKIP_SILENCE"
        const val KEY_SKIP_SILENCE     = "skip_silence_enabled"
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i("Service", "onCreate — building player (skipSilence min=${settings.currentSkipSilenceMinMs}ms thr=${settings.currentSkipSilenceThreshold})")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        // Build a silence-skipping processor from the saved config. It sits in the audio
        // pipeline ahead of Sonic (which handles playback speed/pitch), starts disabled, and is
        // toggled per book via CMD_SET_SKIP_SILENCE. Sensitivity/min-length apply at build time.
        val silence = SilenceSkippingAudioProcessor(
            /* minimumSilenceDurationUs = */ settings.currentSkipSilenceMinMs * 1_000L,
            /* silenceRetentionRatio    = */ 0.2f,
            /* maxSilenceToKeepDurationUs= */ (settings.currentSkipSilenceMinMs * 1_000L).coerceAtMost(100_000L),
            /* minVolumeToKeepPercentageWhenMuting = */ 0,
            /* silenceThresholdLevel     = */ settings.currentSkipSilenceThreshold.toShort()
        )
        silence.setEnabled(false)
        silenceProcessor = silence

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
     * Toggle silence skipping for the current book. The processor's [isActive] only changes on a
     * flush, so after toggling we nudge a seek to the current position to force the audio sink to
     * reconfigure immediately. No-op if the state is unchanged.
     */
    private fun applySkipSilence(enabled: Boolean) {
        val proc = silenceProcessor ?: return
        if (skipSilenceEnabled == enabled) return
        skipSilenceEnabled = enabled
        AppLog.i("Service", "applySkipSilence=$enabled")
        proc.setEnabled(enabled)
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
            repository.updatePosition(bookId, fileId, positionMs)
        }
    }

    private fun startPositionSaver() {
        if (positionSaverJob?.isActive == true) return
        positionSaverJob = serviceScope.launch {
            while (isActive) {
                delay(2_000)
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
        }
        return super.onStartCommand(intent, flags, startId)
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

            val items = files.map { file ->
                MediaItem.Builder()
                    .setMediaId(file.id.toString())
                    .setUri(Uri.parse("file://${file.filePath}"))
                    .setRequestMetadata(
                        MediaItem.RequestMetadata.Builder()
                            .setMediaUri(Uri.parse("file://${file.filePath}"))
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
        val intent = Intent(WidgetRender.ACTION_UPDATE_WIDGET).apply {
            setPackage(packageName)
            putExtra(WidgetRender.EXTRA_IS_PLAYING, player.isPlaying)
            putExtra(WidgetRender.EXTRA_BOOK_TITLE, meta?.albumTitle?.toString() ?: "")
            putExtra(WidgetRender.EXTRA_BOOK_AUTHOR, meta?.artist?.toString() ?: "")
            putExtra(WidgetRender.EXTRA_COVER_ART_URI, meta?.artworkUri?.toString() ?: "")
        }
        sendBroadcast(intent)
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
