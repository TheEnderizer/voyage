package com.betteraudio.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMatcherTest {

    private fun identity(folderPath: String, relPath: String = "", title: String = "Title", author: String = "Author") =
        BookIdentity(folderPath, relPath, title, author)

    @Test
    fun `exact folderPath match wins over everything else`() {
        val backup = identity(folderPath = "/lib/book1", relPath = "book1", title = "Wrong Title")
        val current = listOf(
            BookCandidate(1L, identity(folderPath = "/lib/book1", relPath = "book1", title = "Title")),
            BookCandidate(2L, identity(folderPath = "/other/book1", relPath = "book1", title = "Wrong Title"))
        )
        val result = BackupMatcher.matchBooks(listOf(backup), current).single()
        assertEquals(MatchResult.Matched(1L), result)
    }

    @Test
    fun `falls back to relPath when folderPath differs (moved library root)`() {
        val backup = identity(folderPath = "/old-root/author/book1", relPath = "author/book1")
        val current = listOf(
            BookCandidate(5L, identity(folderPath = "/new-root/author/book1", relPath = "author/book1"))
        )
        val result = BackupMatcher.matchBooks(listOf(backup), current).single()
        assertEquals(MatchResult.Matched(5L), result)
    }

    @Test
    fun `falls back to normalized title and author when paths never matched`() {
        val backup = identity(folderPath = "/gone", relPath = "gone", title = "  The Book  ", author = "AUTHOR NAME")
        val current = listOf(
            BookCandidate(7L, identity(folderPath = "/new/path", relPath = "new/path", title = "the book", author = "author name"))
        )
        val result = BackupMatcher.matchBooks(listOf(backup), current).single()
        assertEquals(MatchResult.Matched(7L), result)
    }

    @Test
    fun `ambiguous title-author match is not silently guessed`() {
        val backup = identity(folderPath = "/gone", relPath = "gone", title = "Duplicate", author = "Same Author")
        val current = listOf(
            BookCandidate(1L, identity(folderPath = "/a", relPath = "a", title = "Duplicate", author = "Same Author")),
            BookCandidate(2L, identity(folderPath = "/b", relPath = "b", title = "Duplicate", author = "Same Author"))
        )
        val result = BackupMatcher.matchBooks(listOf(backup), current).single()
        assertEquals(MatchResult.Ambiguous, result)
    }

    @Test
    fun `no match anywhere returns NoMatch`() {
        val backup = identity(folderPath = "/gone", relPath = "gone", title = "Nothing Like This", author = "Nobody")
        val current = listOf(
            BookCandidate(1L, identity(folderPath = "/a", relPath = "a", title = "Something Else", author = "Someone Else"))
        )
        val result = BackupMatcher.matchBooks(listOf(backup), current).single()
        assertEquals(MatchResult.NoMatch, result)
    }

    @Test
    fun `ambiguous folderPath does not fall through to a lower tier`() {
        // Pathological but must not silently pick one — if two current rows somehow share a
        // folderPath (should never happen, but the matcher must not paper over it).
        val backup = identity(folderPath = "/dup", relPath = "dup", title = "T", author = "A")
        val current = listOf(
            BookCandidate(1L, identity(folderPath = "/dup", relPath = "dup", title = "T", author = "A")),
            BookCandidate(2L, identity(folderPath = "/dup", relPath = "other", title = "Different", author = "Other"))
        )
        val result = BackupMatcher.matchBooks(listOf(backup), current).single()
        assertEquals(MatchResult.Ambiguous, result)
    }

    @Test
    fun `matchFile resolves exact basename`() {
        val candidates = listOf(
            FileCandidate(1L, "ch01.mp3", 60_000L),
            FileCandidate(2L, "ch02.mp3", 60_000L)
        )
        assertEquals(2L, BackupMatcher.matchFile("ch02.mp3", 60_000L, candidates))
    }

    @Test
    fun `matchFile falls back to case-insensitive basename`() {
        val candidates = listOf(FileCandidate(1L, "Chapter One.mp3", 100_000L))
        assertEquals(1L, BackupMatcher.matchFile("chapter one.mp3", 100_000L, candidates))
    }

    @Test
    fun `matchFile tiebreaks same-name collisions by closest duration`() {
        val candidates = listOf(
            FileCandidate(1L, "track.mp3", 50_000L),
            FileCandidate(2L, "track.mp3", 90_000L)
        )
        assertEquals(2L, BackupMatcher.matchFile("track.mp3", 95_000L, candidates))
        assertEquals(1L, BackupMatcher.matchFile("track.mp3", 55_000L, candidates))
    }

    @Test
    fun `matchFile returns null when nothing matches`() {
        val candidates = listOf(FileCandidate(1L, "a.mp3", 1_000L))
        assertTrue(BackupMatcher.matchFile("b.mp3", 1_000L, candidates) == null)
    }

    @Test
    fun `matchFile on empty candidates returns null`() {
        assertTrue(BackupMatcher.matchFile("a.mp3", 1_000L, emptyList()) == null)
    }
}
