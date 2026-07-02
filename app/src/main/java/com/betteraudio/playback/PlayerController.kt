package com.betteraudio.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.util.AppLog
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackState(
    val isPlaying: Boolean = false,
    val bookId: Long = -1L,
    val groupId: Long = -1L,         // -1 if not playing a group
    val groupName: String = "",
    val bookTitle: String = "",
    val author: String = "",
    val coverArtUri: String? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    // Book-level (cumulative) position across all files
    val bookPositionMs: Long = 0L,
    val bookTotalDurationMs: Long = 0L,
    // Sleep timer
    val sleepTimerRemainingMs: Long = 0L
)

@UnstableApi
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val repository: AudiobookRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // Ignore sub-5s play blips so play/pause taps don't spam the history.
        private const val MIN_SESSION_MS = 5_000L
        // Pause longer than this splits the listening session into a new one.
        private const val PAUSE_SESSION_SPLIT_MS = 20 * 60 * 1_000L
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var controller: MediaController? = null
    private var currentBookId = -1L
    private var currentGroupId = -1L
    private var currentGroupName = ""

    // Series-continuation context: when the playing book belongs to an active series session,
    // the next book auto-starts when it ends. Managed by SeriesPlayer via [playBook].
    private var currentSeriesId = -1L
    private var currentSeriesBookIds: List<Long> = emptyList()
    /** Invoked when a series member book ends, so the next book can be loaded and played. */
    var onSeriesBookEnded: ((seriesId: Long, orderedBookIds: List<Long>, endedBookId: Long) -> Unit)? = null
    /** Emits the new book id when a series auto-advances, so the open player can re-target to it. */
    val seriesAdvanced = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)

    // Cumulative start offsets per file index, computed when a book is loaded
    private var cumulativeStartsMs: List<Long> = emptyList()
    private var bookTotalDurationMs: Long = 0L

    // Sleep timer
    private var sleepTimerJob: Job? = null
    private var sleepTimerRemainingMs: Long = 0L

    // Position ticker — updates the seek bar while playback is active
    private var positionTickerJob: Job? = null

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        // Must run on Main — MediaController properties are main-thread only
        positionTickerJob = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            var tick = 0
            while (isActive) {
                delay(500)
                playerListener.triggerSync()
                // Persist progress every ~5s so a swipe-kill (no onStop) can't lose the position.
                if (++tick % 10 == 0) saveCurrentProgress()
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    // Volume boost — the LoudnessEnhancer itself lives in PlaybackService; this is just
    // the last value we sent, kept so the UI can show the current level.
    private var currentBoostMb: Int = 0  // millibels (100 mb = 1 dB)

    // ── Listening-history session tracking ───────────────────────────────────
    // A session spans the open→close window, ignoring short pauses (< 20 min). Only
    // app-close or a pause longer than PAUSE_SESSION_SPLIT_MS ends a session.
    // All session state is touched only on the main thread (playerListener callbacks +
    // Dispatchers.Main timer), so no synchronisation is needed.
    private var sessionOpen = false
    private var sessionBookId = -1L
    private var sessionStartMs = 0L
    private var sessionStartBookPos = 0L
    private var sessionStartChapterIndex = -1
    private var sessionStartChapterName = ""
    private var sessionStartPosInChapter = 0L
    private var sessionAccumulatedMs = 0L   // actual play time, pauses excluded
    private var sessionSegmentStartMs = 0L  // wall-clock when current segment started; 0 = not playing
    private var pauseTimerJob: Job? = null
    // (absStartMs, index, name) per chapter for the loaded single book; empty for groups.
    private var chapterBoundaries: List<Triple<Long, Int, String>> = emptyList()

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
            } catch (e: Exception) {
                Log.e("PlayerController", "Failed to connect to MediaSession", e)
            }
        }, { it.run() })
    }

    fun disconnect() {
        closeHistorySession()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    // ── History session helpers ──────────────────────────────────────────────
    private fun bookPosFromState(): Long {
        val st = _playbackState.value
        return if (st.bookTotalDurationMs > 0) st.bookPositionMs else st.currentPositionMs
    }

    /** index, name, position-within-chapter for a book-level position. */
    private fun chapterAt(bookPos: Long): Triple<Int, String, Long> {
        val b = chapterBoundaries.lastOrNull { it.first <= bookPos } ?: return Triple(-1, "", bookPos)
        return Triple(b.second, b.third, bookPos - b.first)
    }

    private fun loadChapterBoundaries(bookId: Long, files: List<AudioFile>) {
        scope.launch {
            val chapters = repository.getChaptersForBookOnce(bookId).sortedBy { it.orderIndex }
            val cum = HashMap<Long, Long>(files.size)
            var t = 0L
            files.forEach { f -> cum[f.id] = t; t += f.durationMs }
            chapterBoundaries = chapters
                .mapIndexed { i, c -> Triple((cum[c.fileId] ?: 0L) + c.startInFileMs, i, c.title) }
                .sortedBy { it.first }
        }
    }

    private fun openHistorySession() {
        val bid = _playbackState.value.bookId.takeIf { it != -1L }
            ?: currentBookId.takeIf { it != -1L } ?: return
        if (sessionOpen) closeHistorySession()   // flush previous session (different book)
        val pos = bookPosFromState()
        val (ci, cn, pic) = chapterAt(pos)
        sessionOpen = true
        sessionBookId = bid
        sessionStartMs = System.currentTimeMillis()
        sessionStartBookPos = pos
        sessionStartChapterIndex = ci
        sessionStartChapterName = cn
        sessionStartPosInChapter = pic
        sessionAccumulatedMs = 0L
        sessionSegmentStartMs = System.currentTimeMillis()
    }

    private fun closeHistorySession() {
        pauseTimerJob?.cancel()
        pauseTimerJob = null
        if (!sessionOpen) return
        sessionOpen = false
        // Flush any in-progress playing segment (e.g., force-close while still playing)
        if (sessionSegmentStartMs > 0L) {
            sessionAccumulatedMs += System.currentTimeMillis() - sessionSegmentStartMs
            sessionSegmentStartMs = 0L
        }
        val bid = sessionBookId
        val listened = sessionAccumulatedMs
        if (bid == -1L || listened < MIN_SESSION_MS) return
        val pos = bookPosFromState()
        val (ci, cn, pic) = chapterAt(pos)
        val endMs = System.currentTimeMillis()
        val session = ListeningSession(
            bookId = bid,
            startMs = sessionStartMs,
            endMs = endMs,
            startChapterIndex = sessionStartChapterIndex,
            startChapterName = sessionStartChapterName,
            endChapterIndex = ci,
            endChapterName = cn,
            startPositionInChapterMs = sessionStartPosInChapter,
            endPositionInChapterMs = pic,
            endBookPositionMs = pos,
            listenedMs = listened
        )
        AppLog.i("History", "session closed book=$bid listened=${listened}ms endPos=${pos}ms ch=$ci")
        scope.launch { repository.insertListeningSession(session) }
    }

    /** [boostDb] 0–24. Forwarded to the playback service, which owns the LoudnessEnhancer. */
    fun setVolumeBoost(boostDb: Int) {
        val mb = (boostDb * 100).coerceIn(0, 2400)
        currentBoostMb = mb
        val ctrl = controller ?: return
        val args = Bundle().apply { putInt(PlaybackService.KEY_BOOST_MB, mb) }
        ctrl.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SET_BOOST, Bundle.EMPTY),
            args
        )
    }

    val currentVolumeBoostDb: Int get() = currentBoostMb / 100

    fun setEqBands(bandsJson: String?) {
        val ctrl = controller ?: return
        val args = Bundle().apply { putString(PlaybackService.KEY_EQ_BANDS_JSON, bandsJson ?: "") }
        ctrl.sendCustomCommand(SessionCommand(PlaybackService.CMD_SET_EQ, Bundle.EMPTY), args)
    }

    /** Toggle silence-skipping for the loaded book (forwarded to the playback service). */
    fun setSkipSilence(enabled: Boolean) {
        AppLog.i("Player", "setSkipSilence=$enabled book=$currentBookId")
        val ctrl = controller ?: return
        val args = Bundle().apply { putBoolean(PlaybackService.KEY_SKIP_SILENCE, enabled) }
        ctrl.sendCustomCommand(SessionCommand(PlaybackService.CMD_SET_SKIP_SILENCE, Bundle.EMPTY), args)
    }

    fun playBook(
        book: Book,
        files: List<AudioFile>,
        startFileIndex: Int = 0,
        startPositionMs: Long = 0L,
        speed: Float = settings.currentDefaultSpeed,
        // Series continuation: when set, this book is part of series [seriesId] and the next book
        // in [seriesBookIds] auto-starts when it ends. Empty = a standalone book.
        seriesId: Long = -1L,
        seriesBookIds: List<Long> = emptyList()
    ) {
        closeHistorySession()  // end any session on the previously-loaded book first
        AppLog.i("Player", "playBook id=${book.id} '${book.displayTitle}' files=${files.size} startIdx=$startFileIndex startPos=${startPositionMs}ms speed=$speed series=$seriesId")
        currentBookId = book.id
        currentGroupId = -1L
        currentGroupName = ""
        currentSeriesId = seriesId
        currentSeriesBookIds = seriesBookIds
        loadChapterBoundaries(book.id, files)
        buildAndPlay(
            items = files.map { file ->
                buildMediaItem(
                    file = file,
                    memberBookId = book.id,
                    groupId = -1L,
                    albumTitle = book.title,
                    artist = book.author,
                    artworkPath = book.coverArtPath
                )
            },
            allFiles = files,
            startIndex = startFileIndex,
            startPosition = startPositionMs,
            speed = speed
        )
    }

    /**
     * Play a joined book group — all files from all member books in order.
     * [orderedBooks] is sorted by group order. [filesPerBook] maps bookId → sorted AudioFiles.
     */
    fun playBookGroup(
        groupId: Long,
        groupName: String,
        coverArtPath: String?,
        orderedBooks: List<Book>,
        filesPerBook: Map<Long, List<AudioFile>>,
        startGlobalFileIndex: Int = 0,
        startPositionMs: Long = 0L,
        speed: Float = settings.currentDefaultSpeed
    ) {
        closeHistorySession()  // end any session on the previously-loaded book first
        chapterBoundaries = emptyList()  // per-member chapter mapping skipped for groups
        currentGroupId = groupId
        currentGroupName = groupName
        // Use the first member book's ID as the "representative" book ID
        currentBookId = orderedBooks.firstOrNull()?.id ?: -1L

        val allFiles = orderedBooks.flatMap { filesPerBook[it.id] ?: emptyList() }
        val items = orderedBooks.flatMap { book ->
            (filesPerBook[book.id] ?: emptyList()).map { file ->
                buildMediaItem(
                    file = file,
                    memberBookId = book.id,
                    groupId = groupId,
                    albumTitle = groupName,
                    artist = book.author,
                    artworkPath = coverArtPath ?: book.coverArtPath
                )
            }
        }
        buildAndPlay(items, allFiles, startGlobalFileIndex, startPositionMs, speed)
    }

    private fun buildMediaItem(
        file: AudioFile,
        memberBookId: Long,
        groupId: Long,
        albumTitle: String,
        artist: String,
        artworkPath: String?
    ) = MediaItem.Builder()
        .setMediaId(file.id.toString())
        .setUri(Uri.parse("file://${file.filePath}"))
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse("file://${file.filePath}"))
                .build()
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setAlbumTitle(albumTitle)
                .setArtist(artist)
                .setTitle(file.chapterTitle ?: file.fileName)
                .setArtworkUri(artworkPath?.let { Uri.parse("file://$it") })
                .setExtras(Bundle().apply {
                    putLong("bookId", memberBookId)
                    putLong("groupId", groupId)
                })
                .build()
        )
        .build()

    private fun buildAndPlay(
        items: List<MediaItem>,
        allFiles: List<AudioFile>,
        startIndex: Int,
        startPosition: Long,
        speed: Float
    ) {
        val cumulative = mutableListOf<Long>()
        var runningTotal = 0L
        allFiles.forEach { f -> cumulative.add(runningTotal); runningTotal += f.durationMs }
        cumulativeStartsMs = cumulative
        bookTotalDurationMs = runningTotal

        val ctrl = controller ?: return
        ctrl.setMediaItems(items, startIndex, startPosition)
        ctrl.setPlaybackSpeed(speed)
        ctrl.prepare()
        ctrl.play()
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun skipForward() {
        controller?.let { it.seekTo(it.currentPosition + settings.currentSkipForwardMs) }
    }

    fun skipBack() {
        controller?.let { it.seekTo(maxOf(0L, it.currentPosition - settings.currentSkipBackMs)) }
    }

    // Use explicit index seeks (not seekToNext/PreviousMediaItem) so the in-app "part" buttons
    // change files even though the service wraps the player to turn next/previous into time skips.
    fun nextFile() {
        controller?.let { c ->
            if (c.currentMediaItemIndex < c.mediaItemCount - 1) c.seekTo(c.currentMediaItemIndex + 1, 0L)
        }
    }
    fun prevFile() {
        controller?.let { c ->
            if (c.currentMediaItemIndex > 0) c.seekTo(c.currentMediaItemIndex - 1, 0L)
        }
    }
    fun jumpToFile(index: Int) { controller?.seekTo(index, 0L) }

    fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    val currentPositionMs: Long get() = controller?.currentPosition ?: 0L

    /** Seek to an absolute book-level position (spans multiple files). */
    fun bookSeekTo(bookPositionMs: Long) {
        val ctrl = controller ?: return
        val starts = cumulativeStartsMs
        if (starts.isEmpty()) { ctrl.seekTo(bookPositionMs); return }
        // Find the last file whose cumulative start <= bookPositionMs
        var fileIndex = starts.indexOfLast { it <= bookPositionMs }.coerceAtLeast(0)
        val offsetInFile = bookPositionMs - starts[fileIndex]
        ctrl.seekTo(fileIndex, offsetInFile)
    }

    /** Start a sleep timer; pauses playback after [durationMs]. Pass 0 to cancel. */
    fun setSleepTimer(durationMs: Long) {
        sleepTimerJob?.cancel()
        sleepTimerRemainingMs = durationMs
        if (durationMs <= 0L) {
            sleepTimerRemainingMs = 0L
            _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = 0L)
            return
        }
        sleepTimerJob = scope.launch {
            var remaining = durationMs
            while (isActive && remaining > 0L) {
                delay(1_000)
                remaining -= 1_000
                sleepTimerRemainingMs = remaining
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = remaining)
            }
            if (isActive) {
                scope.launch(Dispatchers.Main) { controller?.pause() }
                sleepTimerRemainingMs = 0L
                _playbackState.value = _playbackState.value.copy(sleepTimerRemainingMs = 0L)
            }
        }
    }

    fun saveCurrentProgress() {
        val bookId = currentBookId.takeIf { it != -1L } ?: return
        val ctrl = controller ?: return
        val fileId = ctrl.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val positionMs = ctrl.currentPosition
        // Never overwrite a good saved position with a transient 0 (reported briefly right after
        // a (re)load before the seek lands, or between media-item transitions).
        if (positionMs <= 0L) return
        scope.launch {
            AppLog.i("Player", "saveCurrentProgress book=$bookId file=$fileId pos=${positionMs}ms")
            repository.updatePosition(bookId, fileId, positionMs)
        }
    }

    /** Suspending version — awaits the DB write. Use with runBlocking in lifecycle callbacks. */
    suspend fun saveCurrentProgressNow() {
        val bookId = currentBookId.takeIf { it != -1L } ?: return
        val ctrl = controller ?: return
        val fileId = ctrl.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        val positionMs = ctrl.currentPosition
        if (positionMs <= 0L) { AppLog.i("Player", "saveProgressNow skipped (pos=0) book=$bookId"); return }
        AppLog.i("Player", "saveProgressNow book=$bookId file=$fileId pos=${positionMs}ms")
        repository.updatePosition(bookId, fileId, positionMs)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.e("Player", "playback error book=$currentBookId code=${error.errorCodeName}: ${error.message}", error)
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            AppLog.i("Player", "isPlaying=$isPlaying book=$currentBookId pos=${controller?.currentPosition ?: -1}ms")
            if (isPlaying) startPositionTicker() else stopPositionTicker()
            if (!isPlaying && currentBookId != -1L) {
                saveCurrentProgress()  // persist the moment we pause, not only at app-stop
                val bookId = currentBookId
                scope.launch { repository.updateLastPausedAt(bookId, System.currentTimeMillis()) }
            }
            syncState()
            // Session logic after syncState so _playbackState is current.
            if (isPlaying) {
                pauseTimerJob?.cancel()
                pauseTimerJob = null
                val bid = _playbackState.value.bookId.takeIf { it != -1L } ?: currentBookId
                if (sessionOpen && sessionBookId == bid) {
                    // Short pause ended — resume the same session
                    sessionSegmentStartMs = System.currentTimeMillis()
                } else {
                    openHistorySession()
                }
            } else {
                // Accumulate the segment that just ended
                if (sessionOpen && sessionSegmentStartMs > 0L) {
                    sessionAccumulatedMs += System.currentTimeMillis() - sessionSegmentStartMs
                    sessionSegmentStartMs = 0L
                }
                // Wait 20 min before committing the session; if play resumes we continue it
                if (sessionOpen) {
                    pauseTimerJob?.cancel()
                    pauseTimerJob = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        delay(PAUSE_SESSION_SPLIT_MS)
                        closeHistorySession()
                    }
                }
            }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { syncState() }
        override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) { syncState() }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && currentBookId != -1L) {
                val endedBook = currentBookId
                scope.launch { repository.markBookFinished(endedBook) }
                // Part of a series? Hand off so the next member book auto-starts.
                if (currentSeriesId != -1L) {
                    onSeriesBookEnded?.invoke(currentSeriesId, currentSeriesBookIds, endedBook)
                }
            }
            syncState()
        }

        fun triggerSync() = syncState()

        private fun syncState() {
            val ctrl = controller ?: return
            val meta = ctrl.currentMediaItem?.mediaMetadata
            val fileIndex = ctrl.currentMediaItemIndex
            val filePositionMs = ctrl.currentPosition
            val cumulativeStart = cumulativeStartsMs.getOrElse(fileIndex) { 0L }
            // For group playback, update currentBookId to the member book currently playing
            val memberBookId = ctrl.currentMediaItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
            if (currentGroupId != -1L && memberBookId != -1L) currentBookId = memberBookId
            _playbackState.value = PlaybackState(
                isPlaying = ctrl.isPlaying,
                bookId = currentBookId,
                groupId = currentGroupId,
                groupName = currentGroupName,
                bookTitle = meta?.albumTitle?.toString() ?: "",
                author = meta?.artist?.toString() ?: "",
                coverArtUri = meta?.artworkUri?.toString(),
                currentPositionMs = filePositionMs,
                durationMs = ctrl.duration.takeIf { it > 0 } ?: 0L,
                speed = ctrl.playbackParameters.speed,
                currentFileIndex = fileIndex,
                totalFiles = ctrl.mediaItemCount,
                bookPositionMs = cumulativeStart + filePositionMs,
                bookTotalDurationMs = bookTotalDurationMs,
                sleepTimerRemainingMs = sleepTimerRemainingMs
            )
        }
    }
}
