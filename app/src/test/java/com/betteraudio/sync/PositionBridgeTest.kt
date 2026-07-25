package com.betteraudio.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PositionBridge] is the pure audio↔text mapping the reading/listening bridge relies on; a
 * regression here silently lands the reader/listener at the wrong spot with no crash to catch it.
 * Covers the `audioToText` proportional-fallback bug fix (previously always returned spine 0 for a
 * chapterless book instead of the documented proportional mapping) plus the core run-fraction and
 * anchor-interpolation paths.
 */
class PositionBridgeTest {

    private fun span(index: Int, absStartMs: Long, durationMs: Long) =
        AudioChapterSpan(index, "Ch$index", absStartMs, durationMs)

    // ── audioAnchor ──────────────────────────────────────────────────────────

    @Test
    fun `audioAnchor on empty chapters returns the sentinel`() {
        assertEquals(-1 to 0f, PositionBridge.audioAnchor(500, emptyList()))
    }

    @Test
    fun `audioAnchor finds the containing chapter and fraction within it`() {
        val chapters = listOf(span(0, 0, 1000), span(1, 1000, 1000))
        assertEquals(0 to 0.5f, PositionBridge.audioAnchor(500, chapters))
        assertEquals(1 to 0.5f, PositionBridge.audioAnchor(1500, chapters))
    }

    // ── audioToText: the C.7 fallback fix ───────────────────────────────────

    @Test
    fun `audioToText with no chapters at all returns the spine-0 sentinel`() {
        val result = PositionBridge.audioToText(5000, emptyList(), ChapterMap(emptyList()), spineCount = 3)
        assertEquals(TextLocator(0, 0f), result)
    }

    @Test
    fun `audioToText with chapters but no chapter map falls back to whole-book proportional mapping`() {
        // Single 1000ms chapter, spineCount=2. At 25% through the book: spineIdx=floor(0.25*2)=0,
        // fracInSpine=(0.25-0)/0.5=0.5.
        val chapters = listOf(span(0, 0, 1000))
        val quarter = PositionBridge.audioToText(250, chapters, ChapterMap(emptyList()), spineCount = 2)
        assertEquals(0, quarter.spineIndex)
        assertEquals(0.5f, quarter.fraction, 0.0001f)

        // At 50% through the book: spineIdx=floor(0.5*2)=1, fracInSpine=(0.5-0.5)/0.5=0.
        val half = PositionBridge.audioToText(500, chapters, ChapterMap(emptyList()), spineCount = 2)
        assertEquals(1, half.spineIndex)
        assertEquals(0f, half.fraction, 0.0001f)
    }

    // ── audioToText: normal chapter-map-driven path ─────────────────────────

    @Test
    fun `audioToText with a 1-to-1 chapter map reflects position within that chapter`() {
        val chapters = listOf(span(0, 0, 1000), span(1, 1000, 1000))
        val map = ChapterMap(listOf(0, 1))
        assertEquals(TextLocator(0, 0.5f), PositionBridge.audioToText(500, chapters, map, spineCount = 2))
        assertEquals(TextLocator(1, 0.5f), PositionBridge.audioToText(1500, chapters, map, spineCount = 2))
    }

    @Test
    fun `audioToText fraction spans the full run when multiple audio chapters map to one spine item`() {
        // Both audio chapters map to spine 0, forming one 2000ms run; 1500ms in -> 0.75.
        val chapters = listOf(span(0, 0, 1000), span(1, 1000, 1000))
        val map = ChapterMap(listOf(0, 0))
        assertEquals(TextLocator(0, 0.75f), PositionBridge.audioToText(1500, chapters, map, spineCount = 1))
    }

    @Test
    fun `audioToText interpolates spine for an unmapped chapter between two mapped neighbors`() {
        val chapters = listOf(span(0, 0, 1000), span(1, 1000, 1000), span(2, 2000, 1000))
        val map = ChapterMap(listOf(0, -1, 1))
        // Chapter 1 (unmapped) sits between spine 0 and spine 1 -> resolves to spine 0's run,
        // which now spans chapters 0-1 (2000ms), landing at 1500/2000 = 0.75.
        assertEquals(TextLocator(0, 0.75f), PositionBridge.audioToText(1500, chapters, map, spineCount = 2))
    }

    // ── textToAudio: inverse of the 1-to-1 case ─────────────────────────────

    @Test
    fun `textToAudio inverts audioToText for a 1-to-1 chapter map`() {
        val chapters = listOf(span(0, 0, 1000), span(1, 1000, 1000))
        val map = ChapterMap(listOf(0, 1))
        assertEquals(500L, PositionBridge.textToAudio(TextLocator(0, 0.5f), chapters, map))
        assertEquals(1500L, PositionBridge.textToAudio(TextLocator(1, 0.5f), chapters, map))
    }

    // ── anchor-only resolution (audioToCharAnchored / charToAudioAnchored) ──

    @Test
    fun `fewer than 2 anchors returns null`() {
        assertNull(PositionBridge.audioToCharAnchored(1000, emptyList()) { 0 })
        assertNull(PositionBridge.audioToCharAnchored(1000, listOf(AnchorPoint(1000, 0, 100))) { 0 })
        assertNull(PositionBridge.charToAudioAnchored(0, 100, emptyList()) { 0 })
    }

    @Test
    fun `audioToCharAnchored interpolates linearly within the same spine`() {
        val anchors = listOf(AnchorPoint(1000, 0, 100), AnchorPoint(3000, 0, 300))
        assertEquals(0 to 200, PositionBridge.audioToCharAnchored(2000, anchors) { 0 })
    }

    @Test
    fun `charToAudioAnchored is the exact inverse within the same spine`() {
        val anchors = listOf(AnchorPoint(1000, 0, 100), AnchorPoint(3000, 0, 300))
        assertEquals(2000L, PositionBridge.charToAudioAnchored(0, 200, anchors) { 0 })
    }

    @Test
    fun `audioToCharAnchored and charToAudioAnchored round-trip across a spine transition`() {
        val anchors = listOf(AnchorPoint(1000, 0, 900), AnchorPoint(3000, 2, 50))
        val totalCharsFor = { spine: Int -> when (spine) { 0 -> 1000; 1 -> 500; else -> 0 } }

        val (spine, charOffset) = PositionBridge.audioToCharAnchored(2000, anchors, totalCharsFor)!!
        assertEquals(1, spine)
        assertEquals(225, charOffset)

        val backToAudio = PositionBridge.charToAudioAnchored(spine, charOffset, anchors, totalCharsFor)
        assertEquals(2000L, backToAudio)
    }
}
