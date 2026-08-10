package com.betteraudio.data.diskstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins down the path/filename derivation for all three [Book.folderPath] shapes — a real
 * directory, an AUTO multi-book cluster ("<dir>::<stem>"), and a standalone-ebook row
 * ("<parent>::epub::<stem>") — since a regression here silently orphans a book's disk data.
 */
class BookDataPathsTest {

    private val REAL = "/storage/emulated/0/Audiobooks/Sanderson/Mistborn"
    private val CLUSTER = "/storage/emulated/0/Audiobooks/LooseFolder::Mistborn"
    private val EPUB_ONLY = "/storage/emulated/0/Audiobooks/LooseFolder::epub::Some Novel"

    @Test
    fun `dataDir resolves to dir slash data for all three folderKey shapes`() {
        assertEquals(File(REAL, "data"), BookDataPaths.dataDir(REAL))
        assertEquals(
            File("/storage/emulated/0/Audiobooks/LooseFolder", "data"),
            BookDataPaths.dataDir(CLUSTER)
        )
        assertEquals(
            File("/storage/emulated/0/Audiobooks/LooseFolder", "data"),
            BookDataPaths.dataDir(EPUB_ONLY)
        )
    }

    @Test
    fun `docFileName is book_json for a real folder`() {
        assertEquals("book.json", BookDataPaths.docFileName(REAL))
    }

    @Test
    fun `docFileName is a slug json for a cluster member`() {
        val name = BookDataPaths.docFileName(CLUSTER)
        assertTrue(name.endsWith(".json"))
        assertFalse(name.startsWith("epub."))
        assertTrue(name.startsWith("mistborn"))
    }

    @Test
    fun `docFileName is epub-prefixed for a standalone ebook row`() {
        val name = BookDataPaths.docFileName(EPUB_ONLY)
        assertTrue(name.startsWith("epub."))
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun `isCluster and isEpubOnly classify each shape correctly`() {
        assertFalse(BookDataPaths.isCluster(REAL))
        assertTrue(BookDataPaths.isCluster(CLUSTER))
        assertFalse(BookDataPaths.isEpubOnly(CLUSTER))
        assertTrue(BookDataPaths.isCluster(EPUB_ONLY))
        assertTrue(BookDataPaths.isEpubOnly(EPUB_ONLY))
    }

    @Test
    fun `slug is deterministic`() {
        assertEquals(BookDataPaths.slug("Mistborn"), BookDataPaths.slug("Mistborn"))
    }

    @Test
    fun `slug does not collide for stems that differ only by punctuation`() {
        // AudioFileScanner's own safeFileName() collapses "A B" and "A-B" to the same string —
        // the slug's hash tail must keep them apart.
        val a = BookDataPaths.slug("A B")
        val b = BookDataPaths.slug("A-B")
        assertNotEquals(a, b)
    }

    @Test
    fun `a real single-book folder and a cluster in the same directory never share a filename`() {
        // "<dir>" (book.json) vs "<dir>::Something" (<slug>.json) — must never collide even if
        // the stem happens to be literally "book".
        val clusterKey = "/storage/emulated/0/Audiobooks/Shared::book"
        assertNotEquals("book.json", BookDataPaths.docFileName(clusterKey))
    }

    @Test
    fun `coverBaseName and mappingFileName follow the same book vs cluster split`() {
        assertEquals("cover", BookDataPaths.coverBaseName(REAL))
        assertEquals("mapping.json", BookDataPaths.mappingFileName(REAL))
        assertTrue(BookDataPaths.coverBaseName(CLUSTER).endsWith(".cover"))
        assertTrue(BookDataPaths.mappingFileName(CLUSTER).endsWith(".mapping.json"))
    }

    @Test
    fun `DATA_DIR_NAME matches ScannerHeuristics' reserved name`() {
        assertEquals(com.betteraudio.data.scanner.ScannerHeuristics.DATA_DIR_NAME, BookDataPaths.DATA_DIR_NAME)
    }

    @Test
    fun `every slug ends in a tilde plus 8 lowercase hex chars`() {
        // BookDataStore.sweepOrphans decides what it may delete from a shared data/ folder by
        // matching this exact shape (SLUG_PREFIXED) — anything else is assumed to belong to some
        // other per-book feature and is left alone. If slug()'s format ever changes without that
        // regex changing too, the sweep silently stops recognizing its own files and cluster
        // folders accumulate orphaned docs forever, with no error anywhere.
        val shape = Regex("^[a-z0-9_]+~[0-9a-f]{8}$")
        listOf(
            "Mistborn", "A B", "A-B", "Shadow Slave Volume 7", "  ", "!!!",
            "Ünïcödé Tïtlé", "12345", "a".repeat(80)
        ).forEach { stem ->
            val slug = BookDataPaths.slug(stem)
            assertTrue("slug('$stem') = '$slug' does not match the expected shape", shape.matches(slug))
        }
    }
}
