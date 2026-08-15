package com.betteraudio.playback

import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays a series in the **normal book player** — one member book at a time — and automatically
 * starts the next book when the current one ends. Because each book plays exactly like a
 * standalone book, the player UI is identical to a single book's; the only difference is that a
 * finishing book hands off to the next. Each book keeps its own progress, so resuming a series
 * continues from wherever the listener actually is.
 */
@Singleton
class SeriesPlayer @Inject constructor(
    private val seriesRepository: SeriesRepository,
    private val repository: AudiobookRepository,
    private val settings: SettingsStore,
    private val playerController: PlayerController,
    private val jumpRestoreStore: JumpRestoreStore
) {
    // MediaController calls must happen on the main thread; Room suspend reads switch context
    // internally, so a main scope is safe for the load-then-play advance.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // KNOWN CAVEAT: this singleton is only constructed when Hilt first injects it (Home/Player/
    // SeriesDetail ViewModels), so the onSeriesBookEnded hook below isn't registered until one of
    // those screens has been built at least once in this process. A service-only resume (e.g. a
    // cold widget tap with the Activity never opened) therefore won't auto-advance a finishing
    // series book until the app UI is opened. Acceptable today because loadLastPlayedAndPlay
    // doesn't carry series context either; revisit if the widget path ever becomes series-aware.
    init {
        playerController.onSeriesBookEnded = { seriesId, orderedBookIds, endedBookId ->
            val endedIndex = orderedBookIds.indexOf(endedBookId)
            val nextId = if (endedIndex >= 0) orderedBookIds.getOrNull(endedIndex + 1) else null
            if (nextId != null) {
                AppLog.i(LogCat.PLAYBACK, "series=$seriesId book=$endedBookId ended (index $endedIndex/${orderedBookIds.size - 1}) — advancing to book=$nextId")
                scope.launch {
                    playBookInSeries(nextId, seriesId, orderedBookIds, resume = false)
                    playerController.seriesAdvanced.tryEmit(nextId)
                }
            } else {
                AppLog.i(LogCat.PLAYBACK, "series=$seriesId book=$endedBookId ended (index $endedIndex/${orderedBookIds.size - 1}) — series finished, no next book")
            }
        }
    }

    /**
     * Start playing [seriesId]. With [startBookId] null, resume the member the listener was last
     * in (most-recently-played → first unfinished → first); otherwise start at that book.
     * Returns the book id that started playing, or -1 if the series is empty.
     */
    suspend fun playSeries(seriesId: Long, startBookId: Long? = null): Long {
        val books = seriesRepository.getBooksInSeriesOnce(seriesId)
        if (books.isEmpty()) {
            AppLog.w(LogCat.PLAYBACK, "playSeries: series=$seriesId has no books")
            return -1L
        }
        val orderedIds = books.map { it.id }
        val progressMap = books.associateWith { repository.getProgressForBookOnce(it.id) }
        val mostRecent = progressMap.entries
            .filter { (_, p) -> (p?.lastPlayedMs ?: 0L) > 0L }
            .maxByOrNull { (_, p) -> p?.lastPlayedMs ?: 0L }?.key
        val startBook: com.betteraudio.data.db.entities.Book
        val reason: String
        when {
            startBookId != null && books.any { it.id == startBookId } -> {
                startBook = books.first { it.id == startBookId }; reason = "explicit"
            }
            mostRecent != null -> { startBook = mostRecent; reason = "most-recently-played" }
            books.any { it.status != BookStatus.FINISHED } -> {
                startBook = books.first { it.status != BookStatus.FINISHED }; reason = "first-unfinished"
            }
            else -> { startBook = books.first(); reason = "first (all finished)" }
        }
        AppLog.i(LogCat.PLAYBACK, "playSeries: series=$seriesId starting book=${startBook.id} reason=$reason")
        playBookInSeries(startBook.id, seriesId, orderedIds, resume = true)
        return startBook.id
    }

    /** Play a specific member [bookId] of [seriesId] at [positionMs] within that book (used when
     *  a chapter from another book is picked in the chapter list); the series continues from there. */
    suspend fun playSeriesBookAt(seriesId: Long, bookId: Long, positionMs: Long) {
        val books = seriesRepository.getBooksInSeriesOnce(seriesId)
        if (books.none { it.id == bookId }) {
            AppLog.w(LogCat.PLAYBACK, "playSeriesBookAt: book=$bookId is not a member of series=$seriesId")
            return
        }
        AppLog.i(LogCat.PLAYBACK, "playSeriesBookAt: series=$seriesId switching to book=$bookId at ${positionMs}ms")
        playBookInSeries(bookId, seriesId, books.map { it.id }, resume = false, explicitPositionMs = positionMs)
        playerController.seriesAdvanced.tryEmit(bookId)
    }

    private suspend fun playBookInSeries(
        bookId: Long, seriesId: Long, orderedIds: List<Long>, resume: Boolean,
        explicitPositionMs: Long? = null
    ) {
        val bwp = repository.getBookWithProgress(bookId).first()
        if (bwp == null) {
            AppLog.w(LogCat.PLAYBACK, "playBookInSeries: book=$bookId (series=$seriesId) not found")
            return
        }
        val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        if (files.isEmpty()) {
            AppLog.w(LogCat.PLAYBACK, "playBookInSeries: book=$bookId (series=$seriesId) has no audio files")
            return
        }
        val series = seriesRepository.getSeriesOnce(seriesId)
        val progress = bwp.progress
        // A genuine resume (not a fresh auto-advance to the next book or an explicit chapter
        // pick) goes through the same resolveStart every other resume path uses — which forces
        // file 0 / position 0 for a finished book (see its KDoc). Anything else starts at file 0,
        // position 0 unconditionally: an auto-advance starts its new book from the beginning, and
        // an explicit pick is seeked to its exact position afterward via bookSeekTo below.
        val startIndex: Int
        val startPos: Long
        if (resume && explicitPositionMs == null) {
            val rewind = AudioCascade.autoRewindMs(settings, progress?.lastPausedAt ?: 0L)
            val resolved = AudioCascade.resolveStart(files, progress, rewind, bookId, jumpRestoreStore)
            startIndex = resolved.startIndex
            startPos = resolved.startPositionMs
        } else {
            startIndex = 0
            startPos = 0L
        }
        // Effective audio: book override → series default → global default preset → scalar fallback.
        val gPreset = repository.getDefaultAudioPreset()
        val audio = AudioCascade.resolve(bwp.book, progress, series, gPreset, settings.currentDefaultSpeed)
        playerController.playBook(
            book = bwp.book, files = files, startFileIndex = startIndex, startPositionMs = startPos,
            speed = audio.speed, seriesId = seriesId, seriesBookIds = orderedIds
        )
        // Seek to an exact within-book position once the timeline is loaded (chapter pick).
        if (explicitPositionMs != null) playerController.bookSeekTo(explicitPositionMs)
        playerController.setVolumeBoost(audio.boostDb)
        playerController.setEqBands(audio.eqBandsJson)
        playerController.setSkipSilence(audio.skipSilence)
        repository.touchLastPlayed(bwp.book.id)
        settings.setLastPlayedBookId(bwp.book.id)
        settings.setThemeBookId(bwp.book.id)
        // Playing through the series path shows the SERIES cover in the player (and themes the
        // app from it). Opening a book directly from the Books view resets this to false.
        settings.setPlayerShowSeriesCover(true)
    }
}
