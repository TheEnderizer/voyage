package com.betteraudio.data.diskstore

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [SuppressionGate]'s contract — specifically the property that makes [DiskMirror.suppressed]
 * safe when a WorkManager companion import overlaps a library scan. A Boolean implementation
 * passes the first two tests and fails `overlapping blocks stay suppressed until the LAST exits`,
 * which is the whole point of this file.
 */
class SuppressionGateTest {

    @Test
    fun `starts unsuppressed`() {
        val gate = SuppressionGate()
        assertFalse(gate.isSuppressed)
        assertEquals(0, gate.depth())
    }

    @Test
    fun `a single block suppresses then lifts`() {
        val gate = SuppressionGate()
        assertTrue("first enter is the outermost", gate.enter())
        assertTrue(gate.isSuppressed)
        assertTrue("only exit is the outermost", gate.exit())
        assertFalse(gate.isSuppressed)
    }

    @Test
    fun `nested blocks stay suppressed until the outer one exits`() {
        val gate = SuppressionGate()
        gate.enter()
        assertFalse("an inner enter is not the outermost", gate.enter())
        assertFalse("an inner exit is not the outermost", gate.exit())
        assertTrue("still suppressed while the outer block runs", gate.isSuppressed)
        assertTrue(gate.exit())
        assertFalse(gate.isSuppressed)
    }

    /**
     * The regression this type exists for. Interleaved (not nested) enter/exit — block A enters,
     * block B enters, **A exits first** — must leave suppression up for B. A Boolean flag cleared
     * by A's exit would report `isSuppressed == false` here while B is still mutating, letting a
     * concurrent flushDirty mirror B's half-applied book to disk.
     */
    @Test
    fun `overlapping blocks stay suppressed until the LAST exits`() {
        val gate = SuppressionGate()
        gate.enter()                        // A enters
        gate.enter()                        // B enters
        assertFalse("A exiting is not the outermost exit", gate.exit())
        assertTrue("B is still running — suppression must hold", gate.isSuppressed)
        assertTrue("B's exit is the outermost", gate.exit())
        assertFalse(gate.isSuppressed)
    }

    @Test
    fun `exactly one outermost exit is reported across many concurrent blocks`() = runTest {
        val gate = SuppressionGate()
        val blocks = 64
        val outermostExits = List(blocks) {
            async {
                gate.enter()
                // Suspends inside the block, so the coroutines genuinely interleave rather than
                // each running enter/exit to completion in turn.
                kotlinx.coroutines.yield()
                gate.exit()
            }
        }.awaitAll()

        assertEquals("only the last block out may drain", 1, outermostExits.count { it })
        assertFalse(gate.isSuppressed)
        assertEquals(0, gate.depth())
    }

    @Test
    fun `depth returns to zero after balanced enter and exit pairs`() {
        val gate = SuppressionGate()
        repeat(5) { gate.enter() }
        repeat(4) { assertFalse(gate.exit()) }
        assertEquals(1, gate.depth())
        assertTrue(gate.exit())
        assertEquals(0, gate.depth())
    }
}
