package com.betteraudio.playback

import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.PlaybackProgress

/**
 * "Which member of this series is the listener actually in?" — extracted out of
 * [SeriesPlayer.playSeries] so it can be answered **without starting playback**.
 *
 * That distinction is the whole reason this exists: the Series page's ⋮ → Companion needs a book
 * id (a reveal cursor is per-book — docs/companion-packs.md §6 — so there is no such thing as
 * "the series' cursor"), and asking for one used to mean calling `playSeries`, which would start
 * the audio as a side effect of opening a menu item.
 *
 * Pure, so the rule is pinned by [com.betteraudio.playback.SeriesResumeTest] rather than only ever
 * exercised through a `MediaController`.
 */
object SeriesResume {

    /**
     * Most-recently-played → first unfinished → first. [startBookId], when it names a real member,
     * short-circuits all of it.
     *
     * @param books members in series order.
     * @param progressByBookId progress per member; a missing entry means never played.
     */
    fun pick(
        books: List<Book>,
        progressByBookId: Map<Long, PlaybackProgress?>,
        startBookId: Long? = null
    ): Pick? {
        if (books.isEmpty()) return null

        val explicit = startBookId?.let { id -> books.firstOrNull { it.id == id } }
        if (explicit != null) return Pick(explicit, "explicit")

        val mostRecent = books
            .filter { (progressByBookId[it.id]?.lastPlayedMs ?: 0L) > 0L }
            .maxByOrNull { progressByBookId[it.id]?.lastPlayedMs ?: 0L }
        if (mostRecent != null) return Pick(mostRecent, "most-recently-played")

        val unfinished = books.firstOrNull { it.status != BookStatus.FINISHED }
        if (unfinished != null) return Pick(unfinished, "first-unfinished")

        return Pick(books.first(), "first (all finished)")
    }

    /** [reason] exists only to keep [SeriesPlayer]'s log line as informative as it was before. */
    data class Pick(val book: Book, val reason: String)
}
