package com.betteraudio.playback

import android.content.ComponentName
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.widget.WidgetRender
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var repository: AudiobookRepository
    @Inject lateinit var settings: SettingsStore

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Volume amplifier — must live on the real ExoPlayer's audio session, not on a
    // remote MediaController (which never receives onAudioSessionIdChanged).
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var boostMb = 0  // millibels; 100 mb = 1 dB

    companion object {
        const val ACTION_TOGGLE_PLAY_PAUSE = "com.betteraudio.action.WIDGET_PLAY_PAUSE"
        const val ACTION_SKIP_FORWARD      = "com.betteraudio.action.WIDGET_SKIP_FORWARD"
        const val ACTION_SKIP_BACK         = "com.betteraudio.action.WIDGET_SKIP_BACK"

        const val CMD_SET_BOOST = "com.betteraudio.command.SET_BOOST"
        const val KEY_BOOST_MB  = "boost_mb"
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachLoudnessEnhancer(audioSessionId)
            }
        })

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .build()
    }

    private fun attachLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        loudnessEnhancer?.release()
        loudnessEnhancer = try {
            LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(boostMb)
                enabled = boostMb > 0
            }
        } catch (_: Exception) { null }
    }

    private fun applyBoost(mb: Int) {
        boostMb = mb.coerceIn(0, 2400)
        loudnessEnhancer?.let {
            try {
                it.setTargetGain(boostMb)
                it.enabled = boostMb > 0
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = mediaSession?.player
        when (intent?.action) {
            ACTION_TOGGLE_PLAY_PAUSE -> player?.let {
                if (it.isPlaying) it.pause() else it.play()
                broadcastWidgetUpdate()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Called when user swipes the app from recents — save position synchronously
        val player = mediaSession?.player
        if (player != null) {
            val bookId = player.currentMediaItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
            val fileId = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (bookId != -1L && fileId != null) {
                runBlocking { repository.updatePosition(bookId, fileId, player.currentPosition) }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
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
            if (customCommand.customAction == CMD_SET_BOOST) {
                applyBoost(args.getInt(KEY_BOOST_MB, 0))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
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
