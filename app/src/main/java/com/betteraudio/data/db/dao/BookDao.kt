package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.model.BookWithProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY title ASC")
    fun getAllBooks(): Flow<List<Book>>

    // Every book including ignored/hidden ones — used by the scanner's disk reconciliation.
    @Query("SELECT * FROM books")
    suspend fun getAllBooksOnce(): List<Book>

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

    // Repoint a book's folder (and cover) to a new on-disk location — library restructure move.
    // coverFxPath is left intact (the cover image itself is unchanged, just relocated).
    @Query("UPDATE books SET folderPath = :folderPath, coverArtPath = :coverArtPath WHERE id = :id")
    suspend fun updateLocation(id: Long, folderPath: String, coverArtPath: String?)

    @Query("UPDATE books SET coverFxPath = :path WHERE id = :id")
    suspend fun updateCoverFx(id: Long, path: String?)

    @Query("UPDATE books SET seriesName = :seriesName, seriesOrder = :seriesOrder WHERE id = :id")
    suspend fun updateSeriesInfo(id: Long, seriesName: String?, seriesOrder: Float?)

    // ── First-class series membership (seriesId is authoritative) ─────────────
    @Query("UPDATE books SET seriesId = :seriesId, seriesName = :seriesName, seriesOrder = :seriesOrder WHERE id = :id")
    suspend fun setSeriesMembership(id: Long, seriesId: Long?, seriesName: String?, seriesOrder: Float?)

    @Query("UPDATE books SET seriesOrder = :order WHERE id = :id")
    suspend fun setSeriesOrder(id: Long, order: Float?)

    @Query("SELECT * FROM books WHERE seriesId = :seriesId AND isIgnored = 0 ORDER BY seriesOrder ASC, title ASC")
    fun getBooksInSeriesById(seriesId: Long): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE seriesId = :seriesId AND isIgnored = 0 ORDER BY seriesOrder ASC, title ASC")
    suspend fun getBooksInSeriesByIdOnce(seriesId: Long): List<Book>

    /** Books whose effective author (authorOverride ?: author) matches [name] — used to delete a
     *  whole author from the library grid. */
    @Query("SELECT * FROM books WHERE COALESCE(authorOverride, author) = :name AND isIgnored = 0")
    suspend fun getBooksByEffectiveAuthorOnce(name: String): List<Book>

    // Match the effective author (authorOverride when set, else the scanned author).
    @Query("""
        SELECT * FROM books
        WHERE COALESCE(NULLIF(authorOverride, ''), author) = :author AND isIgnored = 0
        ORDER BY seriesName ASC, seriesOrder ASC, title ASC
    """)
    fun getBooksByAuthor(author: String): Flow<List<Book>>

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

    @Query("UPDATE books SET synopsis = :synopsis WHERE id = :id")
    suspend fun updateSynopsis(id: Long, synopsis: String)

    @Query("SELECT * FROM books WHERE id IN (:ids)")
    suspend fun getBooksByIds(ids: List<Long>): List<Book>

    @Query("UPDATE books SET skipSilenceEnabled = :enabled WHERE id = :id")
    suspend fun setSkipSilenceEnabled(id: Long, enabled: Boolean)

    @Transaction
    @Query("SELECT * FROM books WHERE isIgnored = 0 ORDER BY addedDateMs DESC")
    fun getAllBooksWithProgressUngrouped(): Flow<List<com.betteraudio.data.model.BookWithProgress>>

    // Home grid projection — never joins audio_files (see HomeGridBook's own doc comment).
    @Query("""
        SELECT b.id, b.title, b.titleOverride, b.author, b.authorOverride, b.ebookPath, b.seriesId,
               b.seriesName, b.seriesOrder, b.status, b.totalDurationMs, b.addedDateMs, b.coverArtPath,
               p.positionMs AS positionMs, p.lastPlayedMs AS lastPlayedMsRaw,
               p.textOverallFraction AS textOverallFraction, p.filesBeforeCurrentMs AS filesBeforeCurrentMs
        FROM books b LEFT JOIN playback_progress p ON p.bookId = b.id
        WHERE b.isIgnored = 0
        ORDER BY b.addedDateMs DESC
    """)
    fun getHomeGridBooks(): Flow<List<com.betteraudio.data.model.HomeGridBook>>

    @Query("SELECT EXISTS(SELECT 1 FROM books WHERE isIgnored = 0)")
    fun hasAnyBooks(): Flow<Boolean>

    @Query("SELECT * FROM books WHERE isIgnored = 1 ORDER BY title ASC")
    fun getAllIgnoredBooks(): Flow<List<Book>>

    @Query("UPDATE books SET isIgnored = :ignored WHERE id = :id")
    suspend fun setIgnored(id: Long, ignored: Boolean)

    @Query("UPDATE books SET titleOverride = :titleOverride, authorOverride = :authorOverride WHERE id = :id")
    suspend fun updateMetadata(id: Long, titleOverride: String?, authorOverride: String?)

    @Query("UPDATE books SET narrator = :narrator WHERE id = :id")
    suspend fun updateNarrator(id: Long, narrator: String?)

    @Query("SELECT * FROM books WHERE coverArtPath IS NOT NULL ORDER BY title ASC")
    suspend fun getAllBooksSortedOnce(): List<Book>

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Full library wipe. Cascades to audio_files, chapters, playback_progress, bookmarks,
    // listening_sessions, skip_events and book_group_members via their FKs.
    @Query("DELETE FROM books")
    suspend fun deleteAll()

    // ── Ebook (EPUB) support ─────────────────────────────────────────────────
    // Connecting/disconnecting an epub always nulls chapterMapJson — the audio↔spine alignment is
    // only valid for the epub it was computed against.
    @Query("UPDATE books SET ebookPath = :path, ebookSpineCount = :spineCount, chapterMapJson = NULL WHERE id = :id")
    suspend fun setEbook(id: Long, path: String?, spineCount: Int)

    /** Repoint an already-connected epub after an on-disk move — the file itself is unchanged, so
     *  (unlike [setEbook]) the chapter alignment map is preserved. */
    @Query("UPDATE books SET ebookPath = :path WHERE id = :id")
    suspend fun updateEbookPath(id: Long, path: String)

    @Query("UPDATE books SET ebookSpineCount = :spineCount WHERE id = :id")
    suspend fun updateEbookSpineCount(id: Long, spineCount: Int)

    @Query("UPDATE books SET chapterMapJson = :json WHERE id = :id")
    suspend fun setChapterMap(id: Long, json: String?)

    @Query("SELECT * FROM books WHERE ebookPath IS NOT NULL")
    suspend fun getAllWithEbookOnce(): List<Book>

    @Query("SELECT * FROM books WHERE ebookPath = :path LIMIT 1")
    suspend fun getBookByEbookPath(path: String): Book?
}
