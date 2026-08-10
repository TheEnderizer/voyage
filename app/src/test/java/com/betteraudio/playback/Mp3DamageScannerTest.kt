package com.betteraudio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scanner that locates damaged byte ranges. Fixtures are built in-memory from real MPEG frame
 * headers, so these run anywhere without shipping audio.
 */
class Mp3DamageScannerTest {

    /** One valid MPEG-1 Layer III frame, 256 kbps @ 44.1 kHz — 835 bytes, as in the real files. */
    private fun frame(): ByteArray {
        val b = ByteArray(835)
        b[0] = 0xFF.toByte()
        b[1] = 0xFB.toByte()   // MPEG1, Layer III, no CRC
        // bitrate index 13 (256 kbps), sample-rate index 0 (44.1 kHz), no padding.
        // 144 * 256000 / 44100 = 835 bytes, matching the real audiobook's frames.
        b[2] = 0xD0.toByte()
        b[3] = 0x00
        for (i in 4 until b.size) b[i] = (i % 97).toByte()
        return b
    }

    private fun frames(n: Int): ByteArray {
        val out = ByteArray(835 * n)
        repeat(n) { System.arraycopy(frame(), 0, out, it * 835, 835) }
        return out
    }

    /** Garbage that contains no valid frame sync. */
    private fun junk(n: Int) = ByteArray(n) { 0x42 }

    private fun writeTemp(bytes: ByteArray): String {
        val f = File.createTempFile("damage", ".mp3")
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f.absolutePath
    }

    @Test
    fun `a clean file reports no damage`() {
        val path = writeTemp(frames(50))
        assertEquals(emptyList<Mp3DamageScanner.Gap>(), Mp3DamageScanner.scan(path))
    }

    @Test
    fun `finds a single mid-file gap and its exact bounds`() {
        val good = frames(20)          // 16,700 bytes
        val bad = junk(300_000)        // far beyond ExoPlayer's 128 KB resync limit
        val tail = frames(20)
        val path = writeTemp(good + bad + tail)

        val gaps = Mp3DamageScanner.scan(path)
        assertEquals(1, gaps.size)
        assertEquals(good.size.toLong(), gaps[0].start)
        assertEquals((good.size + bad.size).toLong(), gaps[0].end)
        assertEquals(300_000L, gaps[0].size)
    }

    @Test
    fun `finds several gaps, matching the shape of a real damaged audiobook`() {
        // Mirrors the measured layout: a short run of audio, a big gap, then more of each.
        val parts = listOf(frames(10), junk(306_382), frames(200), junk(302_991), frames(200))
        val path = writeTemp(parts.reduce { a, b -> a + b })

        val gaps = Mp3DamageScanner.scan(path)
        assertEquals(2, gaps.size)
        assertEquals(306_382L, gaps[0].size)
        assertEquals(302_991L, gaps[1].size)
        // ascending and non-overlapping
        assertTrue(gaps[0].end <= gaps[1].start)
    }

    @Test
    fun `damage running to the end of the file is reported`() {
        val path = writeTemp(frames(20) + junk(200_000))
        val gaps = Mp3DamageScanner.scan(path)
        assertEquals(1, gaps.size)
        assertEquals((835L * 20), gaps[0].start)
        assertEquals((835L * 20) + 200_000L, gaps[0].end)
    }

    @Test
    fun `an ID3v2 tag is metadata, not damage`() {
        // ID3v2.3 header declaring a 1000-byte body, then audio.
        val tagBody = 1000
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            0, 0, (tagBody shr 7).toByte(), (tagBody and 0x7f).toByte()
        )
        val path = writeTemp(header + ByteArray(tagBody) { 0x11 } + frames(20))
        assertEquals(10L + tagBody, Mp3DamageScanner.id3TagSizeBytes(File(path)))
        assertEquals(emptyList<Mp3DamageScanner.Gap>(), Mp3DamageScanner.scan(path))
    }

    @Test
    fun `a missing or empty file scans clean rather than throwing`() {
        assertEquals(emptyList<Mp3DamageScanner.Gap>(), Mp3DamageScanner.scan("/nope/missing.mp3"))
        assertEquals(emptyList<Mp3DamageScanner.Gap>(), Mp3DamageScanner.scan(writeTemp(ByteArray(0))))
    }

    @Test
    fun `cancellation stops the scan`() {
        val path = writeTemp(frames(20) + junk(300_000) + frames(20))
        assertEquals(emptyList<Mp3DamageScanner.Gap>(), Mp3DamageScanner.scan(path) { false })
    }

    // ── Not-an-MP3 guards ─────────────────────────────────────────────────────
    // Regression cover for the bug where an 8-part M4A audiobook was permanently bricked: the
    // scanner found no MPEG frames, reported `0-<fileSize>` for every part, and GapSkippingDataSource
    // then served an empty stream. Nothing ever cleared the cached result.

    /** A minimal MP4: an `ftyp` box, then a `mdat` box of non-frame bytes. */
    private fun mp4Bytes(payload: Int): ByteArray {
        fun box(size: Int, type: String) = byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()
        ) + type.toByteArray(Charsets.US_ASCII)
        val ftyp = box(20, "ftyp") + "M4A ".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 2, 0)
        return ftyp + box(payload + 8, "mdat") + junk(payload)
    }

    @Test
    fun `a file with no MPEG frame anywhere is reported clean, not wholly damaged`() {
        val path = writeTemp(mp4Bytes(400_000))
        assertEquals(emptyList<Mp3DamageScanner.Gap>(), Mp3DamageScanner.scan(path))
    }

    @Test
    fun `isMp3Path allowlists mp3 and nothing else`() {
        assertTrue(Mp3DamageScanner.isMp3Path("/a/b/Part 1.mp3"))
        assertTrue(Mp3DamageScanner.isMp3Path("/a/b/UPPER.MP3"))
        // Every one of these produced a whole-file "damage" range before the gate existed.
        for (ext in listOf("m4a", "m4b", "mp4", "aac", "ogg", "flac", "opus", "wav")) {
            assertFalse(ext, Mp3DamageScanner.isMp3Path("/a/b/Part 1.$ext"))
        }
        assertFalse(Mp3DamageScanner.isMp3Path("/a/b/noextension"))
    }

    @Test
    fun `decodeUsable discards a map that would leave nothing to play`() {
        val size = 970_174_700L
        // Exactly the shape found in the user's book.json: byte 0 to EOF.
        assertEquals(
            emptyList<Mp3DamageScanner.Gap>(),
            Mp3DamageScanner.decodeUsable("0-$size", size)
        )
    }

    @Test
    fun `decodeUsable discards a whole-file MP3 map that starts after the ID3 tag`() {
        // The MP3 form of the same poison: scan() begins past the tag, so the range is
        // `<tag>-<size>` and the surviving logical stream is the tag's size — non-zero, which is
        // why the guard measures remaining bytes rather than testing for a full-file range.
        val size = 500_000_000L
        val tag = 2_048L
        assertEquals(
            emptyList<Mp3DamageScanner.Gap>(),
            Mp3DamageScanner.decodeUsable("$tag-$size", size)
        )
    }

    @Test
    fun `decodeUsable keeps a real damage map`() {
        // The measured real-world case: ~2.4 MB of damage in an 898 MB file.
        val size = 898_000_000L
        val encoded = (0 until 8).joinToString(",") { i ->
            val start = 10_000_000L + i * 100_000_000L
            "$start-${start + 300_000L}"
        }
        val gaps = Mp3DamageScanner.decodeUsable(encoded, size)
        assertEquals(8, gaps.size)
        assertEquals(2_400_000L, gaps.sumOf { it.size })
    }

    @Test
    fun `decodeUsable trusts the map when the file size is unknown`() {
        val gaps = Mp3DamageScanner.decodeUsable("100-400", 0L)
        assertEquals(1, gaps.size)
        assertEquals(300L, gaps[0].size)
    }
}
