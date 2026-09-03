package com.betteraudio.sync

import com.betteraudio.sync.ChapterTitleMatcher.ChapterSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTitleMatcherTest {

    /**
     * Builds a token stream from paragraphs grouped per spine item, returning the accessor and
     * slices the matcher takes. `spine` = list of spine items, each a list of paragraph strings.
     */
    private class Book(spine: List<List<String>>) {
        val tokens = ArrayList<String>()
        val slices = ArrayList<ChapterSlice>()

        init {
            spine.forEachIndexed { spineIndex, paragraphs ->
                paragraphs.forEachIndexed { indexInSpine, text ->
                    val start = tokens.size
                    tokens.addAll(TextSimilarity.tokenize(text))
                    slices.add(ChapterSlice(start, tokens.size, spineIndex, indexInSpine))
                }
            }
        }

        fun tokenAt(i: Int): String = tokens[i]
        fun startOfParagraph(spineIndex: Int, indexInSpine: Int): Int =
            slices.first { it.spineIndex == spineIndex && it.indexInSpine == indexInSpine }.startToken
    }

    private val prose = "the survivor walked a long way through the cold and did not look back at all"

    // ── the happy path ────────────────────────────────────────────────────

    @Test
    fun `matches a heading at the top of its spine item`() {
        val book = Book(listOf(
            listOf("Chapter One", prose),
            listOf("Chapter Two", prose)
        ))
        val m = ChapterTitleMatcher.find("Chapter Two", book::tokenAt, book.slices)
        assertNotNull(m)
        assertEquals(book.startOfParagraph(1, 0), m!!.tokenIndex)
    }

    @Test
    fun `digits in the audio title match words in the heading`() {
        val book = Book(listOf(listOf("Chapter Twelve", prose)))
        val m = ChapterTitleMatcher.find("Chapter 12", book::tokenAt, book.slices)
        assertNotNull("'Chapter 12' should match the heading 'Chapter Twelve'", m)
        assertEquals(0, m!!.tokenIndex)
    }

    @Test
    fun `words in the audio title match digits in the heading`() {
        val book = Book(listOf(listOf("Chapter 23", prose)))
        val m = ChapterTitleMatcher.find("Chapter Twenty Three", book::tokenAt, book.slices)
        assertNotNull(m)
        assertEquals(0, m!!.tokenIndex)
    }

    @Test
    fun `a heading with a subtitle still matches the shorter audio title`() {
        val book = Book(listOf(listOf("Chapter 12: The Forgotten Shore", prose)))
        val m = ChapterTitleMatcher.find("Chapter 12", book::tokenAt, book.slices)
        assertNotNull(m)
        assertEquals(0, m!!.tokenIndex)
    }

    @Test
    fun `a single-word title like Prologue matches`() {
        val book = Book(listOf(listOf("Prologue", prose), listOf("Chapter One", prose)))
        val m = ChapterTitleMatcher.find("Prologue", book::tokenAt, book.slices)
        assertNotNull(m)
        assertEquals(0, m!!.tokenIndex)
    }

    // ── the guards, each against the failure it exists for ────────────────

    @Test
    fun `the phrase occurring mid-prose is not matched - paragraph starts only`() {
        val book = Book(listOf(
            listOf("she remembered what happened in chapter twelve of the old book and sighed again")
        ))
        assertNull(ChapterTitleMatcher.find("Chapter Twelve", book::tokenAt, book.slices))
    }

    @Test
    fun `a long body paragraph opening with the title is not a heading`() {
        val book = Book(listOf(
            listOf("Chapter Twelve began badly for everyone involved and it only got worse from there onward as the days passed")
        ))
        assertNull(ChapterTitleMatcher.find("Chapter Twelve", book::tokenAt, book.slices))
    }

    /**
     * The table-of-contents trap: a TOC spine item is a run of paragraph-start matches
     * ("Chapter One", "Chapter Two", …) sitting at the very front of the book. Only its first
     * entry is near enough to its spine head to be a candidate; everything after must be rejected,
     * or every chapter in the book anchors into the TOC.
     */
    @Test
    fun `table of contents entries past the spine head are rejected`() {
        val toc = listOf("Contents", "Chapter One", "Chapter Two", "Chapter Three", "Chapter Four", "Chapter Five")
        val book = Book(listOf(toc, listOf("Chapter Five", prose)))

        val m = ChapterTitleMatcher.find("Chapter Five", book::tokenAt, book.slices)
        assertNotNull(m)
        assertEquals(
            "must land on the real chapter, not the TOC entry",
            book.startOfParagraph(1, 0), m!!.tokenIndex
        )
    }

    @Test
    fun `monotonic - a match before the search floor is not returned`() {
        val book = Book(listOf(listOf("Chapter One", prose), listOf("Chapter One", prose)))
        val second = book.startOfParagraph(1, 0)
        val m = ChapterTitleMatcher.find("Chapter One", book::tokenAt, book.slices, searchFromToken = second)
        assertNotNull(m)
        assertEquals(second, m!!.tokenIndex)
    }

    @Test
    fun `drift bound rejects a match far from where the chapter is expected`() {
        val book = Book(listOf(listOf("Chapter One", prose), listOf("Chapter Forty", prose)))
        val far = book.startOfParagraph(1, 0)
        // Expected near the very start, tolerance of 2 tokens: the real match is well past it.
        assertNull(
            ChapterTitleMatcher.find("Chapter Forty", book::tokenAt, book.slices, expectedToken = 0, maxDriftTokens = 2)
        )
        // Same match, generous tolerance: accepted.
        val ok = ChapterTitleMatcher.find("Chapter Forty", book::tokenAt, book.slices, expectedToken = 0, maxDriftTokens = 10_000)
        assertEquals(far, ok?.tokenIndex)
    }

    @Test
    fun `a title absent from the text yields no match`() {
        val book = Book(listOf(listOf("Chapter One", prose)))
        assertNull(ChapterTitleMatcher.find("Track 07", book::tokenAt, book.slices))
    }

    @Test
    fun `a blank or punctuation-only title yields no match`() {
        val book = Book(listOf(listOf("Chapter One", prose)))
        assertNull(ChapterTitleMatcher.find("", book::tokenAt, book.slices))
        assertNull(ChapterTitleMatcher.find("---", book::tokenAt, book.slices))
    }

    @Test
    fun `empty book yields no match`() {
        val book = Book(emptyList())
        assertNull(ChapterTitleMatcher.find("Chapter One", book::tokenAt, book.slices))
    }

    /** A whole run of chapters must land on its own heading, in order — the property pass 1
     *  actually depends on, since each match becomes the next one's search floor. */
    @Test
    fun `a run of chapters each lands on its own heading in order`() {
        val spine = (1..8).map { listOf("Chapter $it", prose) }
        val book = Book(spine)
        var from = 0
        val hits = ArrayList<Int>()
        for (i in 1..8) {
            val m = ChapterTitleMatcher.find("Chapter $i", book::tokenAt, book.slices, searchFromToken = from)
            assertNotNull("chapter $i should match", m)
            assertEquals("chapter $i landed on the wrong heading", book.startOfParagraph(i - 1, 0), m!!.tokenIndex)
            hits.add(m.tokenIndex)
            from = m.tokenIndex + 1
        }
        assertTrue("matches must be strictly increasing", hits.zipWithNext().all { (a, b) -> a < b })
    }

    // ── number spelling ───────────────────────────────────────────────────

    @Test
    fun `numberToWords covers the chapter-number range and refuses beyond it`() {
        assertEquals(listOf("one"), ChapterTitleMatcher.numberToWords(1))
        assertEquals(listOf("twelve"), ChapterTitleMatcher.numberToWords(12))
        assertEquals(listOf("twenty"), ChapterTitleMatcher.numberToWords(20))
        assertEquals(listOf("twenty", "three"), ChapterTitleMatcher.numberToWords(23))
        assertEquals(listOf("ninety", "nine"), ChapterTitleMatcher.numberToWords(99))
        assertNull(ChapterTitleMatcher.numberToWords(100))
        assertNull(ChapterTitleMatcher.numberToWords(-1))
    }

    @Test
    fun `titleVariants offers both spellings and nothing for an empty title`() {
        val v = ChapterTitleMatcher.titleVariants("Chapter 23")
        assertTrue("should offer the digit form", v.any { it == listOf("chapter", "23") })
        assertTrue("should offer the word form", v.any { it == listOf("chapter", "twenty", "three") })
        assertTrue(ChapterTitleMatcher.titleVariants("   ").isEmpty())
    }
}
