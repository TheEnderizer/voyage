package com.betteraudio.data.repository

import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.Series
import kotlinx.coroutines.flow.Flow
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
    private val bookDao: BookDao
) {
    fun getAllSeries(): Flow<List<Series>> = seriesDao.getAll()
    suspend fun getAllSeriesOnce(): List<Series> = seriesDao.getAllOnce()
    fun getSeries(id: Long): Flow<Series?> = seriesDao.getById(id)
    suspend fun getSeriesOnce(id: Long): Series? = seriesDao.getByIdOnce(id)

    fun getBooksInSeries(seriesId: Long): Flow<List<Book>> = bookDao.getBooksInSeriesById(seriesId)
    suspend fun getBooksInSeriesOnce(seriesId: Long): List<Book> = bookDao.getBooksInSeriesByIdOnce(seriesId)
    suspend fun countBooks(seriesId: Long): Int = bookDao.countBooksInSeries(seriesId)

    /** Resolve a series by name, creating it (with [author]) if it doesn't exist yet. */
    suspend fun getOrCreateSeriesByName(name: String, author: String? = null): Long {
        val trimmed = name.trim()
        seriesDao.getByName(trimmed)?.let { return it.id }
        return seriesDao.insert(Series(name = trimmed, author = author?.takeIf { it.isNotBlank() }))
    }

    suspend fun createSeries(name: String, author: String? = null): Long =
        seriesDao.insert(Series(name = name.trim(), author = author?.takeIf { it.isNotBlank() }))

    suspend fun updateSeries(series: Series) = seriesDao.update(series)

    suspend fun renameSeries(id: Long, name: String) {
        val s = seriesDao.getByIdOnce(id) ?: return
        seriesDao.update(s.copy(name = name.trim()))
        // Keep the denormalised name on member books in sync.
        bookDao.getBooksInSeriesByIdOnce(id).forEach {
            bookDao.setSeriesMembership(it.id, id, name.trim(), it.seriesOrder)
        }
    }

    /**
     * Add a book to a series at [order] (defaults to the end). seriesOrder is a Float, so a book
     * can be inserted between two others (e.g. 3.5) without renumbering.
     */
    suspend fun addBookToSeries(bookId: Long, seriesId: Long, order: Float? = null) {
        val series = seriesDao.getByIdOnce(seriesId) ?: return
        val resolvedOrder = order ?: nextOrder(seriesId)
        bookDao.setSeriesMembership(bookId, seriesId, series.name, resolvedOrder)
    }

    suspend fun setBookOrder(bookId: Long, order: Float) = bookDao.setSeriesOrder(bookId, order)

    suspend fun removeBookFromSeries(bookId: Long) {
        bookDao.setSeriesMembership(bookId, null, null, null)
        seriesDao.deleteEmpty()
    }

    /** Detach all members, then delete the series row. */
    suspend fun deleteSeries(seriesId: Long) {
        bookDao.getBooksInSeriesByIdOnce(seriesId).forEach {
            bookDao.setSeriesMembership(it.id, null, null, null)
        }
        seriesDao.deleteById(seriesId)
    }

    suspend fun setSeriesCover(seriesId: Long, path: String?) = seriesDao.updateCover(seriesId, path)
    suspend fun setSeriesCoverFx(seriesId: Long, path: String?) = seriesDao.updateCoverFx(seriesId, path)

    private suspend fun nextOrder(seriesId: Long): Float {
        val max = bookDao.getBooksInSeriesByIdOnce(seriesId).maxOfOrNull { it.seriesOrder ?: 0f } ?: 0f
        return max + 1f
    }
}
