package com.betteraudio.companion

import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorResolverTest {

    private fun book(totalDurationMs: Long) = Book(
        id = 1L,
        title = "t",
        author = "a",
        folderPath = "/lib/book",
        totalDurationMs = totalDurationMs,
        addedDateMs = 0L,
        status = BookStatus.NOT_STARTED,
        fileCount = 1
    )

    @Test
    fun `globalMs is used directly and marked not degraded - tier 2`() {
        val resolved = AnchorResolver.resolve(FactAnchor(bookRef = "m1", globalMs = 123_456L), book(1_000_000L))
        assertEquals(123_456L, resolved?.bookGlobalMs)
        assertEquals(false, resolved?.degraded)
    }

    @Test
    fun `globalMs wins over ratio when both are present`() {
        val resolved = AnchorResolver.resolve(
            FactAnchor(bookRef = "m1", globalMs = 100_000L, ratio = 0.9f),
            book(1_000_000L)
        )
        assertEquals(100_000L, resolved?.bookGlobalMs)
        assertEquals(false, resolved?.degraded)
    }

    @Test
    fun `ratio falls back to tier 5 and is marked degraded`() {
        val resolved = AnchorResolver.resolve(FactAnchor(bookRef = "m1", ratio = 0.5f), book(1_000_000L))
        assertEquals(500_000L, resolved?.bookGlobalMs)
        assertEquals(true, resolved?.degraded)
    }

    @Test
    fun `ratio rounds down (biases late) rather than up`() {
        // 0.1 of 999,999ms = 99,999.9ms — must floor to 99,999, never round up to 100,000. A
        // spoiler-avoidance feature must never round an approximate anchor's reveal EARLY.
        val resolved = AnchorResolver.resolve(FactAnchor(bookRef = "m1", ratio = 0.1f), book(999_999L))
        assertTrue((resolved?.bookGlobalMs ?: -1L) <= 99_999L)
    }

    @Test
    fun `an anchor with neither globalMs nor ratio is unresolvable`() {
        assertNull(AnchorResolver.resolve(FactAnchor(bookRef = "m1", chapter = 3), book(1_000_000L)))
    }

    @Test
    fun `ratio is unresolvable against a book with no known duration`() {
        assertNull(AnchorResolver.resolve(FactAnchor(bookRef = "m1", ratio = 0.5f), book(0L)))
    }

    @Test
    fun `ratio result is clamped within the book's duration`() {
        val resolved = AnchorResolver.resolve(FactAnchor(bookRef = "m1", ratio = 1.5f), book(1_000_000L))
        assertEquals(1_000_000L, resolved?.bookGlobalMs)
    }
}
