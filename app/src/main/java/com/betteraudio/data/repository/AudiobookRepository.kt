package com.betteraudio.data.repository

import androidx.room.withTransaction
import com.betteraudio.data.db.AppDatabase
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
    private val db: AppDatabase,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val progressDao: PlaybackProgressDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
    private val audioPresetDao: AudioPresetDao,
    private val listeningHistoryDao: ListeningHistoryDao,
    private val authorMetaDao: com.betteraudio.data.db.dao.AuthorMetaDao,
    private val syncAnchorDao: com.betteraudio.data.db.dao.SyncAnchorDao,
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
    suspend fun getAllAuthorMetaOnce(): List<com.betteraudio.data.db.entities.AuthorMeta> = authorMetaDao.getAllOnce()
    suspend fun upsertAuthorMeta(meta: com.betteraudio.data.db.entities.AuthorMeta) = authorMetaDao.upsert(meta)

    /**
     * Wipe the entire library from the database — every book (which cascades to its files,
     * chapters, progress, bookmarks and listening history). The audio files on disk are left
     * untouched; the folder + import-structure settings are kept so the user can immediately
     * rescan. Custom audio presets are preserved.
     */
    suspend fun resetLibrary() {
        bookDao.deleteAll()
    }

    // ── Listening history ────────────────────────────────────────────────────
    suspend fun insertListeningSession(session: ListeningSession): Long = listeningHistoryDao.insertSession(session)
    fun getSessionsForBook(bookId: Long): Flow<List<ListeningSession>> = listeningHistoryDao.getSessionsForBook(bookId)
    suspend fun insertSkipEvent(skip: SkipEvent): Long = listeningHistoryDao.insertSkip(skip)
    /** Insert then prune older rows of the same [SkipEvent.source] for that book beyond [keep]. */
    suspend fun insertSkipEventPruned(skip: SkipEvent, keep: Int) {
        listeningHistoryDao.insertSkip(skip)
        listeningHistoryDao.pruneSkipsBySource(skip.bookId, skip.source, keep)
    }
    fun getSkipsForBook(bookId: Long): Flow<List<SkipEvent>> = listeningHistoryDao.getSkipsForBook(bookId)

    suspend fun setSkipSilenceEnabled(bookId: Long, enabled: Boolean) =
        bookDao.setSkipSilenceEnabled(bookId, enabled)

    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooksSorted()
    fun getBookById(bookId: Long): Flow<Book?> = bookDao.getBookById(bookId)
    suspend fun getBookOnce(bookId: Long): Book? = bookDao.getBookOnce(bookId)
    fun getBookWithProgress(bookId: Long): Flow<BookWithProgress?> = bookDao.getBookWithProgress(bookId)
    fun getHomeGridBooks(): Flow<List<com.betteraudio.data.model.HomeGridBook>> = bookDao.getHomeGridBooks()
    fun hasAnyBooks(): Flow<Boolean> = bookDao.hasAnyBooks()
    fun getChaptersForBook(bookId: Long): Flow<List<Chapter>> = chapterDao.getChaptersForBook(bookId)
    suspend fun getChaptersForBookOnce(bookId: Long): List<Chapter> = chapterDao.getChaptersForBookOnce(bookId)
    suspend fun chapterCountForBook(bookId: Long): Int = chapterDao.countForBook(bookId)
    suspend fun replaceChapters(bookId: Long, chapters: List<Chapter>) {
        db.withTransaction {
            chapterDao.deleteForBook(bookId)
            chapterDao.insertAll(chapters)
        }
    }

    /** Exposes a DB transaction to callers (e.g. the scanner) that need several repository
     *  calls to commit atomically — a killed process must never see files/chapters half applied. */
    suspend fun <T> withTransaction(block: suspend () -> T): T = db.withTransaction { block() }
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
    /** Repoint a book + its files to a new on-disk location (library restructure move). */
    suspend fun updateBookLocation(bookId: Long, folderPath: String, coverArtPath: String?) =
        bookDao.updateLocation(bookId, folderPath, coverArtPath)
    suspend fun updateAudioFilePath(fileId: Long, path: String) = audioFileDao.updatePath(fileId, path)
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
        val book = bookDao.getBookOnce(bookId) ?: return
        val cover = book.coverArtPath ?: return
        val fx = book.coverFxPath
        if (fx != null && java.io.File(fx).exists()) return
        bookDao.updateCoverFx(bookId, coverEffectBaker.bake(cover, bookId.toString()))
    }

    /** Force a re-bake from the current cover (manual "refresh cover effect"). */
    suspend fun regenerateCoverFx(bookId: Long) {
        val book = bookDao.getBookOnce(bookId) ?: return
        val cover = book.coverArtPath ?: return
        bookDao.updateCoverFx(bookId, coverEffectBaker.bake(cover, bookId.toString()))
    }
    suspend fun updateBookStatus(bookId: Long, status: BookStatus) = bookDao.updateStatus(bookId, status)
    suspend fun updateSeriesInfo(bookId: Long, seriesName: String?, seriesOrder: Float?) =
        bookDao.updateSeriesInfo(bookId, seriesName, seriesOrder)

    // Six read-then-write "upserts" (this one plus updateSpeed/updateBoostDb/updateEqBands/
    // updateLastPausedAt/touchLastPlayed below) each wrap their check-then-write in a single
    // transaction. Without it, the service's periodic saver and PlayerController's own save path
    // — which genuinely do overlap — could both read "no row exists" and both insert, and the
    // loser's insert (OnConflictStrategy.REPLACE) becomes a delete-then-insert that drops
    // whichever other column the other writer had just set.
    suspend fun updatePosition(bookId: Long, fileId: Long, positionMs: Long) {
        db.withTransaction {
            val existing = progressDao.getProgressForBookOnce(bookId)
            // Only recompute on an actual file change — cheap relative to how often this is now
            // called after G2-3, but no need to redo it on every same-file position tick.
            val filesBeforeCurrentMs = if (existing?.currentFileId == fileId) {
                existing.filesBeforeCurrentMs
            } else {
                // Natural id (insertion) order, not trackNumber/fileName — matches what
                // BookWithProgress.progressFraction actually computes (its @Relation has no
                // explicit ORDER BY) and what the MIGRATION_19_20 backfill uses; see its comment
                // for why trackNumber/fileName is the wrong key for a disc-merged book.
                audioFileDao.getFilesForBookOnce(bookId)
                    .sortedBy { it.id }
                    .takeWhile { it.id != fileId }
                    .sumOf { it.durationMs }
            }
            if (existing == null) {
                progressDao.upsert(
                    PlaybackProgress(
                        bookId = bookId,
                        currentFileId = fileId,
                        positionMs = positionMs,
                        lastPlayedMs = System.currentTimeMillis(),
                        filesBeforeCurrentMs = filesBeforeCurrentMs
                    )
                )
            } else {
                progressDao.updatePosition(bookId, fileId, positionMs, System.currentTimeMillis(), filesBeforeCurrentMs)
            }
            bookDao.updateStatus(bookId, BookStatus.IN_PROGRESS)
        }
    }

    suspend fun updateSpeed(bookId: Long, speed: Float) {
        db.withTransaction {
            val existing = progressDao.getProgressForBookOnce(bookId)
            if (existing == null) {
                progressDao.upsert(PlaybackProgress(bookId = bookId, playbackSpeed = speed))
            } else {
                progressDao.updateSpeed(bookId, speed)
            }
        }
    }

    suspend fun updateBoostDb(bookId: Long, boostDb: Int) {
        db.withTransaction {
            val existing = progressDao.getProgressForBookOnce(bookId)
            if (existing == null) {
                progressDao.upsert(PlaybackProgress(bookId = bookId, boostDb = boostDb))
            } else {
                progressDao.updateBoostDb(bookId, boostDb)
            }
        }
    }

    suspend fun updateEqBands(bookId: Long, json: String?) {
        db.withTransaction {
            val existing = progressDao.getProgressForBookOnce(bookId)
            if (existing == null) {
                progressDao.upsert(PlaybackProgress(bookId = bookId, eqBandsJson = json))
            } else {
                progressDao.updateEqBands(bookId, json)
            }
        }
    }

    suspend fun updateBookMetadata(bookId: Long, titleOverride: String?, authorOverride: String?) =
        bookDao.updateMetadata(bookId, titleOverride?.takeIf { it.isNotBlank() }, authorOverride?.takeIf { it.isNotBlank() })

    suspend fun updateBookNarrator(bookId: Long, narrator: String?) = bookDao.updateNarrator(bookId, narrator)

    suspend fun setBookIgnored(bookId: Long, ignored: Boolean) = bookDao.setIgnored(bookId, ignored)

    fun getAllIgnoredBooks(): Flow<List<Book>> = bookDao.getAllIgnoredBooks()

    suspend fun deleteBook(bookId: Long, deleteFiles: Boolean) {
        if (deleteFiles) {
            val book = bookDao.getBookOnce(bookId)
            if (book != null) {
                val folder = java.io.File(book.folderPath)
                if (folder.exists() && folder.isDirectory) folder.deleteRecursively()
            }
        }
        bookDao.deleteById(bookId)
    }

    /** All books by an effective author name (for deleting a whole author from the grid). */
    suspend fun getBooksByEffectiveAuthorOnce(name: String): List<com.betteraudio.data.db.entities.Book> =
        bookDao.getBooksByEffectiveAuthorOnce(name)

    /** Remove an author's cover-meta row (after its books are deleted). */
    suspend fun deleteAuthorMeta(name: String) = authorMetaDao.deleteByName(name)

    // ── Ebook (EPUB) support ─────────────────────────────────────────────────

    /** Connect (or disconnect, path = null) an epub to an existing book. Always clears the stale
     *  chapter-alignment map AND the forced-alignment anchors — both are specific to the epub they
     *  were computed against. */
    suspend fun setEbook(bookId: Long, path: String?, spineCount: Int) {
        bookDao.setEbook(bookId, path, spineCount)   // also nulls chapterMapJson
        syncAnchorDao.deleteForBook(bookId)
    }

    // ── Sync anchors (paragraph-resolution alignment points) ──────────────────
    suspend fun getSyncAnchorsOnce(bookId: Long): List<com.betteraudio.data.db.entities.SyncAnchor> =
        syncAnchorDao.getForBookOnce(bookId)
    fun syncAnchorCount(bookId: Long): kotlinx.coroutines.flow.Flow<Int> = syncAnchorDao.countForBook(bookId)
    suspend fun insertSyncAnchors(anchors: List<com.betteraudio.data.db.entities.SyncAnchor>) =
        syncAnchorDao.insertAll(anchors)
    suspend fun deleteSyncAnchors(bookId: Long) = syncAnchorDao.deleteForBook(bookId)

    suspend fun updateEbookSpineCount(bookId: Long, spineCount: Int) =
        bookDao.updateEbookSpineCount(bookId, spineCount)

    /** Repoint a connected epub's path after a library-restructure move (keeps the chapter map). */
    suspend fun updateEbookPath(bookId: Long, path: String) = bookDao.updateEbookPath(bookId, path)

    suspend fun setChapterMap(bookId: Long, json: String?) = bookDao.setChapterMap(bookId, json)

    /** Every book with a connected/standalone ebook (for reconciliation and the Ebooks view). */
    suspend fun getAllWithEbookOnce(): List<Book> = bookDao.getAllWithEbookOnce()

    suspend fun getBookByEbookPath(path: String): Book? = bookDao.getBookByEbookPath(path)

    /** Upsert a standalone ebook-only Book row keyed by its synthetic `::epub::` folderPath —
     *  create on first scan, refresh spine count (never clobber user overrides) on later scans. */
    suspend fun upsertEbookOnlyBook(
        folderPath: String, title: String, author: String, coverArtPath: String?, ebookPath: String,
        spineCount: Int
    ): Long {
        val existing = bookDao.getBookByFolder(folderPath)
        return if (existing != null) {
            bookDao.updateEbookSpineCount(existing.id, spineCount)
            existing.id
        } else {
            bookDao.upsert(
                Book(
                    title = title, author = author, folderPath = folderPath,
                    coverArtPath = coverArtPath, ebookPath = ebookPath, ebookSpineCount = spineCount,
                    fileCount = 0, totalDurationMs = 0
                )
            )
        }
    }

    /** Persist the reader's scroll position and mark text as the freshest mode. Creates the
     *  progress row on first read, mirroring [touchLastPlayed]'s upsert-if-missing pattern. */
    suspend fun updateTextPosition(bookId: Long, spineIndex: Int, fraction: Float, overallFraction: Float) {
        val now = System.currentTimeMillis()
        if (progressDao.updateTextPosition(bookId, spineIndex, fraction, overallFraction, now) == 0) {
            progressDao.upsert(
                PlaybackProgress(
                    bookId = bookId, textSpineIndex = spineIndex, textFraction = fraction,
                    textOverallFraction = overallFraction, lastMode = "TEXT", lastPlayedMs = now
                )
            )
        }
    }

    /** Mark audio as the freshest mode (called when starting/resuming playback from the reader or
     *  the normal player, so the next mode-switch converts FROM the audio position). */
    suspend fun setLastModeAudio(bookId: Long) {
        if (progressDao.setLastModeAudio(bookId) == 0) {
            progressDao.upsert(PlaybackProgress(bookId = bookId, lastMode = "AUDIO"))
        }
    }

    /** When a standalone ebook-only row is being merged into a newly-connected audiobook, carry
     *  its reading progress AND its reading/listening history over — this is what makes the two
     *  books' history "merged" once linked, rather than the standalone row's history being lost to
     *  cascade delete when it's removed. The *position* carry-over only applies if the audiobook
     *  doesn't already have its own (a book already being read/listened to keeps its own position),
     *  but history (skip events, listening sessions) is always reassigned regardless. */
    suspend fun mergeStandaloneEbookProgress(fromBookId: Long, toBookId: Long) {
        val existingTarget = progressDao.getProgressForBookOnce(toBookId)
        if (existingTarget?.textSpineIndex == null) {
            val source = progressDao.getProgressForBookOnce(fromBookId)
            source?.textSpineIndex?.let { spine ->
                updateTextPosition(toBookId, spine, source.textFraction ?: 0f, source.textOverallFraction)
            }
        }
        listeningHistoryDao.reassignSkipsToBook(fromBookId, toBookId)
        listeningHistoryDao.reassignSessionsToBook(fromBookId, toBookId)
    }

    /** Mark a book as just-played now (moves it to the top of last-played sorting immediately). */
    suspend fun touchLastPlayed(bookId: Long) {
        db.withTransaction {
            val now = System.currentTimeMillis()
            if (progressDao.touchLastPlayed(bookId, now) == 0) {
                progressDao.upsert(PlaybackProgress(bookId = bookId, lastPlayedMs = now))
            }
            bookDao.updateStatus(bookId, BookStatus.IN_PROGRESS)
        }
    }

    suspend fun markBookFinished(bookId: Long) {
        progressDao.markCompleted(bookId, System.currentTimeMillis())
        bookDao.updateStatus(bookId, BookStatus.FINISHED)
    }

    suspend fun updateSynopsis(bookId: Long, synopsis: String) = bookDao.updateSynopsis(bookId, synopsis)

    suspend fun getBooksByIds(ids: List<Long>): List<Book> = bookDao.getBooksByIds(ids)

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
    suspend fun insertAudioPreset(preset: AudioPreset): Long = audioPresetDao.insert(preset)
    suspend fun updateAudioPreset(preset: AudioPreset) = audioPresetDao.update(preset)
    suspend fun deleteAudioPreset(id: Long) = audioPresetDao.deleteById(id)
    suspend fun setDefaultAudioPreset(id: Long) {
        db.withTransaction {
            audioPresetDao.clearDefault()
            audioPresetDao.setDefault(id)
        }
    }
    /** The global default preset (applied to every book unless the book overrides it), or null. */
    suspend fun getDefaultAudioPreset(): AudioPreset? = audioPresetDao.getDefault()
    /** Clear the global default flag without deleting any preset. */
    suspend fun clearDefaultAudioPreset() = audioPresetDao.clearDefault()
    suspend fun getProgressForBookOnce(bookId: Long): PlaybackProgress? =
        progressDao.getProgressForBookOnce(bookId)

    suspend fun updateLastPausedAt(bookId: Long, ts: Long) {
        db.withTransaction {
            if (progressDao.getProgressForBookOnce(bookId) == null) {
                progressDao.upsert(PlaybackProgress(bookId = bookId, lastPausedAt = ts))
            } else {
                progressDao.updateLastPausedAt(bookId, ts)
            }
        }
    }

    /** Re-bake the cover effect for every book that has a cover. */
    suspend fun regenerateAllCoverFx() {
        bookDao.getAllBooksSortedOnce()
            .filter { it.coverArtPath != null }
            .forEach { book ->
                bookDao.updateCoverFx(book.id, coverEffectBaker.bake(book.coverArtPath!!, book.id.toString()))
            }
    }
}
