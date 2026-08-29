package com.betteraudio.data.repository

import com.betteraudio.data.covers.CoverEffectBaker
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.diskstore.DiskMirror
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the first-class Series layer: CRUD, membership (via [Book.seriesId] / seriesOrder), and
 * the series-level cascade defaults. Member books always keep their own progress/status — this
 * repo never merges book data.
 */
@Singleton
class SeriesRepository @Inject constructor(
    private val seriesDao: SeriesDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val coverEffectBaker: CoverEffectBaker,
    private val diskMirror: DiskMirror
) {

    /** bookId → its audio files, sorted for playback. Used to flatten a series into one timeline. */
    suspend fun getAudioFilesForBooks(bookIds: List<Long>): Map<Long, List<AudioFile>> =
        bookIds.associateWith { id ->
            audioFileDao.getFilesForBookOnce(id).sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        }
    fun getAllSeries(): Flow<List<Series>> = seriesDao.getAll()
    suspend fun getAllSeriesOnce(): List<Series> = seriesDao.getAllOnce()
    fun getSeries(id: Long): Flow<Series?> = seriesDao.getById(id)
    suspend fun getSeriesOnce(id: Long): Series? = seriesDao.getByIdOnce(id)

    fun getBooksInSeries(seriesId: Long): Flow<List<Book>> = bookDao.getBooksInSeriesById(seriesId)
    suspend fun getBooksInSeriesOnce(seriesId: Long): List<Book> = bookDao.getBooksInSeriesByIdOnce(seriesId)

    /** Resolve a series by name, creating it (with [author]) if it doesn't exist yet. */
    suspend fun getOrCreateSeriesByName(name: String, author: String? = null): Long {
        val trimmed = name.trim()
        seriesDao.getByName(trimmed)?.let { return it.id }
        return seriesDao.insert(Series(name = trimmed, author = author?.takeIf { it.isNotBlank() }))
            .also { diskMirror.flushLibrary() }
    }

    suspend fun updateSeries(series: Series) {
        seriesDao.update(series)
        diskMirror.flushLibrary()
    }

    suspend fun renameSeries(id: Long, name: String) {
        val s = seriesDao.getByIdOnce(id) ?: return
        seriesDao.update(s.copy(name = name.trim()))
        // Keep the denormalised name on member books in sync.
        bookDao.getBooksInSeriesByIdOnce(id).forEach {
            bookDao.setSeriesMembership(it.id, id, name.trim(), it.seriesOrder)
        }
        diskMirror.flushLibrary()
    }

    /**
     * Add a book to a series at [order] (defaults to the end). seriesOrder is a Float, so a book
     * can be inserted between two others (e.g. 3.5) without renumbering.
     */
    suspend fun addBookToSeries(bookId: Long, seriesId: Long, order: Float? = null) {
        val series = seriesDao.getByIdOnce(seriesId) ?: return
        val resolvedOrder = order ?: nextOrder(seriesId)
        bookDao.setSeriesMembership(bookId, seriesId, series.name, resolvedOrder)
        diskMirror.flushLibrary()
        diskMirror.flushBook(bookId)
    }

    suspend fun setBookOrder(bookId: Long, order: Float) {
        bookDao.setSeriesOrder(bookId, order)
        diskMirror.flushLibrary()
        diskMirror.flushBook(bookId)
    }

    suspend fun removeBookFromSeries(bookId: Long) {
        val vacated = bookDao.getBookOnce(bookId)?.seriesId
        bookDao.setSeriesMembership(bookId, null, null, null)
        // pruneSeriesIfEmpty first (it also deletes the row's cover files), then the global sweep
        // as the cheap catch-all it has always been.
        vacated?.let { pruneSeriesIfEmpty(it) }
        seriesDao.deleteEmpty()
        AppLog.i(LogCat.DB, "removeBookFromSeries: book=$bookId detached from series=$vacated")
        diskMirror.flushLibrary()
        diskMirror.flushBook(bookId)
    }

    /**
     * Set a book's series from a free-text name — the write behind Book Options' / Book Info's /
     * the player's "Series" field.
     *
     * [Book.seriesId] is the source of truth for membership, so this always resolves (or creates)
     * a real [Series] row and writes it alongside the seriesName/seriesOrder cache. Writing only
     * the cache is what used to leave a book labelled with a series that Series view never
     * grouped, no Series page existed for, and the cascade defaults could never reach.
     *
     * Name resolution, in order:
     *  - blank/null → detach ([removeBookFromSeries]); a series keeping other members survives.
     *  - a name that already resolves to a series (case-insensitively) → JOIN that series rather
     *    than creating a second row for the same name; the book adopts the series' own spelling.
     *  - a new name, and this book is its current series' only member → RENAME that series in
     *    place, so its cover, description, author/narrator and cascade defaults survive what is
     *    almost always a typo fix.
     *  - a new name otherwise → create the series and move only this book into it; the other
     *    members of the old series stay where they are.
     *
     * [order] of null means "no explicit position": a book joining a series it wasn't in lands at
     * the end (as [addBookToSeries] does), while a book already in the series has its position
     * cleared — the field was pre-filled, so an emptied one is a deliberate clear.
     *
     * Callers restructure the book's folder afterwards where that applies: the series name is part
     * of the AUTHOR_SERIES_BOOK / AUTHOR_DASH_SERIES_BOOK layouts, and LibraryRestructurer reads
     * the name through seriesId — which is exactly what this write is finally setting.
     *
     * @return the resolved series id, or null when the book ended up in no series.
     */
    suspend fun setBookSeriesByName(bookId: Long, name: String?, order: Float? = null): Long? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            removeBookFromSeries(bookId)
            return null
        }

        // Ignore a dangling seriesId (row deleted underneath the book) so it can't send us down
        // the rename branch against a series that no longer exists.
        val currentId = book.seriesId?.takeIf { seriesDao.getByIdOnce(it) != null }
        val existing = seriesDao.getByName(trimmed)

        // Sole member of its current series, renaming to a name nothing else uses: keep the row.
        if (existing == null && currentId != null && bookDao.countSeriesMembers(currentId) == 1) {
            renameSeries(currentId, trimmed)                 // also refreshes this book's cache name
            bookDao.setSeriesOrder(bookId, order)
            AppLog.i(LogCat.DB, "setBookSeriesByName: book=$bookId renamed sole series=$currentId to '$trimmed'")
            diskMirror.flushLibrary()
            diskMirror.flushBook(bookId)
            return currentId
        }

        val targetId = existing?.id
            ?: getOrCreateSeriesByName(trimmed, book.displayAuthor.takeIf { it.isNotBlank() })
        val targetName = existing?.name ?: trimmed
        val resolvedOrder = if (targetId == currentId) order else order ?: nextOrder(targetId)
        bookDao.setSeriesMembership(bookId, targetId, targetName, resolvedOrder)
        if (currentId != null && currentId != targetId) pruneSeriesIfEmpty(currentId)
        AppLog.i(LogCat.DB, "setBookSeriesByName: book=$bookId -> series=$targetId '$targetName' order=$resolvedOrder")
        diskMirror.flushLibrary()
        diskMirror.flushBook(bookId)
        return targetId
    }

    /**
     * One-shot repair for installs carrying books whose seriesName was written without a seriesId
     * (see [setBookSeriesByName]) — they show a series label everywhere the cache is read while
     * being invisible to Series view, series playback and the cascade defaults.
     *
     * Groups the orphans by cached name so books naming the same series land in one row, reuses an
     * existing series when the name already resolves, and keeps each book's stored seriesOrder
     * rather than inventing positions the user never set.
     *
     * @return how many books were re-attached.
     */
    suspend fun repairOrphanedMembership(): Int {
        val orphans = bookDao.getBooksWithOrphanedSeriesNameOnce()
        if (orphans.isEmpty()) return 0
        orphans.groupBy { it.seriesName!!.trim() }.forEach { (seriesName, members) ->
            val author = members.firstOrNull { it.displayAuthor.isNotBlank() }?.displayAuthor
            val series = seriesDao.getByName(seriesName)
            val seriesId = series?.id ?: getOrCreateSeriesByName(seriesName, author)
            val canonicalName = series?.name ?: seriesName
            members.forEach { bookDao.setSeriesMembership(it.id, seriesId, canonicalName, it.seriesOrder) }
        }
        AppLog.i(LogCat.DB, "repairOrphanedMembership: re-attached ${orphans.size} book(s) to their series")
        diskMirror.flushLibrary()
        orphans.forEach { diskMirror.flushBook(it.id) }
        return orphans.size
    }

    /** Delete [seriesId] once nothing points at it any more — counting ignored books too, so
     *  hiding every member doesn't quietly destroy the series and its cover files. */
    private suspend fun pruneSeriesIfEmpty(seriesId: Long) {
        if (bookDao.countSeriesMembers(seriesId) == 0) deleteSeries(seriesId)
    }

    /** Detach all members, then delete the series row. */
    suspend fun deleteSeries(seriesId: Long) {
        val series = seriesDao.getByIdOnce(seriesId)
        val members = bookDao.getBooksInSeriesByIdOnce(seriesId)
        members.forEach {
            bookDao.setSeriesMembership(it.id, null, null, null)
        }
        seriesDao.deleteById(seriesId)
        // A series has no folder of its own — its online cover lives in `.voyage/covers/` (or
        // legacy filesDir for one set before that moved) and the baked composite in filesDir;
        // nothing else deletes either once the row is gone.
        series?.coverArtPath?.let { AudiobookRepository.deleteQuietly(it, "series $seriesId cover") }
        series?.coverFxPath?.let { AudiobookRepository.deleteQuietly(it, "series $seriesId coverFx") }
        AppLog.i(LogCat.DB, "deleteSeries: series=$seriesId '${series?.name}' deleted, detached ${members.size} member book(s)")
        diskMirror.flushLibrary()
        members.forEach { diskMirror.flushBook(it.id) }
    }

    suspend fun setSeriesCover(seriesId: Long, path: String?) {
        seriesDao.updateCover(seriesId, path)
        diskMirror.flushLibrary()
    }
    // Not mirrored: coverFxPath is a derived, re-bakeable composite (see ensureSeriesCoverFx) —
    // persisting it would just double this series' footprint in .voyage/ for nothing reconstructible.
    suspend fun setSeriesCoverFx(seriesId: Long, path: String?) = seriesDao.updateCoverFx(seriesId, path)

    /** Bake the series backdrop effect if a cover exists but no valid baked file is present yet —
     *  mirrors [com.betteraudio.data.repository.AudiobookRepository.ensureCoverFx] for books, so a
     *  series backdrop gets the same pre-baked reflection/blur instead of only ever live-rendering.
     *  Namespaced `"series_$seriesId"` cache key: series ids and book ids are separate autoincrement
     *  sequences and can collide numerically. */
    suspend fun ensureSeriesCoverFx(seriesId: Long) {
        val series = seriesDao.getById(seriesId).firstOrNull() ?: return
        val cover = series.coverArtPath ?: return
        val fx = series.coverFxPath
        if (fx != null && java.io.File(fx).exists()) return
        setSeriesCoverFx(seriesId, coverEffectBaker.bake(cover, "series_$seriesId"))
    }

    private suspend fun nextOrder(seriesId: Long): Float {
        val max = bookDao.getBooksInSeriesByIdOnce(seriesId).maxOfOrNull { it.seriesOrder ?: 0f } ?: 0f
        return max + 1f
    }
}
