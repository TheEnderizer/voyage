package com.betteraudio.data.transcribe

import com.betteraudio.data.db.entities.SyncAnchor

/**
 * Pure, dependency-free math extracted from [SyncAligner] so it is directly unit-testable
 * without the Vosk model / Room / epub-parser dependencies the aligner itself needs. No I/O, no
 * Android APIs — mirrors the same "pure module" convention as
 * [com.betteraudio.sync.PositionBridge] and [com.betteraudio.playback.AudioCascade].
 */
object SyncAlignerMath {
    private const val RANGE_PAD_TOKENS = 800

    /** Jaccard similarity (intersection / union) between two token sets; 0 if either is empty. */
    fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.intersect(b).size.toFloat() / a.union(b).size
    }

    /** Anchors must advance in both audio time and text position. */
    fun isMonotonic(prev: SyncAnchor, next: SyncAnchor): Boolean =
        next.audioMs > prev.audioMs &&
            (next.spineIndex > prev.spineIndex || (next.spineIndex == prev.spineIndex && next.charOffset > prev.charOffset))

    /** Per-span book-token search range for pass 2, bracketed by the nearest span-start matches
     *  (in either direction) found in pass 1, padded a little for slack (a start-probe fires ~15s
     *  into its span, so the span's true start in the text sits a bit before the matched window).
     *  A span whose own start-probe failed just inherits the bracket of its nearest known
     *  neighbors — still far smaller than the whole book unless failures are widespread. Falls
     *  back to the whole book only at the very ends where there's no known neighbor at all. */
    fun spanSearchRanges(spanCount: Int, starts: Array<Int?>, bookTokCount: Int): Array<IntRange> {
        val knownIdx = starts.indices.filter { starts[it] != null }
        return Array(spanCount) { i ->
            val lo = knownIdx.lastOrNull { it <= i }
            val hi = knownIdx.firstOrNull { it > i }
            val rangeStart = ((lo?.let { starts[it]!! } ?: 0) - RANGE_PAD_TOKENS).coerceIn(0, bookTokCount)
            val rangeEnd = ((hi?.let { starts[it]!! } ?: bookTokCount) + RANGE_PAD_TOKENS).coerceIn(0, bookTokCount)
            rangeStart..rangeEnd
        }
    }
}
