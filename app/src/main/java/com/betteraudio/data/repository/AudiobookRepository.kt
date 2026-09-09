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
import com.betteraudio.data.diskstore.DiskMirror
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.AuthorMeta
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val coverEffectBaker: CoverEffectBaker,
    private val diskMirror: DiskMirror
) {

    // ── Author view (lightweight per-author cover) ───────────────────────────
    fun getAllAuthorMeta(): Flow<List<AuthorMeta>> =
        authorMetaDao.getAll()
    fun getBooksByAuthor(author: String): Flow<List<Book>> =
        bookDao.getBooksByAuthor(author)
    suspend fun setAuthorCover(name: String, path: String?) {
        val existing = authorMetaDao.getByName(name)
        authorMetaDao.upsert(
            (existing ?: AuthorMeta(name = name)).copy(coverArtPath = path)
        )
        diskMirror.flushLibrary()
    }
    suspend fun getAllAuthorMetaOnce(): List<AuthorMeta> = authorMetaDao.getAllOnce()
    suspend fun upsertAuthorMeta(meta: AuthorMeta) {
        authorMetaDao.upsert(meta)
        diskMirror.flushLibrary()
    }

    /**
     * Wipe the entire library from the database — every book (which cascades to its files,
     * chapters, progress, bookmarks and listening history). The audio files on disk are left
     * untouched; the folder + import-structure settings are kept so the user can immediately
     * rescan. Custom audio presets are preserved. Book-level disk data is deliberately left
     * alone — under the disk-first model a rescan restores it, which is the point.
     */
    suspend fun resetLibrary() {
        bookDao.deleteAll()
        diskMirror.flushLibrary()
    }

    // ── Listening history ────────────────────────────────────────────────────
    // Raw insert — used by BackupManager's restore, which must not prune away sessions it's in
    // the middle of restoring one at a time.
    suspend fun insertListeningSession(session: ListeningSession): Long =
        listeningHistoryDao.insertSession(session).also { diskMirror.markDirty(session.bookId) }
    /** Insert then prune older sessions for that book beyond [keep]. Used by the live recorder
     *  (PlayerController.closeHistorySession) — the only place a session is appended one at a time
     *  outside of a bulk restore. */
    suspend fun insertListeningSessionPruned(session: ListeningSession, keep: Int) {
        listeningHistoryDao.insertSession(session)
        listeningHistoryDao.pruneSessionsForBook(session.bookId, keep)
        diskMirror.markDirty(session.bookId)
    }
    fun getSessionsForBook(bookId: Long): Flow<List<ListeningSession>> = listeningHistoryDao.getSessionsForBook(bookId)
    suspend fun insertSkipEvent(skip: SkipEvent): Long =
        listeningHistoryDao.insertSkip(skip).also { diskMirror.markDirty(skip.bookId) }
    /** Insert then prune older rows of the same [SkipEvent.source] for that book beyond [keep]. */
    suspend fun insertSkipEventPruned(skip: SkipEvent, keep: Int) {
        listeningHistoryDao.insertSkip(skip)
        listeningHistoryDao.pruneSkipsBySource(skip.bookId, skip.source, keep)
        diskMirror.markDirty(skip.bookId)
    }
    fun getSkipsForBook(bookId: Long): Flow<List<SkipEvent>> = listeningHistoryDao.getSkipsForBook(bookId)

    suspend fun setSkipSilenceEnabled(bookId: Long, enabled: Boolean) {
        bookDao.setSkipSilenceEnabled(bookId, enabled)
        diskMirror.flushBook(bookId)
    }

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
    suspend fun deleteAudioFilesByIds(ids: List<Long>) {
        if (ids.isNotEmpty()) audioFileDao.deleteFilesByIds(ids)
    }
    suspend fun getAudioFilesOnce(bookId: Long): List<AudioFile> = audioFileDao.getFilesForBookOnce(bookId)
    /** Every audio file in the DB, for the scanner's disk reconciliation (batched — one query
     *  for the whole library instead of one per book). */
    suspend fun getAllAudioFilesOnce(): List<AudioFile> = audioFileDao.getAllFilesOnce()
    suspend fun saveProgress(progress: PlaybackProgress) {
        progressDao.upsert(progress)
        diskMirror.markDirty(progress.bookId)
    }
    suspend fun getBookByFolder(folderPath: String): Book? = bookDao.getBookByFolder(folderPath)
    /** Every book, including hidden/ignored ones — for the scanner's disk reconciliation. */
    suspend fun getAllBooksIncludingIgnoredOnce(): List<Book> = bookDao.getAllBooksOnce()
    /** Repoint a book + its files to a new on-disk location (library restructure move). */
    suspend fun updateBookLocation(bookId: Long, folderPath: String, coverArtPath: String?) {
        bookDao.updateLocation(bookId, folderPath, coverArtPath)
        diskMirror.flushBook(bookId)
    }
    suspend fun updateAudioFilePath(fileId: Long, path: String) = audioFileDao.updatePath(fileId, path)

    /** Caches an [com.betteraudio.playback.Mp3DamageScanner] result so a file is scanned once, not once per play. */
    suspend fun updateAudioFileDamageRanges(fileId: Long, ranges: String) {
        audioFileDao.updateDamageRanges(fileId, ranges)
        audioFileDao.getFileById(fileId)?.bookId?.let { diskMirror.flushBook(it) }
    }
    /** Refresh a book's file-derived stats after files were dropped in reconciliation. */
    suspend fun updateBookFileStats(bookId: Long, totalDurationMs: Long, fileCount: Int) {
        bookDao.updateDuration(bookId, totalDurationMs, fileCount)
        diskMirror.flushBook(bookId)
    }
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
        diskMirror.flushBook(bookId)
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
    suspend fun updateBookStatus(bookId: Long, status: BookStatus) {
        bookDao.updateStatus(bookId, status)
        diskMirror.flushBook(bookId)
    }
    // Series membership deliberately does NOT live here: it needs a resolved Series row, not just
    // the cached columns on the book. See SeriesRepository.setBookSeriesByName, which every caller
    // of the old updateSeriesInfo now uses.

    // Six read-then-write "upserts" (this one plus updateSpeed/updateBoostDb/updateEqBands/
    // updateLastPausedAt/touchLastPlayed below) each wrap their check-then-write in a single
    // transaction. Without it, the service's periodic saver and PlayerController's own save path
    // — which genuinely do overlap — could both read "no row exists" and both insert, and the
    // loser's insert (OnConflictStrategy.REPLACE) becomes a delete-then-insert that drops
    // whichever other column the other writer had just set.
    /**
     * Is [bookPositionMs] (book-global) within [COMPLETION_TAIL_MS] of this book's end? Only ever
     * asked of a book already flagged complete, so the extra read costs nothing on the common
     * path. A book with no scanned duration answers false: there is no end to be near, and the
     * safe answer for an unknown is the old, un-finishing behaviour.
     */
    private suspend fun isAtEndOfBook(bookId: Long, bookPositionMs: Long): Boolean {
        val totalMs = bookDao.getBookOnce(bookId)?.totalDurationMs ?: 0L
        return totalMs > 0L && bookPositionMs >= totalMs - COMPLETION_TAIL_MS
    }

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
                bookDao.updateStatus(bookId, BookStatus.IN_PROGRESS)
            } else if (existing.isCompleted && isAtEndOfBook(bookId, filesBeforeCurrentMs + positionMs)) {
                // A position save is normally proof the book is being listened to again, so both
                // writes below un-finish it: the flag is cleared and the status drops back to
                // IN_PROGRESS. The one place that inference is wrong is the end of the book.
                // PlayerController's STATE_ENDED handler marks the book complete, and the save
                // that flushes the final position runs a moment later — unguarded, it undid the
                // completion it was racing, every single time. That is the whole cause of three
                // reported bugs at once: the book stayed off the Finished shelf, kept a
                // currentFileId pointing at its last file, and so resumed there (or, once
                // AudioCascade.resolveStart's isCompleted branch was skipped, at whatever old
                // chapter that file began) instead of at its beginning.
                //
                // "At the end" rather than "immediately after STATE_ENDED" deliberately: the two
                // writers are unordered, so a rule about which came first cannot be evaluated by
                // either of them. Position can, from either side, and it answers the question the
                // flag actually asks. A genuine resume of a finished book is not caught by it —
                // resolveStart sends that book back to file 0 / position 0, about as far from
                // this test as a position gets — and a listener who seeks into the last minute of
                // a book they have finished has not un-finished it either.
                progressDao.updatePositionKeepingCompletion(bookId, fileId, positionMs, System.currentTimeMillis(), filesBeforeCurrentMs)
            } else {
                progressDao.updatePosition(bookId, fileId, positionMs, System.currentTimeMillis(), filesBeforeCurrentMs)
                bookDao.updateStatus(bookId, BookStatus.IN_PROGRESS)
            }
        }
        // Deferred, not flushed: the disk mirror for playback position is intentionally written
        // only on pause/stop/book-close/file-transition, not on every position tick — see
        // DiskMirror's flush trigger sites in PlaybackService/PlayerController.
        diskMirror.markDirty(bookId)
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
        diskMirror.markDirty(bookId)
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
        diskMirror.markDirty(bookId)
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
        diskMirror.markDirty(bookId)
    }

    // ── Companion packs (docs/companion-packs.md §6) ─────────────────────────
    // revealedMs is written from PlaybackService's position-saver tick, the same continuous
    // "high-frequency, deferred" cadence as updatePosition above — markDirty, not flushBook.
    suspend fun getRevealedMs(bookId: Long): Long = progressDao.getRevealedMs(bookId) ?: 0L

    suspend fun updateRevealedMs(bookId: Long, revealedMs: Long) {
        db.withTransaction {
            val existing = progressDao.getProgressForBookOnce(bookId)
            if (existing == null) {
                progressDao.upsert(PlaybackProgress(bookId = bookId, revealedMs = revealedMs))
            } else {
                progressDao.updateRevealedMs(bookId, revealedMs)
            }
        }
        diskMirror.markDirty(bookId)
    }

    /** A deliberate, rare, user-confirmed action (§6.3's "set reveal point", or the "start fresh"
     *  option on companion-pack import, §10.1) — flushed immediately, unlike [updateRevealedMs]'s
     *  tick writes, matching every other one-off user edit in this repository. */
    suspend fun resetProgress(bookId: Long) {
        db.withTransaction { progressDao.resetProgress(bookId) }
        diskMirror.flushBook(bookId)
    }

    suspend fun updateBookMetadata(bookId: Long, titleOverride: String?, authorOverride: String?) {
        bookDao.updateMetadata(bookId, titleOverride?.takeIf { it.isNotBlank() }, authorOverride?.takeIf { it.isNotBlank() })
        diskMirror.flushBook(bookId)
    }

    suspend fun updateBookNarrator(bookId: Long, narrator: String?) {
        bookDao.updateNarrator(bookId, narrator)
        diskMirror.flushBook(bookId)
    }

    suspend fun setBookIgnored(bookId: Long, ignored: Boolean) {
        bookDao.setIgnored(bookId, ignored)
        diskMirror.flushBook(bookId)
    }

    fun getAllIgnoredBooks(): Flow<List<Book>> = bookDao.getAllIgnoredBooks()

    suspend fun deleteBook(bookId: Long, deleteFiles: Boolean) {
        val book = bookDao.getBookOnce(bookId)
        if (book == null) {
            AppLog.w(LogCat.DB, "deleteBook: book=$bookId not found")
            return
        }
        if (deleteFiles) {
            val folder = java.io.File(book.folderPath)
            val folderDeleted = folder.exists() && folder.isDirectory
            if (folderDeleted) folder.deleteRecursively()
            AppLog.i(LogCat.DB, "deleteBook: book=$bookId '${book.title}' deleteFiles=true folderDeleted=$folderDeleted")
        } else {
            AppLog.i(LogCat.DB, "deleteBook: book=$bookId '${book.title}' deleteFiles=false (removed from app, audio left on disk)")
        }
        bookDao.deleteById(bookId)
        // The baked blur/reflection composite lives in filesDir/cover_fx regardless of
        // deleteFiles (it's a derived file, not part of the user's own audio folder), so nothing
        // else deletes it — a book removed from the app otherwise leaves it behind permanently.
        book.coverFxPath?.let { deleteQuietly(it, "book $bookId coverFx") }
        // In BOTH branches, not just !deleteFiles: when deleteFiles is true the folder.
        // deleteRecursively() above already took data/ with it, so this is a no-op there, but it
        // still needs to clear DiskMirror's dirty-set entry either way. When deleteFiles is
        // false, this is load-bearing — without it, a book removed from the app but left on disk
        // would come back on the next rescan with all its old progress and overrides, which is
        // strictly worse than today's "the audio is still there so it comes back fresh".
        diskMirror.deleteBookData(book)
    }

    /** All books by an effective author name (for deleting a whole author from the grid). */
    suspend fun getBooksByEffectiveAuthorOnce(name: String): List<Book> =
        bookDao.getBooksByEffectiveAuthorOnce(name)

    /** Remove an author's cover-meta row (after its books are deleted), and the cover files it
     *  owned — `.voyage/covers/author_<slug>.jpg` (or legacy filesDir for a cover set before this
     *  moved), plus its baked coverFx; an author has no folder of its own to keep either in. */
    suspend fun deleteAuthorMeta(name: String) {
        val meta = authorMetaDao.getByName(name)
        authorMetaDao.deleteByName(name)
        meta?.coverArtPath?.let { deleteQuietly(it, "author '$name' cover") }
        meta?.coverFxPath?.let { deleteQuietly(it, "author '$name' coverFx") }
        diskMirror.flushLibrary()
    }

    companion object {
        /** How close to a book's end still counts as "the end" for [isAtEndOfBook]. Sized for the
         *  gap between STATE_ENDED and the stop-flush save that follows it, which is a fraction of
         *  a second in practice; the slack is for a last file whose scanned duration is slightly
         *  long, the same tolerance PlayerController applies when judging a truncated item. */
        private const val COMPLETION_TAIL_MS = 15_000L

        internal fun deleteQuietly(path: String, what: String) {
            try {
                val f = java.io.File(path)
                if (f.exists() && f.delete()) AppLog.i(LogCat.DB, "deleted orphaned $what: $path")
            } catch (e: Exception) {
                AppLog.e(LogCat.DB, "failed to delete orphaned $what: $path", e)
            }
        }
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
        diskMirror.markDirty(bookId)
    }

    suspend fun markBookFinished(bookId: Long) {
        progressDao.markCompleted(bookId, System.currentTimeMillis())
        bookDao.updateStatus(bookId, BookStatus.FINISHED)
        diskMirror.flushBook(bookId)
    }

    suspend fun updateSynopsis(bookId: Long, synopsis: String) {
        bookDao.updateSynopsis(bookId, synopsis)
        diskMirror.flushBook(bookId)
    }

    suspend fun getBooksByIds(ids: List<Long>): List<Book> = bookDao.getBooksByIds(ids)

    /** Not disk-mirror-hooked — this timestamp records that disk was just READ into the DB, not a
     *  DB change that needs writing back out. See Book.dataAppliedAtMs. */
    suspend fun markDataApplied(bookId: Long, ts: Long) = bookDao.updateDataAppliedAt(bookId, ts)

    /**
     * One-time cleanup of legacy auto-sliced "synthetic" chapters. Clearing all chapter rows
     * for affected books makes the next scan rebuild them via the (now synthetic-free)
     * chapter builder — embedded markers where present, else one chapter per file.
     */
    suspend fun purgeSyntheticChapters() = chapterDao.purgeSyntheticChapters()

    // ── Bookmarks ────────────────────────────────────────────────────────────
    fun getBookmarksForBook(bookId: Long): Flow<List<Bookmark>> = bookmarkDao.getForBook(bookId)
    suspend fun addBookmark(bookmark: Bookmark): Long {
        val id = bookmarkDao.insert(bookmark)
        diskMirror.flushBook(bookmark.bookId)
        return id
    }
    suspend fun deleteBookmark(id: Long) {
        val bookId = bookmarkDao.getById(id)?.bookId
        bookmarkDao.deleteById(id)
        bookId?.let { diskMirror.flushBook(it) }
    }

    // ── Audio presets ─────────────────────────────────────────────────────────
    fun getAllAudioPresets(): Flow<List<AudioPreset>> = audioPresetDao.getAll()
    suspend fun insertAudioPreset(preset: AudioPreset): Long {
        val id = audioPresetDao.insert(preset)
        diskMirror.flushLibrary()
        return id
    }
    suspend fun updateAudioPreset(preset: AudioPreset) {
        audioPresetDao.update(preset)
        diskMirror.flushLibrary()
    }
    suspend fun deleteAudioPreset(id: Long) {
        audioPresetDao.deleteById(id)
        diskMirror.flushLibrary()
    }
    suspend fun setDefaultAudioPreset(id: Long) {
        db.withTransaction {
            audioPresetDao.clearDefault()
            audioPresetDao.setDefault(id)
        }
        diskMirror.flushLibrary()
    }
    /** The global default preset (applied to every book unless the book overrides it), or null. */
    suspend fun getDefaultAudioPreset(): AudioPreset? = audioPresetDao.getDefault()
    /** Clear the global default flag without deleting any preset. */
    suspend fun clearDefaultAudioPreset() {
        audioPresetDao.clearDefault()
        diskMirror.flushLibrary()
    }
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
        diskMirror.markDirty(bookId)
    }

    /** Re-bake the cover effect for every book that has a cover. [onProgress] is called after each
     *  book with (done, total) so a long sweep over a large library can show real progress instead
     *  of an indefinite spinner; cancelling the calling coroutine stops the sweep between books. */
    suspend fun regenerateAllCoverFx(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val books = bookDao.getBooksWithCoversOnce()
        books.forEachIndexed { index, book ->
            currentCoroutineContext().ensureActive()
            bookDao.updateCoverFx(book.id, coverEffectBaker.bake(book.coverArtPath!!, book.id.toString()))
            onProgress(index + 1, books.size)
        }
    }
}
