package com.betteraudio.data.diskstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDataCodecTest {

    private fun sample() = BookDocument(
        writtenAt = 1_754_476_800_000L,
        app = "1.9.14b",
        folderPath = "/Audiobooks/Sanderson/Mistborn",
        relPath = "Sanderson/Mistborn",
        title = "Mistborn",
        author = "Brandon Sanderson",
        titleOverride = "Mistborn: The Final Empire",
        narrator = "Michael Kramer",
        genre = "Fantasy",
        year = 2006,
        status = "IN_PROGRESS",
        addedDateMs = 1_740_000_000_000L,
        totalDurationMs = 90_000_000L,
        fileCount = 24,
        cover = BookDocument.CoverInfo("data/cover.jpg", "user", 1_754_400_000_000L),
        series = BookDocument.SeriesRef("Mistborn", 1.0f),
        ebook = BookDocument.EbookInfo("mistborn.epub", 62, listOf(0, 1, 2)),
        files = listOf(BookDocument.FileEntry("01.mp3", 3_600_000L, 1, "Prologue", "Prologue", "")),
        progress = BookDocument.ProgressEntry(
            positionMs = 123_456L, lastPlayedMs = 1_754_470_000_000L,
            currentFile = BookDocument.CurrentFileRef("05.mp3", 3_600_000L),
            playbackSpeed = 1.25f, boostDb = 3, eqBandsJson = "[0,2,0,0,-1]",
            isCompleted = false, completedDateMs = null, lastPausedAt = 1_754_469_000_000L,
            textSpineIndex = 12, textFraction = 0.4f, textCharOffset = 8_842, textOverallFraction = 0.19f, lastMode = "AUDIO"
        ),
        bookmarks = listOf(BookDocument.BookmarkEntry("05.mp3", 1000, 2000, "", 1_754_000_000_000L)),
        sessions = listOf(BookDocument.SessionEntry(1, 2, 0, "", 0, "", 0, 0, 0, 0)),
        skipEvents = listOf(
            BookDocument.SkipEventEntry(1, "AUDIO", "jump", 0, 0, -1, "", null, null, null, null, null)
        )
    )

    @Test
    fun `round-trips every field`() {
        val original = sample()
        val decoded = BookDataCodec.decodeOrNull(BookDataCodec.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown top-level keys survive a decode-encode cycle`() {
        val json = JSONObject(BookDataCodec.encodeToString(sample()))
        json.put("futureField", "from a newer build")
        val decoded = BookDataCodec.decodeOrNull(json.toString())!!
        assertEquals("from a newer build", decoded.unknown["futureField"])

        val reEncoded = JSONObject(BookDataCodec.encodeToString(decoded))
        assertEquals("from a newer build", reEncoded.getString("futureField"))
    }

    @Test
    fun `unknown nested keys inside progress survive a decode-encode cycle`() {
        val json = JSONObject(BookDataCodec.encodeToString(sample()))
        json.getJSONObject("progress").put("futureProgressField", 42)
        val decoded = BookDataCodec.decodeOrNull(json.toString())!!
        assertEquals(42, decoded.progress!!.unknown["futureProgressField"])

        val reEncoded = JSONObject(BookDataCodec.encodeToString(decoded))
        assertEquals(42, reEncoded.getJSONObject("progress").getInt("futureProgressField"))
    }

    @Test
    fun `current fields always win over a stale unknown snapshot`() {
        // Simulates round-tripping through an older build that doesn't understand "status": the
        // unknown bag must never be allowed to shadow a key the current encoder already wrote.
        val json = JSONObject(BookDataCodec.encodeToString(sample()))
        val decoded = BookDataCodec.decodeOrNull(json.toString())!!
        val mutatedUnknown = decoded.copy(unknown = decoded.unknown + ("status" to "SHOULD_NOT_APPEAR"))
        val reEncoded = JSONObject(BookDataCodec.encodeToString(mutatedUnknown))
        assertEquals("IN_PROGRESS", reEncoded.getString("status"))
    }

    @Test
    fun `missing fields fall back to safe defaults instead of throwing`() {
        val minimal = """{"folderPath":"/x","title":"T","author":"A"}"""
        val decoded = BookDataCodec.decodeOrNull(minimal)
        assertEquals(BookDocument.CURRENT_VERSION, decoded!!.version)
        assertEquals("NOT_STARTED", decoded.status)
        assertEquals("AUDIO", decoded.kind)
        assertTrue(decoded.files.isEmpty())
        assertNull(decoded.progress)
    }

    @Test
    fun `a v1 doc written before textCharOffset existed decodes it as null`() {
        val json = JSONObject(BookDataCodec.encodeToString(sample()))
        json.getJSONObject("progress").remove("textCharOffset")
        json.put("version", 1)
        val decoded = BookDataCodec.decodeOrNull(json.toString())
        assertNull(decoded!!.progress!!.textCharOffset)
        // The rest of progress is untouched by the missing field.
        assertEquals(0.4f, decoded.progress!!.textFraction)
    }

    @Test
    fun `corrupt input returns null instead of throwing`() {
        assertNull(BookDataCodec.decodeOrNull("{not valid json"))
        assertNull(BookDataCodec.decodeOrNull(""))
    }

    @Test
    fun `an unrecognized version number still parses`() {
        val json = JSONObject(BookDataCodec.encodeToString(sample()))
        json.put("version", 99)
        val decoded = BookDataCodec.decodeOrNull(json.toString())
        assertEquals(99, decoded!!.version)
        assertEquals("Mistborn", decoded.title)
    }

    @Test
    fun `optional null fields are omitted, not written as JSON null`() {
        val minimal = BookDocument(
            writtenAt = 1L, app = "1.0", folderPath = "/x", relPath = "x",
            title = "T", author = "A", status = "NOT_STARTED"
        )
        val json = JSONObject(BookDataCodec.encodeToString(minimal))
        assertTrue(!json.has("titleOverride"))
        assertTrue(!json.has("cover"))
        assertTrue(!json.has("progress"))
    }
}
