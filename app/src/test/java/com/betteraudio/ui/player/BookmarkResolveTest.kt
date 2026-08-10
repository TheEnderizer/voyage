package com.betteraudio.ui.player

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.playback.ChapterTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A bookmark is stored as a file-relative anchor (`fileId` + `positionInFileMs`) precisely so that
 * deleting one file from a book leaves every other bookmark pointing at the same audio. That
 * property only holds if surviving files keep their DB ids across a rescan (see
 * `AudioFileScanner.dropMissingFiles`) AND if resolution re-derives the absolute offset from the
 * anchor rather than trusting the offset frozen at creation time — these tests pin the second half.
 */
class BookmarkResolveTest {

    private fun file(id: Long, track: Int, durationMs: Long) =
        AudioFile(id = id, bookId = 1L, filePath = "/f$id", fileName = "f$id.mp3", trackNumber = track, durationMs = durationMs)

    /** 3 files of 10 min each; book starts at 0/600_000/1_200_000. */
    private val files = listOf(file(1, 1, 600_000), file(2, 2, 600_000), file(3, 3, 600_000))
    private val timeline = ChapterTimeline.ofFiles(files, bookId = 1L)

    /** Bookmark 3:21 into the second file — the user's own example. */
    private val bm = Bookmark(
        id = 7, bookId = 1L, fileId = 2L, positionInFileMs = 201_000,
        absolutePositionMs = 801_000, comment = ""
    )

    @Test
    fun `resolves to the file start plus the in-file offset`() {
        val ui = resolveBookmark(bm, timeline)
        assertEquals(801_000L, ui.absPositionMs)
        assertEquals(201_000L, ui.positionInChapterMs)
        assertFalse(ui.fileMissing)
    }

    @Test
    fun `still points at the same audio after an earlier file is deleted`() {
        // File 1 is gone; files 2 and 3 keep their ids, so the book is now 20 minutes long and the
        // bookmark's audio sits at 3:21 — NOT at the stored absolutePositionMs of 13:21.
        val shortened = ChapterTimeline.ofFiles(listOf(files[1], files[2]), bookId = 1L)
        val ui = resolveBookmark(bm, shortened)
        assertEquals(201_000L, ui.absPositionMs)
        assertEquals(201_000L, ui.positionInChapterMs)
        assertFalse(ui.fileMissing)
    }

    @Test
    fun `is unaffected by a later file being deleted`() {
        val shortened = ChapterTimeline.ofFiles(listOf(files[0], files[1]), bookId = 1L)
        assertEquals(801_000L, resolveBookmark(bm, shortened).absPositionMs)
    }

    @Test
    fun `falls back to the stored offset and names no chapter when its own file is gone`() {
        val without2 = ChapterTimeline.ofFiles(listOf(files[0], files[2]), bookId = 1L)
        val ui = resolveBookmark(bm, without2)
        assertTrue(ui.fileMissing)
        assertEquals(801_000L, ui.absPositionMs)
        // No chapter is claimed — the fallback offset can land past the end of the shortened book,
        // where chapterAt() clamps to the last mark and would name the wrong chapter.
        assertEquals("", ui.chapterName)
    }

    @Test
    fun `an unloaded timeline is not treated as a missing file`() {
        val ui = resolveBookmark(bm, ChapterTimeline.EMPTY)
        assertFalse(ui.fileMissing)
        assertEquals(801_000L, ui.absPositionMs)
    }

    @Test
    fun `locate round-trips through startOfFileMs for every file boundary`() {
        for (abs in listOf(0L, 599_999L, 600_000L, 1_200_000L, 1_799_999L)) {
            val locus = timeline.locate(abs)!!
            assertEquals(abs, timeline.startOfFileMs(locus.fileId) + locus.offsetInFileMs)
        }
    }
}
