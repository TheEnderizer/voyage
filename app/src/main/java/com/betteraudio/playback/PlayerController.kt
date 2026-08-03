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
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.util.AppLog
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackState(
    val isPlaying: Boolean = false,
    val bookId: Long = -1L,
    val bookTitle: String = "",
    val author: String = "",
    val coverArtUri: String? = null,
    val speed: Float = 1f,
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
)

/**
 * Fields that change on every 500ms position-ticker tick while playing. Split out of
 * [PlaybackState] so collecting playback state at the app root (theme/nav) doesn't recompose
 * twice a second — only leaf composables that render a scrubber/progress bar need this.
 */
data class PositionState(
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    // Book-level (cumulative) position across all files
    val bookPositionMs: Long = 0L,
    val bookTotalDurationMs: Long = 0L,
    // Sleep timer
    val sleepTimerRemainingMs: Long = 0L,
    // True when the armed timer targets "end of chapter" rather than a fixed countdown.
    val sleepTimerEndOfChapter: Boolean = false
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
        // Position-history keep counts (see AudiobookRepository.insertSkipEventPruned) and timing.
        private const val SKIP_BUTTON_HISTORY_KEEP = 20
        private const val AUTO_CHECKPOINT_HISTORY_KEEP = 20
        // Sessions are lower-frequency and higher-value than skip events (a real listening block,
        // not a technical checkpoint) — kept far more generously.
        private const val SESSION_HISTORY_KEEP = 200
        private const val SKIP_BUTTON_COALESCE_MS = 2_000L
        private const val AUTO_CHECKPOINT_INTERVAL_MS = 10 * 60_000L
        // How close to the end of a file a corrupt-file skip may land. Inside this margin the
        // skip is treated as unrecoverable rather than seeking to (effectively) the end, which
        // would roll playback into the next file — see tryRecoverFromCorruptFile.
        private const val RECOVERY_END_MARGIN_MS = 5_000L
    }

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _positionState = MutableStateFlow(PositionState())
    val positionState: StateFlow<PositionState> = _positionState.asStateFlow()

    private var controller: MediaController? = null
    private var currentBookId = -1L

    // Series-continuation context: when the playing book belongs to an active series session,
    // the next book auto-starts when it ends. Managed by SeriesPlayer via [playBook].
    private var currentSeriesId = -1L
    private var currentSeriesBookIds: List<Long> = emptyList()
    /** Invoked when a series member book ends, so the next book can be loaded and played. */
    var onSeriesBookEnded: ((seriesId: Long, orderedBookIds: List<Long>, endedBookId: Long) -> Unit)? = null
    /** Emits the new book id when a series auto-advances, so the open player can re-target to it. */
    val seriesAdvanced = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 1)

    // The single shared chapter/file-boundary timeline for the loaded book — see
    // playback/ChapterTimeline.kt. @Volatile + whole-object replacement (not the old
    // main-thread-only discipline): recordSkipButtonTap/recordAutoCheckpoint below already read
    // this off Dispatchers.IO (scope's default dispatcher), and buildAndPlay/loadTimeline write
    // it from IO too.
    @Volatile private var chapterTimeline: ChapterTimeline = ChapterTimeline.EMPTY

    // ── Corrupt-file skip recovery ────────────────────────────────────────────
    // Escalating skip schedule: 1s steps to 10s, then 5s steps to 30s. Attempt N skips
    // recoverySkipSeconds[N] further past the damage; the schedule exhausting = give up.
    private val recoverySkipSeconds = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 25, 30)
    // Attempts made per media id during the current book load (cleared on each load). Doubles
    // as the give-up latch: a count past the schedule length means recovery failed for the file.
    // Written from the player listener (main thread) and from a coroutine on Dispatchers.IO —
    // this also doubles as the give-up latch, so a lost write means the escalating-skip
    // schedule never terminates and the app retries a broken file forever.
    private val recoveryAttempts = ConcurrentHashMap<String, Int>()
    // filePath + scanned duration per media id, captured at load so recovery can estimate a
    // bytes-per-second rate without an async DB round-trip mid-error-handling.
    private var fileInfoByItemId: Map<String, Pair<String, Long>> = emptyMap()

    // Sleep timer — PlaybackService is sole authority (see its class doc); this only mirrors the
    // last known state for the UI, updated optimistically on every local call AND authoritatively
    // whenever the service pushes CMD_SLEEP_STATE_CHANGED (covers shake-extends and scheduled
    // auto-arms, which happen entirely service-side with no local call to hook).
    private var sleepTimerRemainingMs: Long = 0L
    private var sleepTimerIsEndOfChapter: Boolean = false

    // Position ticker — updates the seek bar while playback is active
    private var positionTickerJob: Job? = null

    private fun startPositionTicker() {
        if (positionTickerJob?.isActive == true) return
        // Must run on Main — MediaController properties are main-thread only
        positionTickerJob = scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            while (isActive) {
                delay(500)
                playerListener.triggerSync()
                // Periodic persistence lives solely in PlaybackService's saver (survives the UI
                // dying, and swipe-kill has no onStop) — no duplicate writer needed here.

                autoCheckpointElapsedMs += 500
                if (autoCheckpointElapsedMs >= AUTO_CHECKPOINT_INTERVAL_MS) {
                    autoCheckpointElapsedMs = 0L
                    recordAutoCheckpoint()
                }
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
        autoCheckpointElapsedMs = 0L  // require CONTINUOUS listening, matching the doc on the field
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

    private val controllerListener = object : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle
        ): com.google.common.util.concurrent.ListenableFuture<SessionResult> {
            if (command.customAction == PlaybackService.CMD_SLEEP_STATE_CHANGED) {
                onSleepStatePushed(
                    mode = args.getString(PlaybackService.KEY_SLEEP_MODE, PlaybackService.SLEEP_MODE_OFF),
                    remainingMs = args.getLong(PlaybackService.KEY_SLEEP_REMAINING_MS, 0L)
                )
            }
            return com.google.common.util.concurrent.Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).setListener(controllerListener).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                controller?.addListener(playerListener)
                adoptLiveSession()
            } catch (e: Exception) {
                Log.e("PlayerController", "Failed to connect to MediaSession", e)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        // Main executor above is mandatory, not cosmetic: adoptLiveSession() reads MediaController
        // properties, which are main-thread-only. The previous `{ it.run() }` ran this callback on
        // whatever thread completed the future.
    }

    // Book id + full timeline currently being (re)loaded, so a book change in flight isn't
    // fetched twice by two different callers racing each other (syncState's self-heal below and
    // adoptLiveSession can both want the same load at connect time).
    @Volatile private var timelineLoadInFlightFor: Long = -1L

    private data class LoadedBookData(
        val timeline: ChapterTimeline,
        val fileInfo: Map<String, Pair<String, Long>>,
    )

    private suspend fun loadBookData(bookId: Long): LoadedBookData {
        val files = repository.getAudioFilesOnce(bookId)
        val chapters = repository.getChaptersForBookOnce(bookId)
        return LoadedBookData(
            timeline = ChapterTimeline.build(files, chapters, bookId),
            fileInfo = files.associate { it.id.toString() to (it.filePath to it.durationMs) },
        )
    }

    /**
     * Loads (or reloads) the full chapter timeline for [bookId] unless it's already current,
     * guarding against two concurrent callers duplicating the same DB fetch. [onLoaded] (optional)
     * is invoked on Main exactly once — either once this specific call's load lands, or
     * immediately if the timeline was already current when called. If another call for the same
     * [bookId] is already in flight, this one defers to it silently (no [onLoaded] of its own) —
     * callers that need the completion signal must be the one that starts the load, which is why
     * [adoptLiveSession] calls this BEFORE its own [PlayerController.playerListener.triggerSync]
     * (whose [syncState] would otherwise race it for ownership of the in-flight load).
     */
    private fun ensureTimelineFor(bookId: Long, onLoaded: (() -> Unit)? = null) {
        if (bookId == -1L) return
        if (chapterTimeline.bookId == bookId) { onLoaded?.invoke(); return }
        if (timelineLoadInFlightFor == bookId) return
        timelineLoadInFlightFor = bookId
        scope.launch {
            val data = loadBookData(bookId)
            val applied = currentBookId == bookId
            if (applied) {
                chapterTimeline = data.timeline
                fileInfoByItemId = data.fileInfo
            }
            if (timelineLoadInFlightFor == bookId) timelineLoadInFlightFor = -1L
            if (applied) withContext(Dispatchers.Main) { onLoaded?.invoke() }
        }
    }

    /**
     * Seeds local state from whatever the service is already doing at the moment a
     * [MediaController] connects — the service can already be playing a book (cold widget tap,
     * a Bluetooth resume) before this app process even existed, and until this runs,
     * [_playbackState]/[_positionState] sit at their defaults ([PlaybackState.bookId] == -1L).
     * That's why the chapter pill/scrubber and the listening-history session used to only appear
     * after the user pressed play. Runs on Main (guaranteed by [connect]'s executor).
     */
    private fun adoptLiveSession() {
        val ctrl = controller ?: return
        if (ctrl.mediaItemCount == 0) return  // nothing ever played — the Room-backed UI fallback covers this
        val liveBookId = ctrl.currentMediaItem?.mediaMetadata?.extras?.getLong("bookId", -1L) ?: -1L
        if (liveBookId != -1L) currentBookId = liveBookId
        val bid = currentBookId
        if (bid == -1L) { playerListener.triggerSync(); return }
        // Start the load (and own its completion callback) BEFORE the immediate triggerSync()
        // below — triggerSync() runs syncState() synchronously, which would otherwise also call
        // ensureTimelineFor(bid) and, if it went first, "win" ownership of the in-flight load,
        // leaving this call's onLoaded never invoked.
        ensureTimelineFor(bid) {
            // Only NOW — chapterTimeline/bookTotalDurationMs are real — so a session opened here
            // gets a correct start-chapter/-position instead of (-1, "", pos) from an empty
            // timeline, and the scrubber gets a real bookTotalDurationMs from the first tick.
            playerListener.triggerSync()
            if (ctrl.isPlaying) {
                startPositionTicker()
                openHistorySession()
            }
        }
        // bookId/isPlaying/title/speed land on this very first frame, before the DB round-trip
        // ensureTimelineFor just kicked off completes.
        playerListener.triggerSync()
    }

    fun disconnect() {
        closeHistorySession()
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
    }

    // ── History session helpers ──────────────────────────────────────────────
    private fun bookPosFromState(): Long {
        val st = _positionState.value
        return if (st.bookTotalDurationMs > 0) st.bookPositionMs else st.currentPositionMs
    }

    /** index, name, position-within-chapter for a book-level position — the shape the
     *  history/checkpoint call sites below already expect. Falls back to (-1, "", pos) when the
     *  timeline has nothing loaded yet (mirrors the old chapterAt's behaviour on an empty list). */
    private fun chapterInfoAt(bookPos: Long): Triple<Int, String, Long> {
        val mark = chapterTimeline.chapterAt(bookPos) ?: return Triple(-1, "", bookPos)
        return Triple(mark.index, mark.title, bookPos - mark.startMs)
    }

    /** Loads the full chapter timeline (embedded/per-file chapter rows) for [bookId] and upgrades
     *  [chapterTimeline] in place once ready. [buildAndPlay] has already assigned a synchronous
     *  file-granularity timeline before this is called, so nothing reads a null/empty timeline in
     *  the meantime — this only makes the chapter-level detail available a moment later. Guarded
     *  against a stale result landing after the user has already switched to a different book. */
    private fun loadTimeline(bookId: Long, files: List<AudioFile>) {
        scope.launch {
            val chapters = repository.getChaptersForBookOnce(bookId)
            val full = ChapterTimeline.build(files, chapters, bookId)
            if (currentBookId == bookId) chapterTimeline = full
        }
    }

    private fun openHistorySession() {
        val bid = _playbackState.value.bookId.takeIf { it != -1L }
            ?: currentBookId.takeIf { it != -1L } ?: return
        if (sessionOpen) closeHistorySession()   // flush previous session (different book)
        val pos = bookPosFromState()
        val (ci, cn, pic) = chapterInfoAt(pos)
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
        val (ci, cn, pic) = chapterInfoAt(pos)
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
        scope.launch { repository.insertListeningSessionPruned(session, SESSION_HISTORY_KEEP) }
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

    /** Global (not per-book) stereo balance/mono, forwarded to the playback service. */
    fun setChannelMix(balance: Float, mono: Boolean) {
        val ctrl = controller ?: return
        val args = Bundle().apply {
            putFloat(PlaybackService.KEY_AUDIO_BALANCE, balance)
            putBoolean(PlaybackService.KEY_MONO_AUDIO, mono)
        }
        ctrl.sendCustomCommand(SessionCommand(PlaybackService.CMD_SET_CHANNEL_MIX, Bundle.EMPTY), args)
    }

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
        // An end-of-chapter display is meaningless once the book changes (its target position no
        // longer applies) — clear it locally. A plain countdown is unaffected and left as-is
        // (same as before this refactor; whether a still-armed service-side timer should also be
        // cancelled on a book change is a separate, pre-existing behavior this doesn't alter).
        if (sleepTimerIsEndOfChapter) updateSleepDisplay(0L, endOfChapter = false)
        AppLog.i("Player", "playBook id=${book.id} '${book.displayTitle}' files=${files.size} startIdx=$startFileIndex startPos=${startPositionMs}ms speed=$speed series=$seriesId")
        currentBookId = book.id
        currentSeriesId = seriesId
        currentSeriesBookIds = seriesBookIds
        buildAndPlay(
            items = files.map { file ->
                buildMediaItem(
                    file = file,
                    memberBookId = book.id,
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
        // Upgrades chapterTimeline (already seeded file-granularity by buildAndPlay above) with
        // the real chapter rows once loaded — safe for an immediate bookSeekTo right after this
        // call (see SeriesPlayer's chapter-pick) because that seek only needs file boundaries.
        loadTimeline(book.id, files)
    }

    private fun buildMediaItem(
        file: AudioFile,
        memberBookId: Long,
        albumTitle: String,
        artist: String,
        artworkPath: String?
    ) = MediaItem.Builder()
        .setMediaId(file.id.toString())
        // Uri.fromFile percent-encodes; hand-building "file://$path" breaks on '%' or '#' in a name.
        .setUri(Uri.fromFile(java.io.File(file.filePath)))
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.fromFile(java.io.File(file.filePath)))
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
        // Synchronous, file-granularity timeline so an immediate bookSeekTo (e.g. SeriesPlayer's
        // chapter-pick right after playBook, see playBook below) never races the async chapter
        // load in loadTimeline — that upgrades this in place once the real chapter rows arrive.
        chapterTimeline = ChapterTimeline.ofFiles(allFiles, currentBookId)
        recoveryAttempts.clear()
        fileInfoByItemId = allFiles.associate { it.id.toString() to (it.filePath to it.durationMs) }

        val ctrl = controller ?: return
        ctrl.setMediaItems(items, startIndex, startPosition)
        ctrl.setPlaybackSpeed(speed)
        ctrl.prepare()
        ctrl.play()
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    /** Close the current book entirely: save its resume position, stop playback and clear the
     *  queue, then wipe the last-played/last-open markers so the mini bar disappears and nothing
     *  is restored on next launch. Called when the user swipes the mini bar down. */
    fun stop() {
        saveCurrentProgress()
        controller?.let {
            it.pause()
            it.clearMediaItems()
            it.stop()
        }
        currentBookId = -1L
        currentSeriesId = -1L
        currentSeriesBookIds = emptyList()
        stopPositionTicker()
        _playbackState.value = PlaybackState()
        _positionState.value = PositionState()
        scope.launch {
            settings.setLastPlayedBookId(-1L)
            settings.setLastOpenBookId(-1L)
        }
    }

    /** Like [stop], but *awaits* the final position write (via [saveCurrentProgressNow]) before
     *  clearing playback, instead of firing it off in [scope]. Used by
     *  [com.betteraudio.data.backup.BackupManager.restore], where a fire-and-forget save could
     *  land after restore has already read/written progress for the same book. Must be called
     *  from the main thread (MediaController is main-thread-only) — same as [stop]. */
    suspend fun stopAndFlush() {
        saveCurrentProgressNow()
        controller?.let {
            it.pause()
            it.clearMediaItems()
            it.stop()
        }
        currentBookId = -1L
        currentSeriesId = -1L
        currentSeriesBookIds = emptyList()
        stopPositionTicker()
        _playbackState.value = PlaybackState()
        _positionState.value = PositionState()
        settings.setLastPlayedBookId(-1L)
        settings.setLastOpenBookId(-1L)
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun skipForward() {
        val ctrl = controller ?: return
        val fromBookPos = _positionState.value.bookPositionMs
        ctrl.seekTo(ctrl.currentPosition + settings.currentSkipForwardMs)
        recordSkipButtonTap(fromBookPos, fromBookPos + settings.currentSkipForwardMs)
    }

    fun skipBack() {
        val ctrl = controller ?: return
        val fromBookPos = _positionState.value.bookPositionMs
        ctrl.seekTo(maxOf(0L, ctrl.currentPosition - settings.currentSkipBackMs))
        recordSkipButtonTap(fromBookPos, maxOf(0L, fromBookPos - settings.currentSkipBackMs))
    }

    // ── Position history: skip-button taps + periodic auto-checkpoints ─────────────────────
    // Both are lower-confidence than a PlayerViewModel-recorded "jump" (chapter/bookmark select),
    // so they're kept in a tighter rotation — see AudiobookRepository.insertSkipEventPruned.
    private var skipButtonCoalesceJob: Job? = null
    // Written from skipForward()/skipBack() (main thread) and read/reset from the coalescing
    // coroutine (scope = Dispatchers.IO) — @Volatile for cross-thread visibility.
    @Volatile private var skipButtonCoalesceFromMs: Long = -1L

    /** Coalesces rapid consecutive skip-button taps (fast-forward mashing) into one history
     *  entry: the first tap's start position, the latest tap's end position, written once no
     *  further taps arrive within [SKIP_BUTTON_COALESCE_MS]. */
    private fun recordSkipButtonTap(fromMs: Long, toMs: Long) {
        if (skipButtonCoalesceFromMs < 0L) skipButtonCoalesceFromMs = fromMs
        val coalescedFrom = skipButtonCoalesceFromMs
        skipButtonCoalesceJob?.cancel()
        skipButtonCoalesceJob = scope.launch {
            delay(SKIP_BUTTON_COALESCE_MS)
            skipButtonCoalesceFromMs = -1L
            val bid = _playbackState.value.bookId.takeIf { it != -1L } ?: currentBookId
            if (bid == -1L) return@launch
            val (ci, cn, _) = chapterInfoAt(toMs)
            repository.insertSkipEventPruned(
                SkipEvent(
                    bookId = bid, fromPositionMs = coalescedFrom, toPositionMs = toMs,
                    chapterIndex = ci, chapterName = cn, source = "skip_button"
                ),
                keep = SKIP_BUTTON_HISTORY_KEEP
            )
        }
    }

    // Accumulates continuous playing time (reset on pause) — see startPositionTicker/onIsPlayingChanged.
    private var autoCheckpointElapsedMs = 0L

    /** A periodic "you were here" marker, so the history list is useful even when the user never
     *  made an explicit jump (e.g. "where was I an hour ago"). */
    private fun recordAutoCheckpoint() {
        val bid = _playbackState.value.bookId.takeIf { it != -1L } ?: currentBookId
        if (bid == -1L) return
        val pos = _positionState.value.bookPositionMs
        val (ci, cn, _) = chapterInfoAt(pos)
        scope.launch {
            repository.insertSkipEventPruned(
                SkipEvent(bookId = bid, fromPositionMs = pos, toPositionMs = pos, chapterIndex = ci, chapterName = cn, source = "auto"),
                keep = AUTO_CHECKPOINT_HISTORY_KEEP
            )
        }
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

    /** Seek to an absolute book-level position (spans multiple files). Resolves via
     *  [chapterTimeline]/[ChapterTimeline.locate] and seeks by matching `mediaId` against the
     *  live queue first (so a queue built in a different file order can't mis-seek), falling
     *  back to the resolved file index, then to a plain positional seek if no timeline is loaded
     *  at all. */
    fun bookSeekTo(bookPositionMs: Long) {
        val ctrl = controller ?: return
        val locus = chapterTimeline.locate(bookPositionMs)
        if (locus == null) { ctrl.seekTo(bookPositionMs); return }
        val targetMediaId = locus.fileId.toString()
        var idx = -1
        for (i in 0 until ctrl.mediaItemCount) {
            if (ctrl.getMediaItemAt(i).mediaId == targetMediaId) { idx = i; break }
        }
        if (idx < 0) idx = locus.fileIndex
        ctrl.seekTo(idx, locus.offsetInFileMs)
    }

    /** Start a fixed-duration sleep timer; pauses playback after [durationMs]. Pass 0 to cancel.
     *  [PlaybackService] is sole authority (survives this process dying, and is the one place a
     *  shake-extend or scheduled auto-arm can happen) — this just sets an optimistic local value
     *  for instant UI feedback, then lets the service's push (see [onSleepStatePushed]) take over. */
    fun setSleepTimer(durationMs: Long) {
        val ctrl = controller
        if (durationMs <= 0L) {
            updateSleepDisplay(0L, endOfChapter = false)
            ctrl?.sendCustomCommand(
                SessionCommand(PlaybackService.CMD_SET_SLEEP_TIMER, Bundle.EMPTY),
                Bundle().apply { putString(PlaybackService.KEY_SLEEP_MODE, PlaybackService.SLEEP_MODE_OFF) }
            )
            return
        }
        updateSleepDisplay(durationMs, endOfChapter = false)
        ctrl?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply {
                putString(PlaybackService.KEY_SLEEP_MODE, PlaybackService.SLEEP_MODE_COUNTDOWN)
                putLong(PlaybackService.KEY_SLEEP_DURATION_MS, durationMs)
            }
        )
    }

    /** Arms a sleep timer that fires when the book reaches [targetBookPositionMs] (e.g. the end
     *  of the currently-playing chapter) instead of a fixed duration. Same authority split as
     *  [setSleepTimer]: optimistic local display, service push takes over from there. */
    fun setSleepTimerEndOfChapter(targetBookPositionMs: Long) {
        val remaining = (targetBookPositionMs - bookPosFromState()).coerceAtLeast(0L)
        updateSleepDisplay(remaining, endOfChapter = true)
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_SET_SLEEP_TIMER, Bundle.EMPTY),
            Bundle().apply {
                putString(PlaybackService.KEY_SLEEP_MODE, PlaybackService.SLEEP_MODE_END_OF_CHAPTER)
                // Absolute book-level ms — the service derives its OWN current position from the
                // live ExoPlayer timeline and compares directly; see KEY_SLEEP_TARGET_POSITION_MS.
                putLong(PlaybackService.KEY_SLEEP_TARGET_POSITION_MS, targetBookPositionMs)
            }
        )
    }

    private fun updateSleepDisplay(remainingMs: Long, endOfChapter: Boolean) {
        sleepTimerRemainingMs = remainingMs
        sleepTimerIsEndOfChapter = endOfChapter
        _positionState.value = _positionState.value.copy(
            sleepTimerRemainingMs = remainingMs,
            sleepTimerEndOfChapter = endOfChapter
        )
    }

    /** Authoritative state pushed from [PlaybackService] (CMD_SLEEP_STATE_CHANGED) — covers every
     *  case a local call can't: shake-to-extend, the 1s countdown tick, and a scheduled auto-arm
     *  the user never explicitly requested from this process. */
    private fun onSleepStatePushed(mode: String, remainingMs: Long) {
        updateSleepDisplay(remainingMs, endOfChapter = mode == PlaybackService.SLEEP_MODE_END_OF_CHAPTER)
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

    private fun showUserMessage(msg: String) {
        scope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * ExoPlayer's extractor is stricter than platform decoders: a file with a corrupt section
     * fails with ERROR_CODE_PARSING_CONTAINER_MALFORMED even when the rest plays fine. Lenient
     * players (e.g. Smart AudioBook Player) just skip the bad section. We mimic that with an
     * escalating skip: tell the user the file is corrupt and that we're skipping the damage,
     * then retry 1s further in, then 2s, … up to 10s, then 5s steps up to 30s. If 30s still
     * fails, tell the user to skip manually or accept the file can't be played.
     *
     * Two skip mechanisms, chosen by whether the file ever prepared (duration known):
     *  - **Head damage** (prepare failed, no duration): a time-seek can't work — with no seek
     *    map, re-preparing always re-reads the same corrupt head — so hide the corresponding
     *    leading *bytes* from the extractor (see [SkipHeadDataSource]) and it synchronizes in
     *    clean audio. The attempted start position is preserved (adjusted for the skip) so a
     *    resumed book doesn't get thrown back to the file start.
     *  - **Mid-file damage** (was prepared/playing): the seek map exists, so simply step the
     *    play position forward past the damage and re-prepare.
     *
     * @return true if a retry was kicked off (caller should treat the error as handled).
     */
    /**
     * Abandons recovery for [itemId]: latches the attempt counter past the schedule so no further
     * error re-enters the escalation, tells the user, and — crucially — **pauses**.
     *
     * Pausing is what stops a damaged file from cascading. Left playing, an unrecoverable item
     * rolls into the next file, and once the last file is exhausted STATE_ENDED marks the book
     * finished and (in a series) auto-advances to the next book, so one bad book could silently
     * burn through several. Stopping on the broken file leaves the user where the problem is.
     */
    private fun giveUpOnCorruptFile(itemId: String) {
        recoveryAttempts[itemId] = recoverySkipSeconds.size + 1
        showUserMessage("Couldn't skip past the damaged section — this file can't be played. Try the next part manually.")
        scope.launch(Dispatchers.Main) { controller?.pause() }
    }

    private fun tryRecoverFromCorruptFile(error: androidx.media3.common.PlaybackException): Boolean {
        if (error.errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED) return false
        val ctrl = controller ?: return false
        val item = ctrl.currentMediaItem ?: return false
        val itemId = item.mediaId
        val attempt = recoveryAttempts.getOrDefault(itemId, 0)
        recoveryAttempts[itemId] = attempt + 1
        if (attempt >= recoverySkipSeconds.size) {
            if (attempt == recoverySkipSeconds.size) {  // first error past the schedule → give up once
                AppLog.e("Player", "corrupt-file recovery exhausted (30s) for item=$itemId")
                showUserMessage("Couldn't skip past the damaged section (tried up to 30s). Try skipping manually — if that doesn't work, this file can't be played.")
                // Pause for the same reason giveUpOnCorruptFile does: an exhausted file left
                // playing rolls into the next part, and eventually the next book of a series.
                scope.launch(Dispatchers.Main) { controller?.pause() }
            }
            return false
        }
        if (attempt == 0) {
            showUserMessage("This audio file is corrupted — attempting to skip past the damaged section…")
        }
        val skipSeconds = recoverySkipSeconds[attempt]
        val index = ctrl.currentMediaItemIndex
        val positionMs = ctrl.currentPosition.coerceAtLeast(0L)
        val playerDurationMs = ctrl.duration.takeIf { it > 0L } ?: 0L
        val scannedDurationMs = fileInfoByItemId[itemId]?.second?.takeIf { it > 0L } ?: 0L
        // A damaged header routinely makes ExoPlayer's own duration estimate wildly short (seconds
        // for a multi-hour file). Trusting it would send us down the mid-file path with a limit so
        // small that even a +1s skip "overshoots", so cross-check it against the scanned duration
        // and fall through to the byte-level head skip — which needs no seek map at all — whenever
        // it looks untrustworthy.
        val durationIsTrustworthy = playerDurationMs > 0L &&
            (scannedDurationMs <= 0L || playerDurationMs >= scannedDurationMs / 2)

        if (durationIsTrustworthy) {
            // Mid-file damage: step past it and resume.
            val targetMs = positionMs + skipSeconds * 1_000L
            // The seek MUST stay inside this item. seekTo(index, pos) past the item's duration
            // lands at its end and, with playback active, immediately rolls into the NEXT file.
            // With every part of a book damaged that cascades through the whole book and then, via
            // STATE_ENDED, into the next book of a series — marking each one finished on the way.
            // Skipping forward can't help beyond the end of the audio anyway, so stop instead.
            if (targetMs >= playerDurationMs - RECOVERY_END_MARGIN_MS) {
                AppLog.e("Player", "corrupt-file recovery item=$itemId: +${skipSeconds}s would pass the end (${targetMs}ms of ${playerDurationMs}ms) — giving up rather than rolling into the next file")
                giveUpOnCorruptFile(itemId)
                return true
            }
            AppLog.w("Player", "corrupt-file recovery item=$itemId attempt=${attempt + 1}: mid-file, seeking +${skipSeconds}s from ${positionMs}ms (limit ${playerDurationMs}ms)")
            scope.launch(Dispatchers.Main) {
                val c = controller ?: return@launch
                c.seekTo(index, targetMs)
                c.prepare()
                c.play()
            }
            return true
        }

        // Head damage: hide leading bytes so the extractor never sees the corrupt region.
        val info = fileInfoByItemId[itemId] ?: return false
        val (filePath, durationMs) = info
        scope.launch {  // IO: reads the file header for the ID3 size
            val file = java.io.File(filePath)
            val fileLen = file.length()
            val id3Bytes = SkipHeadDataSource.id3TagSizeBytes(file)
            val audioBytes = (fileLen - id3Bytes).coerceAtLeast(1L)
            // Approximate byte rate from the scanned duration; assume 256 kbps when unknown.
            val bytesPerSec = if (durationMs > 1_000L) audioBytes * 1_000 / durationMs else 32_000L
            val skipBytes = id3Bytes + skipSeconds * bytesPerSec
            if (skipBytes >= fileLen) {
                giveUpOnCorruptFile(itemId)
                return@launch
            }
            // Keep the position the user was starting from, shifted into the skipped timeline.
            val resumeMs = (positionMs - skipSeconds * 1_000L).coerceAtLeast(0L)
            AppLog.w("Player", "corrupt-file recovery item=$itemId attempt=${attempt + 1}: head, hiding ${skipSeconds}s ($skipBytes of $fileLen bytes), resume at ${resumeMs}ms")
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                val c = controller ?: return@withContext
                val skipUri = SkipHeadDataSource.wrapUri(Uri.fromFile(file), skipBytes)
                // BOTH uri and requestMetadata.mediaUri must carry the skip marker.
                // PlaybackService.onAddMediaItems resolves an item's uri from requestMetadata
                // when the item has none, so leaving mediaUri pointing at the original unskipped
                // path is how this recovery silently no-op'd: the service handed the extractor
                // byte 0 again and every escalating retry re-read the same corrupt head.
                val retryItem = item.buildUpon()
                    .setUri(skipUri)
                    .setRequestMetadata(
                        item.requestMetadata.buildUpon().setMediaUri(skipUri).build()
                    )
                    .build()
                c.replaceMediaItem(index, retryItem)
                c.seekTo(index, resumeMs)
                c.prepare()
                c.play()
            }
        }
        return true
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            AppLog.e("Player", "playback error book=$currentBookId code=${error.errorCodeName}: ${error.message}", error)
            if (tryRecoverFromCorruptFile(error)) return
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
            // A recovery attempt that reaches READY found clean audio — log the win.
            if (playbackState == Player.STATE_READY) {
                val id = controller?.currentMediaItem?.mediaId
                val attempts = id?.let { recoveryAttempts[it] } ?: 0
                if (attempts in 1..recoverySkipSeconds.size) {
                    AppLog.i("Player", "corrupt-file recovery: prepared OK for item=$id after $attempts attempt(s) (${recoverySkipSeconds[attempts - 1]}s skipped)")
                }
            }
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
            // Derive the book from the live session rather than trusting currentBookId alone —
            // this is what makes PlayerController self-healing for any service-initiated book
            // change (cold widget resume via PlaybackService.loadLastPlayedAndPlay, a future
            // service-side series advance): Media3 fires onMediaItemTransition with
            // MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED when the service's own setMediaItems
            // lands, so a connected controller picks it up automatically without a bespoke push.
            val itemBookId = meta?.extras?.getLong("bookId", -1L) ?: -1L
            val effectiveBookId = if (itemBookId != -1L) itemBookId else currentBookId
            if (effectiveBookId != -1L) {
                if (effectiveBookId != currentBookId) currentBookId = effectiveBookId
                ensureTimelineFor(effectiveBookId)
            }
            val fileIndex = ctrl.currentMediaItemIndex
            val filePositionMs = ctrl.currentPosition
            val fileId = ctrl.currentMediaItem?.mediaId?.toLongOrNull()
            val timeline = chapterTimeline
            val cumulativeStart = fileId?.let { timeline.startOfFileMs(it) }
                ?: timeline.fileStartsMs.getOrElse(fileIndex) { 0L }
            val bookPos = cumulativeStart + filePositionMs
            // Sleep-timer fields are NOT recomputed here — PlaybackService is sole authority and
            // pushes updates directly into sleepTimerRemainingMs/sleepTimerIsEndOfChapter (see
            // onSleepStatePushed); this just carries the last-known values through.
            _positionState.value = PositionState(
                currentPositionMs = filePositionMs,
                durationMs = ctrl.duration.takeIf { it > 0 } ?: 0L,
                bookPositionMs = bookPos,
                bookTotalDurationMs = timeline.bookTotalMs,
                sleepTimerRemainingMs = sleepTimerRemainingMs,
                sleepTimerEndOfChapter = sleepTimerIsEndOfChapter
            )

            // Data-class equality makes this a no-op write (no new emission) on the common tick
            // where only position moved — this is what keeps app-root collectors of playbackState
            // (theme, nav) quiet while playing instead of recomposing twice a second.
            val newPlaybackState = PlaybackState(
                isPlaying = ctrl.isPlaying,
                bookId = effectiveBookId,
                bookTitle = meta?.albumTitle?.toString() ?: "",
                author = meta?.artist?.toString() ?: "",
                coverArtUri = meta?.artworkUri?.toString(),
                speed = ctrl.playbackParameters.speed,
                currentFileIndex = fileIndex,
                totalFiles = ctrl.mediaItemCount,
            )
            if (newPlaybackState != _playbackState.value) _playbackState.value = newPlaybackState
        }
    }
}
