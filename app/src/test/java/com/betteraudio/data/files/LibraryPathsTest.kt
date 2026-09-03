package com.betteraudio.data.files

import com.betteraudio.data.scanner.ImportStructure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LibraryPathsTest {

    @Test
    fun `AUTO places one flat folder under root`() {
        val f = LibraryPaths.targetFolder("The Final Empire", "Brandon Sanderson", null, ImportStructure.AUTO, "/lib")
        assertEquals(File("/lib/The Final Empire"), f)
    }

    @Test
    fun `AUTHOR_SERIES_BOOK nests author then series then title`() {
        val f = LibraryPaths.targetFolder("The Final Empire", "Brandon Sanderson", "Mistborn", ImportStructure.AUTHOR_SERIES_BOOK, "/lib")
        assertEquals(File("/lib/Brandon Sanderson/Mistborn/The Final Empire"), f)
    }

    @Test
    fun `AUTHOR_SERIES_BOOK with no series skips that level`() {
        val f = LibraryPaths.targetFolder("Warbreaker", "Brandon Sanderson", null, ImportStructure.AUTHOR_SERIES_BOOK, "/lib")
        assertEquals(File("/lib/Brandon Sanderson/Warbreaker"), f)
    }

    @Test
    fun `AUTHOR_DASH_SERIES_BOOK combines author and series with a dash`() {
        val f = LibraryPaths.targetFolder("The Final Empire", "Brandon Sanderson", "Mistborn", ImportStructure.AUTHOR_DASH_SERIES_BOOK, "/lib")
        assertEquals(File("/lib/Brandon Sanderson - Mistborn/The Final Empire"), f)
    }

    @Test
    fun `AUTHOR_DASH_SERIES_BOOK with only author has no dash`() {
        val f = LibraryPaths.targetFolder("Warbreaker", "Brandon Sanderson", null, ImportStructure.AUTHOR_DASH_SERIES_BOOK, "/lib")
        assertEquals(File("/lib/Brandon Sanderson/Warbreaker"), f)
    }

    @Test
    fun `unsafe characters are sanitized out of every path segment`() {
        val f = LibraryPaths.targetFolder("Title: Part 1/2", "Au*thor?", null, ImportStructure.AUTHOR_SERIES_BOOK, "/lib")
        assertEquals(File("/lib/Au_thor_/Title_ Part 1_2"), f)
    }

    @Test
    fun `a blank title falls back to a safe default rather than an empty segment`() {
        val f = LibraryPaths.targetFolder("", "", null, ImportStructure.AUTO, "/lib")
        assertEquals(File("/lib/Book"), f)
    }
}
