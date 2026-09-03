package com.betteraudio.companion

import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.data.db.entities.Book

/**
 * Resolves a [FactAnchor] to a book-global millisecond position — the same coordinate space
 * [com.betteraudio.data.db.entities.PlaybackProgress.revealedMs] is compared against (§6.1's
 * `resolvedRevealMs(fact) <= revealedMs(fact.bookRef)`).
 *
 * **Scope note:** this implementation resolves tiers #2 (`globalMs`) and #5 (`ratio`) of
 * docs/companion-packs.md §5's five-tier fallback chain only. Tiers #1 (`fileKey`+`offsetMs`),
 * #3 (`chapter`+`chapterOffsetMs`), and #4 (`quote`, via SyncAnchor) need, respectively: matching
 * the anchor's `fileKey` against this device's own `AudioFile` rows (needs the cached `fileKey`
 * column §11 calls for, not yet added), this book's `ChapterTimeline`, and a quote→locator search
 * over aligned text (§14 already calls this a stretch goal — the search itself doesn't exist yet).
 * None of those are needed for a pack authored on THIS device against THIS book's own files, which
 * always has `globalMs` available (the author's own playback position at capture time) — the case
 * this resolver correctly and fully serves today. A pack imported from another device with a
 * different file layout (§10.3) will resolve through tier #5 only until the fuller resolver
 * lands, which is honestly reflected by [degraded] below rather than silently claiming precision
 * this resolver doesn't have.
 */
object AnchorResolver {

    data class Resolved(
        val bookGlobalMs: Long,
        /** True when resolution fell back to tier #5 (`ratio`, ±minutes) rather than an exact
         *  tier — drives the `timings approximate` badge (§5). Also true, conservatively, for any
         *  anchor this resolver cannot resolve at all (treated as maximally coarse rather than
         *  silently wrong). */
        val degraded: Boolean
    )

    /** Returns null only when the anchor carries neither `globalMs` nor `ratio` nor a duration to
     *  scale `ratio` against — i.e. genuinely unresolvable with what this resolver knows how to
     *  read, not a "not implemented yet" case (see class doc). */
    fun resolve(anchor: FactAnchor, book: Book): Resolved? {
        anchor.globalMs?.let { return Resolved(it, degraded = false) }
        val ratio = anchor.ratio ?: return null
        if (book.totalDurationMs <= 0L) return null
        // §14's risk table: "bias late" — when resolution is coarse, round the reveal BACKWARDS,
        // never forwards, so an approximate anchor can under-reveal but never over-reveal.
        val floor = kotlin.math.floor(ratio.toDouble() * book.totalDurationMs).toLong()
        return Resolved(floor.coerceIn(0L, book.totalDurationMs), degraded = true)
    }
}
