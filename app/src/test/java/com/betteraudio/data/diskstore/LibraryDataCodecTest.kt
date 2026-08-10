package com.betteraudio.data.diskstore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryDataCodecTest {

    private fun sample() = LibraryDocument(
        writtenAt = 1_754_476_800_000L,
        libraryRoot = "/storage/emulated/0/Audiobooks",
        presets = listOf(LibraryDocument.PresetEntry("Podcast", "BUNDLE", 1.4f, 3, null, true)),
        authors = listOf(LibraryDocument.AuthorEntry("Brandon Sanderson", "covers/author_brandon_sanderson.jpg")),
        series = listOf(
            LibraryDocument.SeriesEntry(
                name = "Mistborn", author = "Brandon Sanderson", narrator = null, description = null,
                playbackSpeed = null, boostDb = null, eqBandsJson = null, skipSilenceEnabled = null,
                cover = "covers/series_mistborn.jpg", createdAtMs = 1_740_000_000_000L,
                members = listOf(
                    LibraryDocument.MemberRef(
                        folderPath = "/Audiobooks/Sanderson/Mistborn", relPath = "Sanderson/Mistborn",
                        title = "Mistborn", author = "Brandon Sanderson", seriesOrder = 1.0f
                    )
                )
            )
        )
    )

    @Test
    fun `round-trips presets, authors and series with members`() {
        val original = sample()
        val decoded = LibraryDataCodec.decodeOrNull(LibraryDataCodec.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `unknown top-level keys survive a decode-encode cycle`() {
        val json = JSONObject(LibraryDataCodec.encodeToString(sample()))
        json.put("futureField", "x")
        val decoded = LibraryDataCodec.decodeOrNull(json.toString())!!
        assertEquals("x", decoded.unknown["futureField"])
    }

    @Test
    fun `corrupt input returns null instead of throwing`() {
        assertNull(LibraryDataCodec.decodeOrNull("{"))
    }

    @Test
    fun `missing arrays decode as empty lists`() {
        val decoded = LibraryDataCodec.decodeOrNull("""{"libraryRoot":"/x"}""")
        assertEquals(emptyList<Any>(), decoded!!.presets)
        assertEquals(emptyList<Any>(), decoded.authors)
        assertEquals(emptyList<Any>(), decoded.series)
    }
}
