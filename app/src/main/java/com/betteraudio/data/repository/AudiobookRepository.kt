package com.betteraudio.data.repository

import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudiobookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val progressDao: PlaybackProgressDao,
    private val chapterDao: ChapterDao
) {

    fun getBooksInProgress(): Flow<List<BookWithProgress>> = bookDao.getBooksInProgress()
    fun getNotStartedBooks(): Flow<List<Book>> = bookDao.getNotStartedBooks()
    fun getFinishedBooks(): Flow<List<Book>> = bookDao.getFinishedBooks()
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooksSorted()
    fun getBookById(bookId: Long): Flow<Book?> = bookDao.getBookById(bookId)
    fun getBookWithProgress(bookId: Long): Flow<BookWithProgress?> = bookDao.getBookWithProgress(bookId)
    fun getMostRecentProgress(): Flow<PlaybackProgress?> = progressDao.getMostRecentProgress()
    fun getProgressForBook(bookId: Long): Flow<PlaybackProgress?> = progressDao.getProgressForBook(bookId)
    fun getAudioFilesForBook(bookId: Long): Flow<List<AudioFile>> = audioFileDao.getFilesForBook(bookId)
    fun getChaptersForBook(bookId: Long): Flow<List<Chapter>> = chapterDao.getChaptersForBook(bookId)
    suspend fun getChaptersForBookOnce(bookId: Long): List<Chapter> = chapterDao.getChaptersForBookOnce(bookId)
    suspend fun chapterCountForBook(bookId: Long): Int = chapterDao.countForBook(bookId)
    suspend fun replaceChapters(bookId: Long, chapters: List<Chapter>) {
        chapterDao.deleteForBook(bookId)
        chapterDao.insertAll(chapters)
    }
    fun searchBooks(query: String): Flow<List<Book>> = bookDao.searchBooks(query)
    fun getBooksInSeries(seriesName: String): Flow<List<Book>> = bookDao.getBooksInSeries(seriesName)
    fun getAllBooksWithProgressUngrouped(): Flow<List<BookWithProgress>> = bookDao.getAllBooksWithProgressUngrouped()

    suspend fun upsertBook(book: Book): Long =
        if (book.id != 0L) {
            // Real UPDATE — REPLACE would DELETE+INSERT and cascade-delete playback_progress
            bookDao.update(book)
            book.id
        } else {
            bookDao.upsert(book)
        }
    suspend fun insertAudioFiles(files: List<AudioFile>) = audioFileDao.insertAll(files)
    suspend fun clearAudioFiles(bookId: Long) = audioFileDao.deleteFilesForBook(bookId)
    suspend fun getAudioFilesOnce(bookId: Long): List<AudioFile> = audioFileDao.getFilesForBookOnce(bookId)
    suspend fun saveProgress(progress: PlaybackProgress) = progressDao.upsert(progress)
    suspend fun getBookByFolder(folderPath: String): Book? = bookDao.getBookByFolder(folderPath)
    suspend fun updateCoverArt(bookId: Long, path: String) = bookDao.updateCoverArt(bookId, path)
    suspend fun updateBookStatus(bookId: Long, status: BookStatus) = bookDao.updateStatus(bookId, status)
    suspend fun updateSeriesInfo(bookId: Long, seriesName: String?, seriesOrder: Float?) =
        bookDao.updateSeriesInfo(bookId, seriesName, seriesOrder)

    suspend fun updatePosition(bookId: Long, fileId: Long, positionMs: Long) {
        val existing = progressDao.getProgressForBookOnce(bookId)
        if (existing == null) {
            progressDao.upsert(
                PlaybackProgress(
                    bookId = bookId,
                    currentFileId = fileId,
                    positionMs = positionMs,
                    lastPlayedMs = System.currentTimeMillis()
                )
            )
        } else {
            progressDao.updatePosition(bookId, fileId, positionMs, System.currentTimeMillis())
        }
        bookDao.updateStatus(bookId, BookStatus.IN_PROGRESS)
    }

    suspend fun updateSpeed(bookId: Long, speed: Float) {
        val existing = progressDao.getProgressForBookOnce(bookId)
        if (existing == null) {
            progressDao.upsert(PlaybackProgress(bookId = bookId, playbackSpeed = speed))
        } else {
            progressDao.updateSpeed(bookId, speed)
        }
    }

    suspend fun markBookFinished(bookId: Long) {
        progressDao.markCompleted(bookId, System.currentTimeMillis())
        bookDao.updateStatus(bookId, BookStatus.FINISHED)
    }

    suspend fun updateSynopsis(bookId: Long, synopsis: String) = bookDao.updateSynopsis(bookId, synopsis)

    suspend fun getBooksByIds(ids: List<Long>): List<Book> = bookDao.getBooksByIds(ids)
    suspend fun getAllUngroupedOnce(): List<Book> = bookDao.getAllUngroupedOnce()
}
