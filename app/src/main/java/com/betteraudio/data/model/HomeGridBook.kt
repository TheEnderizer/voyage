package com.betteraudio.data.model

import com.betteraudio.data.db.entities.BookStatus

/**
 * Lightweight projection for the home grid, standing in for [BookWithProgress] there — it never
 * loads a book's audio_files, which for [BookWithProgress] means one child-table load per book,
 * for every book in the library, on every recomposition. Field set is exactly what
 * HomeViewModel.buildGridItems and the home grid card composables read; keep it in sync with
 * their actual usage, not the other way around — a missing-field compiler error here is exactly
 * how a UI regression in this projection is supposed to surface.
 *
 * [BookWithProgress] itself is untouched and still backs every consumer that needs the real
 * audio file list (play-queue building, book info, etc.) via the existing cheap single-book
 * `getBookWithProgress(bookId)` flow.
 */
data class HomeGridBook(
    val id: Long,
    val title: String,
    val titleOverride: String?,
    val author: String,
    val authorOverride: String?,
    val ebookPath: String?,
    val seriesId: Long?,
    val seriesName: String?,
    val seriesOrder: Float?,
    val status: BookStatus,
    val totalDurationMs: Long,
    val addedDateMs: Long,
    val coverArtPath: String?,
    val positionMs: Long?,
    val lastPlayedMsRaw: Long?,
    val textOverallFraction: Float?,
    val filesBeforeCurrentMs: Long?
) {
    val displayTitle: String get() = titleOverride ?: title
    val displayAuthor: String get() = authorOverride ?: author
    val isEbookOnly: Boolean get() = ebookPath != null && totalDurationMs == 0L
    val lastPlayedMs: Long get() = lastPlayedMsRaw ?: addedDateMs
    val readingFraction: Float get() = textOverallFraction ?: 0f

    val progressFraction: Float get() {
        val total = totalDurationMs.takeIf { it > 0 } ?: return 0f
        val pos = positionMs ?: 0L
        val before = filesBeforeCurrentMs ?: 0L
        return ((before + pos).toFloat() / total).coerceIn(0f, 1f)
    }
}
