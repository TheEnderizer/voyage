package com.betteraudio.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Table-driven tests for the AUTO-mode clustering rules, using the exact filename patterns
 * the code comments (and the emulator seed library) call out as the cases these rules exist
 * to handle. These rules silently shatter or merge a user's books when they regress, so each
 * test pins down the actual book split a real folder of that shape must produce.
 */
class ScannerHeuristicsTest {

    private fun f(name: String) = File(name)

    @Test
    fun `looksLikePartFolder matches an N-of-M disc folder`() {
        assertTrue(ScannerHeuristics.looksLikePartFolder(f("Mistborn 1 - The Final Empire (1 of 3)")))
        assertTrue(ScannerHeuristics.looksLikePartFolder(f("Mistborn 1 - The Final Empire (2 of 3)")))
        assertTrue(ScannerHeuristics.looksLikePartFolder(f("Disc 2")))
        assertTrue(ScannerHeuristics.looksLikePartFolder(f("CD1")))
    }

    @Test
    fun `looksLikePartFolder rejects an ordinary book folder name`() {
        assertFalse(ScannerHeuristics.looksLikePartFolder(f("Mistborn 1 - The Final Empire")))
        assertFalse(ScannerHeuristics.looksLikePartFolder(f("Shadow Slave Volume 7")))
    }

    @Test
    fun `extractTrackNumber reads a leading number, else MAX_VALUE`() {
        assertEquals(1, ScannerHeuristics.extractTrackNumber("01 - Chapter One"))
        assertEquals(12, ScannerHeuristics.extractTrackNumber("12.Chapter Twelve"))
        assertEquals(Int.MAX_VALUE, ScannerHeuristics.extractTrackNumber("Chapter One"))
    }

    @Test
    fun `clusterBySimilarName keeps a plain sequential chapter list as one book`() {
        val files = listOf(
            f("01 - Chapter One.mp3"),
            f("02 - Chapter Two.mp3"),
            f("03 - Chapter Three.mp3")
        )
        val result = ScannerHeuristics.clusterBySimilarName(files)
        assertEquals(1, result.size)
        assertEquals(3, result.single().second.size)
    }

    @Test
    fun `clusterBySimilarName does not shatter a multi-part book split by pt N`() {
        // "pt" must NOT be treated as a genuine volume boundary — these three files are one book.
        val files = listOf(
            f("The Long Story pt 1.mp3"),
            f("The Long Story pt 2.mp3"),
            f("The Long Story pt 3.mp3")
        )
        val result = ScannerHeuristics.clusterBySimilarName(files)
        assertEquals(1, result.size)
        assertEquals(3, result.single().second.size)
    }

    @Test
    fun `clusterBySimilarName splits Shadow Slave Volume N files into distinct books`() {
        val files = listOf(
            f("Shadow Slave Volume 7.mp3"),
            f("Shadow Slave Volume 8.mp3"),
            f("Shadow Slave Volume 9.mp3")
        )
        val result = ScannerHeuristics.clusterBySimilarName(files)
        assertEquals(3, result.size)
        result.forEach { assertEquals(1, it.second.size) }
    }

    @Test
    fun `clusterBySimilarName splits two genuine multi-file sequences into two books`() {
        val files = listOf(
            f("Book One - 01.mp3"), f("Book One - 02.mp3"),
            f("Book Two - 01.mp3"), f("Book Two - 02.mp3")
        )
        val result = ScannerHeuristics.clusterBySimilarName(files)
        assertEquals(2, result.size)
        result.forEach { assertEquals(2, it.second.size) }
    }

    @Test
    fun `stemKey strips leading and trailing sequence tokens`() {
        assertEquals("chapter one", ScannerHeuristics.stemKey("01 - Chapter One"))
        assertEquals("the long story", ScannerHeuristics.stemKey("The Long Story pt 1"))
    }

    @Test
    fun `splitBySequentialVolumeNumber refuses a folder with a repeated volume number`() {
        val files = listOf(f("Shadow Slave Volume 7.mp3"), f("Shadow Slave Volume 7 (copy).mp3"))
        assertNull(ScannerHeuristics.splitBySequentialVolumeNumber(files))
    }
}
