package com.betteraudio.playback

import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.db.entities.BookStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts continuous playback of a whole series: every member book's files are flattened, in
 * series order, into one timeline so a finishing book auto-advances to the next book. Each book
 * keeps its own progress (every media item carries its own bookId), so resume stays correct even
 * if the order later changes. Shared by the home series tile and the series screen.
 */
@Singleton
class SeriesPlayer @Inject constructor(
    private val seriesRepository: SeriesRepository,
    private val repository: AudiobookRepository,
    private val settings: SettingsStore,
    private val playerController: PlayerController
) {
    /**
     * Play [seriesId]. When [startBookId] is null, resume the member the listener was last in
     * (most-recently-played → first unfinished → first). When set, start at that book (at its own
     * saved position) and continue into the following books.
     */
    suspend fun playSeries(seriesId: Long, startBookId: Long? = null) {
        val series = seriesRepository.getSeriesOnce(seriesId) ?: return
        val books = seriesRepository.getBooksInSeriesOnce(seriesId)
        if (books.isEmpty()) return
        val filesPerBook = seriesRepository.getAudioFilesForBooks(books.map { it.id })
        val progressMap = books.associateWith { repository.getProgressForBookOnce(it.id) }

        val startBook = startBookId?.let { id -> books.firstOrNull { it.id == id } }
            ?: progressMap.entries
                .filter { (_, p) -> (p?.lastPlayedMs ?: 0L) > 0L }
                .maxByOrNull { (_, p) -> p?.lastPlayedMs ?: 0L }?.key
            ?: books.firstOrNull { it.status != BookStatus.FINISHED }
            ?: books.first()
        val startProgress = progressMap[startBook]

        var globalIndex = 0
        for (book in books) {
            val files = filesPerBook[book.id] ?: emptyList()
            if (book.id == startBook.id) {
                globalIndex += files.indexOfFirst { it.id == startProgress?.currentFileId }.coerceAtLeast(0)
                break
            }
            globalIndex += files.size
        }
        val startPos = if (startProgress?.isCompleted == true) 0L else startProgress?.positionMs ?: 0L

        playerController.playBookGroup(
            groupId = series.id,
            groupName = series.name,
            coverArtPath = series.coverArtPath,
            orderedBooks = books,
            filesPerBook = filesPerBook,
            startGlobalFileIndex = globalIndex,
            startPositionMs = startPos,
            speed = series.playbackSpeed ?: settings.currentDefaultSpeed
        )
        repository.touchLastPlayed(startBook.id)
        settings.setLastPlayedBookId(startBook.id)
    }
}
