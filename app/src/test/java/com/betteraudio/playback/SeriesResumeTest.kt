package com.betteraudio.playback

import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.PlaybackProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesResumeTest {

    private fun book(id: Long, status: BookStatus = BookStatus.NOT_STARTED) = Book(
        id = id, title = "Book $id", author = "a", folderPath = "/lib/$id",
        totalDurationMs = 1_000L, addedDateMs = 0L, status = status, fileCount = 1
    )

    private fun played(bookId: Long, at: Long) =
        bookId to PlaybackProgress(bookId = bookId, lastPlayedMs = at)

    @Test
    fun `an empty series has no resume book`() {
        assertNull(SeriesResume.pick(emptyList(), emptyMap()))
    }

    @Test
    fun `the most recently played member wins even when it is not the earliest unfinished`() {
        val books = listOf(book(1), book(2), book(3))
        val pick = SeriesResume.pick(books, mapOf(played(1, 500L), played(3, 900L)))
        assertEquals(3L, pick?.book?.id)
        assertEquals("most-recently-played", pick?.reason)
    }

    @Test
    fun `a never-played series falls back to the first unfinished member, in series order`() {
        // Book 1 finished, 2 and 3 not — and "series order" means list order, not id order, which
        // is why the list is deliberately shuffled relative to the ids.
        val books = listOf(book(3, BookStatus.FINISHED), book(2), book(1))
        val pick = SeriesResume.pick(books, emptyMap())
        assertEquals(2L, pick?.book?.id)
        assertEquals("first-unfinished", pick?.reason)
    }

    @Test
    fun `a fully finished series resumes at its first member`() {
        val books = listOf(book(1, BookStatus.FINISHED), book(2, BookStatus.FINISHED))
        val pick = SeriesResume.pick(books, emptyMap())
        assertEquals(1L, pick?.book?.id)
        assertEquals("first (all finished)", pick?.reason)
    }

    @Test
    fun `an explicit start book short-circuits every other rule`() {
        val books = listOf(book(1), book(2), book(3))
        val pick = SeriesResume.pick(books, mapOf(played(1, 999L)), startBookId = 3L)
        assertEquals(3L, pick?.book?.id)
        assertEquals("explicit", pick?.reason)
    }

    @Test
    fun `an explicit id that is not a member is ignored rather than honoured`() {
        // Guards the Companion entry point: handing in a stale book id must not make a
        // non-member's sheet open under this series' name.
        val books = listOf(book(1), book(2))
        val pick = SeriesResume.pick(books, mapOf(played(2, 100L)), startBookId = 99L)
        assertEquals(2L, pick?.book?.id)
        assertEquals("most-recently-played", pick?.reason)
    }

    @Test
    fun `a progress row that exists but was never played does not count as played`() {
        // `lastPlayedMs == 0` is the "row exists, never listened" case — treating it as played
        // would pin the resume book to whichever member happened to get a row first.
        val books = listOf(book(1, BookStatus.FINISHED), book(2))
        val progress = mapOf(1L to PlaybackProgress(bookId = 1L, lastPlayedMs = 0L))
        val pick = SeriesResume.pick(books, progress)
        assertEquals(2L, pick?.book?.id)
        assertEquals("first-unfinished", pick?.reason)
    }
}
