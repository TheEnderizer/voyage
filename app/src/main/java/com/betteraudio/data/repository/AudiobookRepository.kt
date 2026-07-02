package com.betteraudio.data.repository

import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.AudioPresetDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.ListeningHistoryDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.covers.CoverEffectBaker
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudiobookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val progressDao: PlaybackProgressDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val audioPresetDao: AudioPresetDao,
    private val listeningHistoryDao: ListeningHistoryDao,
    private val bookGroupDao: com.betteraudio.data.db.dao.BookGroupDao,
    private val authorMetaDao: com.betteraudio.data.db.dao.AuthorMetaDao,
    private val coverEffectBaker: CoverEffectBaker
) {

    // ── Author view (lightweight per-author cover) ───────────────────────────
    fun getAllAuthorMeta(): kotlinx.coroutines.flow.Flow<List<com.betteraudio.data.db.entities.AuthorMeta>> =
        authorMetaDao.getAll()
    fun getBooksByAuthor(author: String): kotlinx.coroutines.flow.Flow<List<Book>> =
        bookDao.getBooksByAuthor(author)
    suspend fun setAuthorCover(name: String, path: String?) {
        val existing = authorMetaDao.getByName(name)
        authorMetaDao.upsert(
            (existing ?: com.betteraudio.data.db.entities.AuthorMeta(name = name)).copy(coverArtPath = path)
        )
    }

    /**
     * Wipe the entire library from the database — every book (which cascades to its files,
     * chapters, progress, bookmarks and listening history) and every playback group. The
     * audio files on disk are left untouched; the folder + import-structure settings are kept
     * so the user can immediately rescan. Custom audio presets are preserved.
     */
    suspend fun resetLibrary() {
        bookDao.deleteAll()
        bookGroupDao.deleteAllGroups()
    }

    // ── Listening history ────────────────────────────────────────────────────
    suspend fun insertListeningSession(session: ListeningSession): Long = listeningHistoryDao.insertSession(session)
    suspend fun finishListeningSession(
        id: Long, endMs: Long, endChapterIndex: Int, endChapterName: String,
        endPositionInChapterMs: Long, listenedMs: Long
    ) = listeningHistoryDao.finishSession(id, endMs, endChapterIndex, endChapterName, endPositionInChapterMs, listenedMs)
    fun getSessionsForBook(bookId: Long): Flow<List<ListeningSession>> = listeningHistoryDao.getSessionsForBook(bookId)
    suspend fun insertSkipEvent(skip: SkipEvent): Long = listeningHistoryDao.insertSkip(skip)
    fun getSkipsForBook(bookId: Long): Flow<List<SkipEvent>> = listeningHistoryDao.getSkipsForBook(bookId)
    suspend fun deleteHistoryForBook(bookId: Long) {
        listeningHistoryDao.deleteSessionsForBook(bookId)
        listeningHistoryDao.deleteSkipsForBook(bookId)
    }

    suspend fun setSkipSilenceEnabled(bookId: Long, enabled: Boolean) =
        bookDao.setSkipSilenceEnabled(bookId, enabled)

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
    /** Every book, including hidden/ignored ones — for the scanner's disk reconciliation. */
    suspend fun getAllBooksIncludingIgnoredOnce(): List<Book> = bookDao.getAllBooksOnce()
    /** Refresh a book's file-derived stats after files were dropped in reconciliation. */
    suspend fun updateBookFileStats(bookId: Long, totalDurationMs: Long, fileCount: Int) =
        bookDao.updateDuration(bookId, totalDurationMs, fileCount)
    suspend fun updateCoverArt(bookId: Long, path: String) {
        bookDao.updateCoverArt(bookId, path)
        // The cover changed: invalidate the baked effect so it's re-rendered. The UI
        // live-renders meanwhile and ensureCoverFx() re-bakes on the next view.
        bookDao.updateCoverFx(bookId, null)
        // Keep cover art out of the phone gallery
        val folder = java.io.File(path).parentFile
        if (folder != null) {
            val nomedia = java.io.File(folder, ".nomedia")
            if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        }
    }

    /** Bake the cover effect if a cover exists but no valid baked file is present yet. */
    suspend fun ensureCoverFx(bookId: Long) {
        val book = bookDao.getBookById(bookId).firstOrNull() ?: return
        val cover = book.coverArtPath ?: return
        val fx = book.coverFxPath
        if (fx != null && java.io.File(fx).exists()) return
        bookDao.updateCoverFx(bookId, coverEffectBaker.bake(cover, bookId))
    }

    /** Force a re-bake from the current cover (manual "refresh cover effect"). */
    suspend fun regenerateCoverFx(bookId: Long) {
        val book = bookDao.getBookById(bookId).firstOrNull() ?: return
        val cover = book.coverArtPath ?: return
        bookDao.updateCoverFx(bookId, coverEffectBaker.bake(cover, bookId))
    }
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

    suspend fun updateBoostDb(bookId: Long, boostDb: Int) {
        val existing = progressDao.getProgressForBookOnce(bookId)
        if (existing == null) {
            progressDao.upsert(PlaybackProgress(bookId = bookId, boostDb = boostDb))
        } else {
            progressDao.updateBoostDb(bookId, boostDb)
        }
    }

    suspend fun updateEqBands(bookId: Long, json: String?) {
        val existing = progressDao.getProgressForBookOnce(bookId)
        if (existing == null) {
            progressDao.upsert(PlaybackProgress(bookId = bookId, eqBandsJson = json))
        } else {
            progressDao.updateEqBands(bookId, json)
        }
    }

    suspend fun updateBookMetadata(bookId: Long, titleOverride: String?, authorOverride: String?) =
        bookDao.updateMetadata(bookId, titleOverride?.takeIf { it.isNotBlank() }, authorOverride?.takeIf { it.isNotBlank() })

    suspend fun setBookIgnored(bookId: Long, ignored: Boolean) = bookDao.setIgnored(bookId, ignored)

    fun getAllIgnoredBooks(): Flow<List<Book>> = bookDao.getAllIgnoredBooks()

    suspend fun deleteBook(bookId: Long, deleteFiles: Boolean) {
        if (deleteFiles) {
            val book = bookDao.getBookById(bookId).firstOrNull()
            if (book != null) {
                val folder = java.io.File(book.folderPath)
                if (folder.exists() && folder.isDirectory) folder.deleteRecursively()
            }
        }
        bookDao.deleteById(bookId)
    }

    /** Mark a book as just-played now (moves it to the top of last-played sorting immediately). */
    suspend fun touchLastPlayed(bookId: Long) {
        val now = System.currentTimeMillis()
        if (progressDao.touchLastPlayed(bookId, now) == 0) {
            progressDao.upsert(PlaybackProgress(bookId = bookId, lastPlayedMs = now))
        }
        bookDao.updateStatus(bookId, BookStatus.IN_PROGRESS)
    }

    suspend fun markBookFinished(bookId: Long) {
        progressDao.markCompleted(bookId, System.currentTimeMillis())
        bookDao.updateStatus(bookId, BookStatus.FINISHED)
    }

    suspend fun updateSynopsis(bookId: Long, synopsis: String) = bookDao.updateSynopsis(bookId, synopsis)

    suspend fun getBooksByIds(ids: List<Long>): List<Book> = bookDao.getBooksByIds(ids)
    suspend fun getAllUngroupedOnce(): List<Book> = bookDao.getAllUngroupedOnce()

    /** Lock these books' grouping so the scanner's AutoJoiner never touches them again. */
    suspend fun markManualGrouping(ids: List<Long>) = bookDao.markManualGrouping(ids)

    /**
     * One-time cleanup of legacy auto-sliced "synthetic" chapters. Clearing all chapter rows
     * for affected books makes the next scan rebuild them via the (now synthetic-free)
     * chapter builder — embedded markers where present, else one chapter per file.
     */
    suspend fun purgeSyntheticChapters() {
        chapterDao.bookIdsWithSyntheticChapters().forEach { bookId ->
            chapterDao.deleteForBook(bookId)
        }
    }

    // ── Bookmarks ────────────────────────────────────────────────────────────
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>> = bookmarkDao.getForBook(bookId)
    suspend fun addBookmark(bookmark: Bookmark): Long = bookmarkDao.insert(bookmark)
    suspend fun deleteBookmark(id: Long) = bookmarkDao.deleteById(id)

    // ── Audio presets ─────────────────────────────────────────────────────────
    fun getAllAudioPresets(): Flow<List<AudioPreset>> = audioPresetDao.getAll()
    fun getAudioPresetsByType(type: String): Flow<List<AudioPreset>> = audioPresetDao.getByType(type)
    suspend fun insertAudioPreset(preset: AudioPreset): Long = audioPresetDao.insert(preset)
    suspend fun updateAudioPreset(preset: AudioPreset) = audioPresetDao.update(preset)
    suspend fun deleteAudioPreset(id: Long) = audioPresetDao.deleteById(id)
    suspend fun setDefaultAudioPreset(id: Long) {
        audioPresetDao.clearDefault()
        audioPresetDao.setDefault(id)
    }
    suspend fun getProgressForBookOnce(bookId: Long): PlaybackProgress? =
        progressDao.getProgressForBookOnce(bookId)

    suspend fun updateLastPausedAt(bookId: Long, ts: Long) {
        if (progressDao.getProgressForBookOnce(bookId) == null) {
            progressDao.upsert(PlaybackProgress(bookId = bookId, lastPausedAt = ts))
        } else {
            progressDao.updateLastPausedAt(bookId, ts)
        }
    }

    /** Re-bake the cover effect for every book that has a cover. */
    suspend fun regenerateAllCoverFx() {
        bookDao.getAllBooksSortedOnce()
            .filter { it.coverArtPath != null }
            .forEach { book ->
                bookDao.updateCoverFx(book.id, coverEffectBaker.bake(book.coverArtPath!!, book.id))
            }
    }
}
