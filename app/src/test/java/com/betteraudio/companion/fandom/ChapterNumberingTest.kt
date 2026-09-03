package com.betteraudio.companion.fandom

import com.betteraudio.playback.ChapterMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterNumberingTest {

    private fun marks(titles: List<String>, stepMs: Long = 1_000L): List<ChapterMark> =
        titles.mapIndexed { i, title ->
            ChapterMark(
                index = i, title = title, startMs = i * stepMs, endMs = (i + 1) * stepMs,
                rawDurationMs = stepMs, fileId = 1L, fileIndex = 0, key = i.toLong()
            )
        }

    /** 40 marks titled the way a serialised audiobook names them. */
    private fun numbered(count: Int = 40, from: Int = 1) =
        marks((0 until count).map { "Ep ${from + it} - Something" })

    @Test
    fun `a chapter number is read out of the title`() {
        assertEquals(1296, ChapterNumbering.numberOf("Ep 1296 - Fake It"))
        assertEquals(13, ChapterNumbering.numberOf("Chapter 13 Moment of Truth"))
        assertEquals(7, ChapterNumbering.numberOf("7. The Road"))
        assertNull(ChapterNumbering.numberOf("Fake It"))
    }

    @Test
    fun `numbered titles give exact positions`() {
        val mapping = ChapterNumbering.of(numbered())
        assertEquals(ChapterNumbering.Kind.NUMBERED, mapping.kind)
        assertEquals(0L, mapping.positionOf(1))
        assertEquals(9_000L, mapping.positionOf(10))
    }

    @Test
    fun `a chapter the audiobook does not mark is interpolated between its neighbours`() {
        // Marks for chapters 10 and 20 only, ten seconds apart.
        val mapping = ChapterNumbering.of(marks(listOf("Ep 10", "Ep 20")) + numbered(30, from = 30))
        // 15 sits halfway between 10 (0 ms) and 20 (1000 ms).
        assertEquals(500L, mapping.positionOf(15))
    }

    @Test
    fun `a chapter outside the mapping is refused rather than clamped`() {
        val mapping = ChapterNumbering.of(numbered(count = 40, from = 1))
        assertNull("chapter 900 is past the last mark and must not resolve to the end", mapping.positionOf(900))
    }

    @Test
    fun `titles whose numbers do not increase are rejected as a numbering`() {
        // Volume/part numbers that reset would otherwise produce a plausible but wrong map.
        val titles = (0 until 40).map { "Part ${(it % 5) + 1} of the Story" }
        val mapping = ChapterNumbering.of(marks(titles))
        assertEquals(ChapterNumbering.Kind.ORDINAL, mapping.kind)
        assertEquals(2_000L, mapping.positionOf(3))
    }

    @Test
    fun `a per-file book cannot locate chapters at all`() {
        // Eight volume-length files are a file list, not a chapter list: "Volume 3" is not
        // chapter 3, and pretending otherwise would misplace every anchor.
        val mapping = ChapterNumbering.of(marks((1..8).map { "Shadow Slave Volume $it" }))
        assertEquals(ChapterNumbering.Kind.NONE, mapping.kind)
        assertNull(mapping.positionOf(3))
        assertNull(mapping.chapterAt(5_000L))
        assertNull(mapping.maxChapter)
    }

    @Test
    fun `an empty book has no numbering`() {
        assertEquals(ChapterNumbering.Kind.NONE, ChapterNumbering.of(emptyList()).kind)
    }

    @Test
    fun `chapterAt reports the story chapter the listener is in`() {
        val mapping = ChapterNumbering.of(numbered(count = 40, from = 1200))
        // Mark index 5 starts at 5000 ms and is titled "Ep 1205".
        assertEquals(1205, mapping.chapterAt(5_500L))
        assertEquals(1200, mapping.chapterAt(0L))
    }

    @Test
    fun `an ordinal mapping counts marks in order`() {
        val mapping = ChapterNumbering.of(marks((1..40).map { "Untitled" }))
        assertEquals(ChapterNumbering.Kind.ORDINAL, mapping.kind)
        assertEquals(6, mapping.chapterAt(5_500L))
        assertEquals(40, mapping.maxChapter)
    }
}
