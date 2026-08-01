package com.betteraudio.playback

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChapterTimeline] is the single shared implementation of chapter-boundary math behind the
 * player's next/previous-chapter buttons, the chapter pill/scrubber, the chapter sheet, the
 * widget's chapter fields, and listening-history chapter tagging — a regression here silently
 * desyncs all of them from each other, which is exactly what having four+three independent
 * reimplementations used to risk.
 */
class ChapterTimelineTest {

    private fun file(id: Long, track: Int, name: String, durationMs: Long) =
        AudioFile(id = id, bookId = 1L, filePath = "/f$id", fileName = name, trackNumber = track, durationMs = durationMs)

    private fun chapter(
        id: Long, fileId: Long, title: String, startInFileMs: Long, durationMs: Long, orderIndex: Int,
        source: String = "embedded"
    ) = Chapter(id = id, bookId = 1L, fileId = fileId, title = title, startInFileMs = startInFileMs, durationMs = durationMs, orderIndex = orderIndex, source = source)

    // ── multi-file, per_file (chapter == file) ──────────────────────────────

    @Test
    fun `multi-file with no chapter rows falls back to one mark per file`() {
        val files = listOf(
            file(1, 0, "01.mp3", 60_000),
            file(2, 1, "02.mp3", 90_000),
            file(3, 2, "03.mp3", 30_000),
        )
        val tl = ChapterTimeline.build(files, emptyList(), bookId = 42L)
        assertEquals(3, tl.marks.size)
        assertFalse(tl.hasChapterData)
        assertEquals(0L, tl.marks[0].startMs)
        assertEquals(60_000L, tl.marks[1].startMs)
        assertEquals(150_000L, tl.marks[2].startMs)
        assertEquals(180_000L, tl.bookTotalMs)
        // last mark's end derives from bookTotalMs, not a stored duration
        assertEquals(180_000L, tl.marks[2].endMs)
    }

    @Test
    fun `ofFiles agrees with build's fallback on fileStartsMs and bookTotalMs`() {
        val files = listOf(file(1, 0, "a", 60_000), file(2, 1, "b", 40_000))
        val a = ChapterTimeline.ofFiles(files, bookId = 1L)
        val b = ChapterTimeline.build(files, emptyList(), bookId = 1L)
        assertEquals(a.fileStartsMs, b.fileStartsMs)
        assertEquals(a.bookTotalMs, b.bookTotalMs)
        assertFalse(a.hasChapterData)
        assertFalse(b.hasChapterData)
    }

    // ── single-file, embedded chapters ──────────────────────────────────────

    @Test
    fun `single-file embedded chapters produce marks spanning the whole file`() {
        val files = listOf(file(1, 0, "book.m4b", 3_600_000))
        val chapters = listOf(
            chapter(1, 1, "Ch 1", 0, 600_000, 0),
            chapter(2, 1, "Ch 2", 600_000, 900_000, 1),
            chapter(3, 1, "Ch 3", 1_500_000, 2_100_000, 2),
        )
        val tl = ChapterTimeline.build(files, chapters, bookId = 1L)
        assertTrue(tl.hasChapterData)
        assertEquals(3, tl.marks.size)
        assertEquals(0L, tl.marks[0].startMs)
        assertEquals(600_000L, tl.marks[1].startMs)
        assertEquals(1_500_000L, tl.marks[2].startMs)
        assertEquals(3_600_000L, tl.marks[2].endMs)
        assertEquals(3_600_000L, tl.bookTotalMs)
    }

    @Test
    fun `truncated last chapter still spans to the true end of the book`() {
        // Simulates the chpl u8 truncation: the last chapter's stored durationMs (rawDurationMs)
        // undershoots — endMs must NOT be derived from it.
        val files = listOf(file(1, 0, "book.m4b", 3_600_000))
        val chapters = listOf(
            chapter(1, 1, "Ch 1", 0, 600_000, 0),
            chapter(2, 1, "Ch 2 (truncated)", 600_000, 1_000, 1), // bogus short duration
        )
        val tl = ChapterTimeline.build(files, chapters, bookId = 1L)
        assertEquals(1_000L, tl.marks[1].rawDurationMs)
        assertEquals(3_600_000L, tl.marks[1].endMs) // derived, spans to real end
        assertEquals(3_000_000L, tl.marks[1].durationMs)
    }

    // ── edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `empty file list yields EMPTY`() {
        val tl = ChapterTimeline.build(emptyList(), emptyList(), bookId = 9L)
        assertTrue(tl.isEmpty)
        assertFalse(tl.hasMultiple)
    }

    @Test
    fun `single chapter has hasMultiple false`() {
        val files = listOf(file(1, 0, "a", 1000))
        val tl = ChapterTimeline.build(files, emptyList(), bookId = 1L)
        assertEquals(1, tl.marks.size)
        assertFalse(tl.hasMultiple)
    }

    @Test
    fun `orphan chapter fileId is dropped, not anchored at position 0`() {
        val files = listOf(file(1, 0, "a", 60_000), file(2, 1, "b", 60_000))
        val chapters = listOf(
            chapter(1, 1, "Ch 1", 0, 60_000, 0),
            chapter(2, 999L, "Orphan", 0, 60_000, 1), // fileId 999 doesn't exist
            chapter(3, 2, "Ch 2", 0, 60_000, 2),
        )
        val tl = ChapterTimeline.build(files, chapters, bookId = 1L)
        assertEquals(2, tl.marks.size)
        assertTrue(tl.marks.none { it.title == "Orphan" })
        assertEquals(0L, tl.marks[0].startMs)
        assertEquals(60_000L, tl.marks[1].startMs)
    }

    @Test
    fun `all chapters orphaned falls back to one mark per file`() {
        val files = listOf(file(1, 0, "a", 60_000))
        val chapters = listOf(chapter(1, 999L, "Orphan", 0, 60_000, 0))
        val tl = ChapterTimeline.build(files, chapters, bookId = 1L)
        assertFalse(tl.hasChapterData)
        assertEquals(1, tl.marks.size)
    }

    @Test
    fun `startInFileMs beyond file duration is clamped`() {
        val files = listOf(file(1, 0, "a", 60_000), file(2, 1, "b", 60_000))
        val chapters = listOf(
            chapter(1, 1, "Ch 1", 0, 60_000, 0),
            chapter(2, 1, "Ch 1 overflow", 999_999, 1000, 1), // way beyond file 1's duration
        )
        val tl = ChapterTimeline.build(files, chapters, bookId = 1L)
        // clamped to 60_000 (file 1's duration) -> startMs == 60_000, same as file 2's start
        assertEquals(60_000L, tl.marks[1].startMs)
    }

    // ── next / prev navigation ──────────────────────────────────────────────

    private fun threeChapterTimeline(): ChapterTimeline {
        val files = listOf(file(1, 0, "book.m4b", 900_000))
        val chapters = listOf(
            chapter(1, 1, "Ch 1", 0, 300_000, 0),
            chapter(2, 1, "Ch 2", 300_000, 300_000, 1),
            chapter(3, 1, "Ch 3", 600_000, 300_000, 2),
        )
        return ChapterTimeline.build(files, chapters, bookId = 1L)
    }

    @Test
    fun `next from mid-chapter goes to the very next boundary, not skipping one`() {
        val tl = threeChapterTimeline()
        // 250ms before the ch2 boundary — display tolerance would say "already ch2", but next()
        // must use zero tolerance so it doesn't skip ch2 entirely.
        assertEquals(300_000L, tl.nextStartMs(299_900L))
    }

    @Test
    fun `next at the last chapter returns null`() {
        val tl = threeChapterTimeline()
        assertNull(tl.nextStartMs(650_000L))
    }

    @Test
    fun `prev restarts current chapter when more than the threshold in`() {
        val tl = threeChapterTimeline()
        // 10s into chapter 2 (starts at 300_000) — restart it.
        assertEquals(300_000L, tl.prevStartMs(310_000L, restartThresholdMs = 3_000L))
    }

    @Test
    fun `prev steps to the real previous chapter when near the start`() {
        val tl = threeChapterTimeline()
        // 1s into chapter 2 — go to chapter 1's start.
        assertEquals(0L, tl.prevStartMs(301_000L, restartThresholdMs = 3_000L))
    }

    @Test
    fun `prev at the first chapter near its start returns 0`() {
        val tl = threeChapterTimeline()
        assertEquals(0L, tl.prevStartMs(500L, restartThresholdMs = 3_000L))
    }

    @Test
    fun `chapterAt uses display tolerance`() {
        val tl = threeChapterTimeline()
        // 200ms before the ch2 boundary, within the 250ms display tolerance -> reports ch2.
        val mark = tl.chapterAt(299_800L)
        assertEquals(1, mark?.index)
    }

    // ── locate ───────────────────────────────────────────────────────────────

    @Test
    fun `locate resolves a multi-file position to file and offset`() {
        val files = listOf(file(10, 0, "a", 60_000), file(20, 1, "b", 90_000))
        val tl = ChapterTimeline.build(files, emptyList(), bookId = 1L)
        val locus = tl.locate(75_000L)
        assertEquals(20L, locus?.fileId)
        assertEquals(1, locus?.fileIndex)
        assertEquals(15_000L, locus?.offsetInFileMs)
    }

    @Test
    fun `locate exactly on a file boundary lands in the new file at offset 0`() {
        val files = listOf(file(10, 0, "a", 60_000), file(20, 1, "b", 90_000))
        val tl = ChapterTimeline.build(files, emptyList(), bookId = 1L)
        val locus = tl.locate(60_000L)
        assertEquals(20L, locus?.fileId)
        assertEquals(0L, locus?.offsetInFileMs)
    }

    @Test
    fun `startOfFileMs returns the cumulative start for a given fileId`() {
        val files = listOf(file(10, 0, "a", 60_000), file(20, 1, "b", 90_000))
        val tl = ChapterTimeline.build(files, emptyList(), bookId = 1L)
        assertEquals(0L, tl.startOfFileMs(10))
        assertEquals(60_000L, tl.startOfFileMs(20))
        assertEquals(0L, tl.startOfFileMs(999)) // unknown id -> 0 fallback
    }
}
