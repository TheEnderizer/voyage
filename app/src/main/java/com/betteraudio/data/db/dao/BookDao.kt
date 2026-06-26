package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.model.BookWithProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Transaction
    @Query("""
        SELECT books.* FROM books
        LEFT JOIN playback_progress ON books.id = playback_progress.bookId
        WHERE books.status = 'IN_PROGRESS'
        ORDER BY playback_progress.lastPlayedMs DESC
    """)
    fun getBooksInProgress(): Flow<List<BookWithProgress>>

    @Query("SELECT * FROM books WHERE status = 'NOT_STARTED' ORDER BY title ASC")
    fun getNotStartedBooks(): Flow<List<Book>>

    @Query("""
        SELECT books.* FROM books
        LEFT JOIN playback_progress ON books.id = playback_progress.bookId
        WHERE books.status = 'FINISHED'
        ORDER BY playback_progress.completedDateMs DESC
    """)
    fun getFinishedBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookById(id: Long): Flow<Book?>

    @Transaction
    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookWithProgress(id: Long): Flow<BookWithProgress?>

    @Query("SELECT * FROM books WHERE folderPath = :path LIMIT 1")
    suspend fun getBookByFolder(path: String): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Query("UPDATE books SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: BookStatus)

    @Query("UPDATE books SET totalDurationMs = :durationMs, fileCount = :count WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long, count: Int)

    @Query("UPDATE books SET coverArtPath = :path WHERE id = :id")
    suspend fun updateCoverArt(id: Long, path: String)

    @Query("UPDATE books SET coverFxPath = :path WHERE id = :id")
    suspend fun updateCoverFx(id: Long, path: String?)

    @Query("UPDATE books SET seriesName = :seriesName, seriesOrder = :seriesOrder WHERE id = :id")
    suspend fun updateSeriesInfo(id: Long, seriesName: String?, seriesOrder: Float?)

    @Query("SELECT * FROM books ORDER BY CASE WHEN seriesName IS NULL THEN title ELSE seriesName END ASC, seriesOrder ASC, title ASC")
    fun getAllBooksSorted(): Flow<List<Book>>

    @Query("""
        SELECT * FROM books
        WHERE title LIKE '%' || :query || '%'
           OR author LIKE '%' || :query || '%'
           OR seriesName LIKE '%' || :query || '%'
        ORDER BY title ASC
    """)
    fun searchBooks(query: String): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE seriesName = :seriesName ORDER BY seriesOrder ASC, title ASC")
    fun getBooksInSeries(seriesName: String): Flow<List<Book>>

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET groupId = :groupId WHERE id = :bookId")
    suspend fun setGroupId(bookId: Long, groupId: Long?)

    @Query("UPDATE books SET synopsis = :synopsis WHERE id = :id")
    suspend fun updateSynopsis(id: Long, synopsis: String)

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getBooksByIds(ids: List<Long>): List<Book>

    @Query("SELECT * FROM books WHERE groupId IS NULL")
    suspend fun getAllUngroupedOnce(): List<Book>

    @Transaction
    @Query("SELECT * FROM books WHERE groupId IS NULL AND isIgnored = 0 ORDER BY addedDateMs DESC")
    fun getAllBooksWithProgressUngrouped(): Flow<List<com.betteraudio.data.model.BookWithProgress>>

    @Query("SELECT * FROM books WHERE isIgnored = 1 ORDER BY title ASC")
    fun getAllIgnoredBooks(): Flow<List<Book>>

    @Query("UPDATE books SET isIgnored = :ignored WHERE id = :id")
    suspend fun setIgnored(id: Long, ignored: Boolean)

    @Query("UPDATE books SET titleOverride = :titleOverride, authorOverride = :authorOverride WHERE id = :id")
    suspend fun updateMetadata(id: Long, titleOverride: String?, authorOverride: String?)

    @Query("SELECT * FROM books WHERE coverArtPath IS NOT NULL ORDER BY title ASC")
    suspend fun getAllBooksSortedOnce(): List<Book>

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)
}
