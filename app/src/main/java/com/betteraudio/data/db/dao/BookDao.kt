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

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookOnce(id: Long): Book?

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

    // ── First-class series membership (seriesId is authoritative) ─────────────
    // There is deliberately no cache-only "UPDATE books SET seriesName/seriesOrder" writer
    // here: one existed, and it was what Book Options' series field called — it set the label
    // without ever resolving a Series row, so the book never actually joined anything.
    // Membership always goes through setSeriesMembership, which writes seriesId and its cache
    // together. See SeriesRepository.setBookSeriesByName.
    @Query("UPDATE books SET seriesId = :seriesId, seriesName = :seriesName, seriesOrder = :seriesOrder WHERE id = :id")
    suspend fun setSeriesMembership(id: Long, seriesId: Long?, seriesName: String?, seriesOrder: Float?)

    @Query("UPDATE books SET seriesOrder = :order WHERE id = :id")
    suspend fun setSeriesOrder(id: Long, order: Float?)

    /** Members regardless of [Book.isIgnored] — the "is this series still in use?" test, which a
     *  hidden book must still pass (unlike getBooksInSeriesByIdOnce, which filters them out). */
    @Query("SELECT COUNT(*) FROM books WHERE seriesId = :seriesId")
    suspend fun countSeriesMembers(seriesId: Long): Int

    /** Books carrying a cached series name that resolves to no Series row — the wreckage left by
     *  the cache-only writer described above. Ignored books included: they keep their series when
     *  unhidden. */
    @Query("SELECT * FROM books WHERE seriesId IS NULL AND seriesName IS NOT NULL AND TRIM(seriesName) <> '' ORDER BY id ASC")
    suspend fun getBooksWithOrphanedSeriesNameOnce(): List<Book>

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
        SELECT b.id, b.title, b.titleOverride, b.author, b.authorOverride, b.seriesId,
               b.seriesName, b.seriesOrder, b.status, b.totalDurationMs, b.addedDateMs, b.coverArtPath,
               p.positionMs AS positionMs, p.lastPlayedMs AS lastPlayedMsRaw,
               p.filesBeforeCurrentMs AS filesBeforeCurrentMs
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
    suspend fun getBooksWithCoversOnce(): List<Book>

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Full library wipe. Cascades to audio_files, chapters, playback_progress, bookmarks,
    // listening_sessions and skip_events via their FKs.
    @Query("DELETE FROM books")
    suspend fun deleteAll()

    /** Stamped after a scan-time MERGE apply from data/book.json — see RestoreOps/AudioFileScanner
     *  and Book.dataAppliedAtMs's own doc comment for why this exists instead of comparing the
     *  doc's mtime to lastPlayedMs. */
    @Query("UPDATE books SET dataAppliedAtMs = :ts WHERE id = :id")
    suspend fun updateDataAppliedAt(id: Long, ts: Long)
}
