package com.betteraudio.data.transcribe

import com.betteraudio.data.db.entities.SyncAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the deterministic index/set-math primitives behind forced alignment (extracted from
 * [SyncAligner] specifically so they're testable without its Vosk/Room/epub dependencies) — a
 * regression here would silently mis-anchor audio-to-text sync with no crash to catch it.
 */
class SyncAlignerMathTest {

    // ── jaccard ──────────────────────────────────────────────────────────────

    @Test
    fun `jaccard of identical sets is 1`() {
        val s = setOf("a", "b", "c")
        assertEquals(1f, SyncAlignerMath.jaccard(s, s))
    }

    @Test
    fun `jaccard of disjoint sets is 0`() {
        assertEquals(0f, SyncAlignerMath.jaccard(setOf("a", "b"), setOf("c", "d")))
    }

    @Test
    fun `jaccard of empty sets is 0`() {
        assertEquals(0f, SyncAlignerMath.jaccard(emptySet(), setOf("a")))
        assertEquals(0f, SyncAlignerMath.jaccard(setOf("a"), emptySet()))
        assertEquals(0f, SyncAlignerMath.jaccard(emptySet(), emptySet()))
    }

    @Test
    fun `jaccard of partial overlap is intersection over union`() {
        // {a,b,c} ∩ {b,c,d} = {b,c} (2); ∪ = {a,b,c,d} (4) -> 0.5
        assertEquals(0.5f, SyncAlignerMath.jaccard(setOf("a", "b", "c"), setOf("b", "c", "d")))
    }

    // ── isMonotonic ──────────────────────────────────────────────────────────

    private fun anchor(audioMs: Long, spineIndex: Int, charOffset: Int) =
        SyncAnchor(bookId = 1L, audioMs = audioMs, spineIndex = spineIndex, paragraphIndex = 0, charOffset = charOffset, confidence = 1f)

    @Test
    fun `advancing in time and spine is monotonic`() {
        assertTrue(SyncAlignerMath.isMonotonic(anchor(1000, 0, 50), anchor(2000, 1, 0)))
    }

    @Test
    fun `advancing in time and char offset within the same spine is monotonic`() {
        assertTrue(SyncAlignerMath.isMonotonic(anchor(1000, 0, 50), anchor(2000, 0, 60)))
    }

    @Test
    fun `same or earlier audio time is never monotonic`() {
        assertFalse(SyncAlignerMath.isMonotonic(anchor(2000, 0, 50), anchor(2000, 1, 100)))
        assertFalse(SyncAlignerMath.isMonotonic(anchor(2000, 0, 50), anchor(1000, 1, 100)))
    }

    @Test
    fun `later time but earlier spine is not monotonic`() {
        assertFalse(SyncAlignerMath.isMonotonic(anchor(1000, 2, 50), anchor(2000, 1, 999)))
    }

    @Test
    fun `later time same spine but earlier char offset is not monotonic`() {
        assertFalse(SyncAlignerMath.isMonotonic(anchor(1000, 0, 100), anchor(2000, 0, 50)))
    }

    // ── spanSearchRanges ─────────────────────────────────────────────────────

    @Test
    fun `all spans known brackets each span between its neighbors' starts, padded`() {
        val starts = arrayOf<Int?>(0, 1000, 2000)
        val ranges = SyncAlignerMath.spanSearchRanges(3, starts, bookTokCount = 3000)
        // range end looks at the NEXT known start (strictly after i), not span i's own start, so
        // span 0's range extends to span 1's start (+pad), etc. Only the last span has no "next"
        // neighbor and falls back to bookTokCount for its upper bound.
        assertEquals(0..1800, ranges[0])   // (0-800)->0 .. (1000+800)
        assertEquals(200..2800, ranges[1]) // (1000-800) .. (2000+800)
        assertEquals(1200..3000, ranges[2]) // (2000-800) .. (3000+800)->3000
    }

    @Test
    fun `unknown span inherits the bracket of its nearest known neighbors`() {
        val starts = arrayOf<Int?>(0, null, 2000)
        val ranges = SyncAlignerMath.spanSearchRanges(3, starts, bookTokCount = 3000)
        // Span 1 has no known start -> brackets between neighbor 0 (start=0) and neighbor 2 (start=2000).
        assertEquals((0 - 800).coerceIn(0, 3000)..(2000 + 800).coerceIn(0, 3000), ranges[1])
    }

    @Test
    fun `no known neighbor at all falls back to the whole book`() {
        val starts = arrayOf<Int?>(null, null)
        val ranges = SyncAlignerMath.spanSearchRanges(2, starts, bookTokCount = 5000)
        assertEquals(0..5000, ranges[0])
        assertEquals(0..5000, ranges[1])
    }

    @Test
    fun `range is clamped into the book token bounds`() {
        val starts = arrayOf<Int?>(10)
        val ranges = SyncAlignerMath.spanSearchRanges(1, starts, bookTokCount = 100)
        assertEquals(0..100, ranges[0])
    }

    @Test
    fun `result size always matches spanCount`() {
        val ranges = SyncAlignerMath.spanSearchRanges(5, arrayOf(null, null, null, null, null), bookTokCount = 1000)
        assertEquals(5, ranges.size)
    }
}
