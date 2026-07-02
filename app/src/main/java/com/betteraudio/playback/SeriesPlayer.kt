package com.betteraudio.playback

import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
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
    private val playerController: PlayerController
) {
    // MediaController calls must happen on the main thread; Room suspend reads switch context
    // internally, so a main scope is safe for the load-then-play advance.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        playerController.onSeriesBookEnded = { seriesId, orderedBookIds, endedBookId ->
            val nextId = orderedBookIds.getOrNull(orderedBookIds.indexOf(endedBookId) + 1)
            if (nextId != null) {
                scope.launch {
                    playBookInSeries(nextId, seriesId, orderedBookIds, resume = false)
                    playerController.seriesAdvanced.tryEmit(nextId)
                }
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
        if (books.isEmpty()) return -1L
        val orderedIds = books.map { it.id }
        val progressMap = books.associateWith { repository.getProgressForBookOnce(it.id) }
        val startBook = startBookId?.let { id -> books.firstOrNull { it.id == id } }
            ?: progressMap.entries
                .filter { (_, p) -> (p?.lastPlayedMs ?: 0L) > 0L }
                .maxByOrNull { (_, p) -> p?.lastPlayedMs ?: 0L }?.key
            ?: books.firstOrNull { it.status != BookStatus.FINISHED }
            ?: books.first()
        playBookInSeries(startBook.id, seriesId, orderedIds, resume = true)
        return startBook.id
    }

    private suspend fun playBookInSeries(
        bookId: Long, seriesId: Long, orderedIds: List<Long>, resume: Boolean
    ) {
        val bwp = repository.getBookWithProgress(bookId).first() ?: return
        val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        if (files.isEmpty()) return
        val series = seriesRepository.getSeriesOnce(seriesId)
        val progress = bwp.progress
        val startIndex = if (resume)
            files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0) else 0
        val startPos = if (resume && progress?.isCompleted != true) progress?.positionMs ?: 0L else 0L
        val speed = progress?.playbackSpeed ?: series?.playbackSpeed ?: settings.currentDefaultSpeed
        playerController.playBook(
            book = bwp.book, files = files, startFileIndex = startIndex, startPositionMs = startPos,
            speed = speed, seriesId = seriesId, seriesBookIds = orderedIds
        )
        playerController.setVolumeBoost(progress?.boostDb ?: series?.boostDb ?: 0)
        repository.touchLastPlayed(bwp.book.id)
        settings.setLastPlayedBookId(bwp.book.id)
    }
}
