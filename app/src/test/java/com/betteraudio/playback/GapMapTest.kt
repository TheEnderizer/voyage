package com.betteraudio.playback

import com.betteraudio.playback.Mp3DamageScanner.Gap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The logical↔physical mapping behind [GapSkippingDataSource]. Worth testing hard: an off-by-one
 * here does not fail loudly, it silently feeds the decoder misaligned bytes.
 */
class GapMapTest {

    @Test
    fun `no gaps is an identity mapping`() {
        val m = GapMap(emptyList(), physicalSize = 1000)
        assertEquals(1000, m.logicalSize)
        assertEquals(0, m.toPhysical(0))
        assertEquals(500, m.toPhysical(500))
        assertEquals(1000, m.bytesUntilNextGap(0))
    }

    @Test
    fun `a single gap shifts everything after it`() {
        // physical [0,100) good, [100,300) damaged, [300,1000) good
        val m = GapMap(listOf(Gap(100, 300)), physicalSize = 1000)
        assertEquals(800, m.logicalSize)
        assertEquals(0, m.toPhysical(0))
        assertEquals(99, m.toPhysical(99))
        // logical 100 is the first byte AFTER the gap
        assertEquals(300, m.toPhysical(100))
        assertEquals(301, m.toPhysical(101))
        assertEquals(999, m.toPhysical(799))
        // readable run before the gap, then to EOF after it
        assertEquals(100, m.bytesUntilNextGap(0))
        assertEquals(1, m.bytesUntilNextGap(99))
        assertEquals(700, m.bytesUntilNextGap(300))
    }

    @Test
    fun `multiple gaps accumulate`() {
        val m = GapMap(listOf(Gap(100, 200), Gap(500, 600)), physicalSize = 1000)
        assertEquals(800, m.logicalSize)
        assertEquals(50, m.toPhysical(50))
        assertEquals(200, m.toPhysical(100))   // past gap 1
        assertEquals(399, m.toPhysical(299))   // still between the gaps
        assertEquals(600, m.toPhysical(400))   // past gap 2 as well
        assertEquals(799, m.toPhysical(599))
    }

    @Test
    fun `a gap at the very start is skipped`() {
        val m = GapMap(listOf(Gap(0, 250)), physicalSize = 1000)
        assertEquals(750, m.logicalSize)
        assertEquals(250, m.toPhysical(0))
        assertEquals(750, m.bytesUntilNextGap(250))
    }

    @Test
    fun `a gap running to EOF just shortens the stream`() {
        val m = GapMap(listOf(Gap(900, 1000)), physicalSize = 1000)
        assertEquals(900, m.logicalSize)
        assertEquals(899, m.toPhysical(899))
        assertEquals(900, m.bytesUntilNextGap(0))
    }

    @Test
    fun `overlapping and touching gaps are merged`() {
        val m = GapMap(listOf(Gap(100, 200), Gap(150, 300), Gap(300, 350)), physicalSize = 1000)
        assertEquals(listOf(Gap(100, 350)), m.gaps)
        assertEquals(750, m.logicalSize)
        assertEquals(350, m.toPhysical(100))
    }

    @Test
    fun `unsorted input is ordered and out-of-range input is clamped`() {
        val m = GapMap(
            listOf(Gap(500, 600), Gap(100, 200), Gap(900, 5000), Gap(-50, 10)),
            physicalSize = 1000
        )
        assertEquals(listOf(Gap(0, 10), Gap(100, 200), Gap(500, 600), Gap(900, 1000)), m.gaps)
        assertTrue(m.logicalSize < 1000)
        // degenerate/inverted ranges are dropped rather than corrupting the walk
        assertEquals(emptyList<Gap>(), GapMap(listOf(Gap(50, 50), Gap(90, 10)), 1000).gaps)
    }

    /**
     * The real proof: drive the exact loop [GapSkippingDataSource.read] uses over a synthetic
     * "file" and assert the bytes handed to the decoder are byte-identical to the same file with
     * the damaged ranges physically cut out.
     */
    @Test
    fun `simulated reads reproduce the spliced stream exactly`() {
        val physical = ByteArray(1000) { (it % 251).toByte() }
        val gaps = listOf(Gap(100, 300), Gap(700, 750))
        val m = GapMap(gaps, physical.size.toLong())

        val expected = physical.copyOfRange(0, 100) +
            physical.copyOfRange(300, 700) +
            physical.copyOfRange(750, 1000)
        assertEquals(expected.size.toLong(), m.logicalSize)

        // Read with an awkward buffer size so gap edges land mid-buffer, as they would in practice.
        val out = ByteArray(m.logicalSize.toInt())
        var logicalPos = 0L
        var remaining = m.logicalSize
        var guard = 0
        while (remaining > 0) {
            if (guard++ > 10_000) error("read loop failed to terminate")
            val phys = m.toPhysical(logicalPos)
            val room = minOf(37L, remaining, m.bytesUntilNextGap(phys)).toInt()
            assertTrue("must always make progress", room > 0)
            System.arraycopy(physical, phys.toInt(), out, logicalPos.toInt(), room)
            logicalPos += room
            remaining -= room
        }
        assertArrayEquals(expected, out)
    }

    @Test
    fun `encode and decode round-trip`() {
        val gaps = listOf(Gap(33_688, 340_070), Gap(126_420_800, 126_723_791))
        assertEquals("33688-340070,126420800-126723791", Mp3DamageScanner.encode(gaps))
        assertEquals(gaps, Mp3DamageScanner.decode(Mp3DamageScanner.encode(gaps)))
        // null/blank = never scanned, empty = scanned clean; both mean "nothing to skip"
        assertEquals(emptyList<Gap>(), Mp3DamageScanner.decode(null))
        assertEquals(emptyList<Gap>(), Mp3DamageScanner.decode(""))
        // malformed entries are dropped, not fatal
        assertEquals(listOf(Gap(5, 9)), Mp3DamageScanner.decode("junk,5-9,7-,-3,20-10"))
    }
}
