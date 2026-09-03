package com.betteraudio.companion

import com.betteraudio.companion.model.PackMember
import com.betteraudio.data.db.entities.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionPackMatcherTest {

    private fun book(id: Long, title: String, author: String = "", titleOverride: String? = null, authorOverride: String? = null) = Book(
        id = id, title = title, author = author, titleOverride = titleOverride, authorOverride = authorOverride, folderPath = "/books/$id"
    )

    private fun member(title: String, author: String = "") = PackMember(bookRef = "m1", title = title, author = author)

    @Test
    fun `matches exact title and author`() {
        val candidates = listOf(book(1, "The Final Empire", "Brandon Sanderson"), book(2, "Warbreaker", "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("The Final Empire", "Brandon Sanderson"))
        assertEquals(1L, match?.id)
    }

    @Test
    fun `matches case and whitespace insensitively`() {
        val candidates = listOf(book(1, "The Final Empire", "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("  the FINAL   empire ", "  brandon sanderson "))
        assertEquals(1L, match?.id)
    }

    @Test
    fun `falls back to title only when member has no author`() {
        val candidates = listOf(book(1, "The Final Empire", "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("The Final Empire", ""))
        assertEquals(1L, match?.id)
    }

    @Test
    fun `falls back to title only when author mismatches every candidate`() {
        val candidates = listOf(book(1, "The Final Empire", "Someone Else"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("The Final Empire", "Brandon Sanderson"))
        assertEquals(1L, match?.id)
    }

    @Test
    fun `prefers author match over a title-only match when both exist`() {
        val candidates = listOf(book(1, "The Final Empire", "Wrong Author"), book(2, "The Final Empire", "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("The Final Empire", "Brandon Sanderson"))
        assertEquals(2L, match?.id)
    }

    @Test
    fun `matches against overrides not raw scanned fields`() {
        val candidates = listOf(book(1, "raw title", "raw author", titleOverride = "The Final Empire", authorOverride = "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("The Final Empire", "Brandon Sanderson"))
        assertEquals(1L, match?.id)
    }

    @Test
    fun `no match returns null`() {
        val candidates = listOf(book(1, "Warbreaker", "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("The Final Empire", "Brandon Sanderson"))
        assertNull(match)
    }

    @Test
    fun `blank member title never matches`() {
        val candidates = listOf(book(1, "The Final Empire", "Brandon Sanderson"))
        val match = CompanionPackMatcher.bestMatch(candidates, member("", ""))
        assertNull(match)
    }

    @Test
    fun `empty candidate list returns null`() {
        val match = CompanionPackMatcher.bestMatch(emptyList(), member("The Final Empire"))
        assertNull(match)
    }
}
