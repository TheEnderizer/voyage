package com.betteraudio.data.diskstore

import java.io.File
import java.security.MessageDigest

/**
 * Pure derivation of on-disk paths/filenames for a book's `data/` folder from its (possibly
 * synthetic) [com.betteraudio.data.db.entities.Book.folderPath] "folderKey" alone — no I/O, no
 * Android types, unit-testable.
 *
 * A folderKey has three shapes (see AudioFileScanner): a real directory's absolute path, a
 * "<dir>::<stem>" AUTO multi-book cluster (several books share one physical directory), or a
 * "<parentDir>::epub::<stem>" standalone-ebook row (no audio, no folder of its own). Only the
 * first is a real directory; all three resolve to the same data dir, `<dir>/data`. The folderKey
 * is always treated as opaque outside this object — never `File()`'d directly elsewhere.
 */
object BookDataPaths {

    /** Reserved directory name for a book's own data folder — also the name the scanner must
     *  never walk into (see ScannerHeuristics.DATA_DIR_NAME, which this mirrors). */
    const val DATA_DIR_NAME = "data"

    private const val CLUSTER_SEPARATOR = "::"
    private const val EPUB_MARKER = "::epub::"

    /** The real directory a folderKey lives in — peels off a synthetic "::" suffix, if any. */
    fun containingDir(folderKey: String): File = File(folderKey.substringBefore(CLUSTER_SEPARATOR))

    fun dataDir(folderKey: String): File = File(containingDir(folderKey), DATA_DIR_NAME)

    fun isCluster(folderKey: String): Boolean = folderKey.contains(CLUSTER_SEPARATOR)

    fun isEpubOnly(folderKey: String): Boolean = folderKey.contains(EPUB_MARKER)

    /** The synthetic suffix identifying one member of a shared folder — null for a real,
     *  single-book directory. For "<parent>::epub::<stem>" this is "<stem>" (the part after the
     *  LAST "::"), not "epub::<stem>". */
    private fun stem(folderKey: String): String? =
        if (isCluster(folderKey)) folderKey.substringAfterLast(CLUSTER_SEPARATOR) else null

    /** "book.json" for a real single-book folder, "<slug>.json" for one member of an AUTO
     *  cluster, "epub.<slug>.json" for a standalone-ebook row. */
    fun docFileName(folderKey: String): String {
        val s = stem(folderKey) ?: return "book.json"
        return if (isEpubOnly(folderKey)) "epub.${slug(s)}.json" else "${slug(s)}.json"
    }

    /** Base filename (no extension) for this book's cover inside its data dir — "cover" or
     *  "<slug>.cover"; caller appends the real extension ("cover.jpg" etc). */
    fun coverBaseName(folderKey: String): String {
        val s = stem(folderKey) ?: return "cover"
        return "${slug(s)}.cover"
    }

    /** "mapping.json" or "<slug>.mapping.json" — see [com.betteraudio.data.sync.MappingFileIO]. */
    fun mappingFileName(folderKey: String): String {
        val s = stem(folderKey) ?: return "mapping.json"
        return "${slug(s)}.mapping.json"
    }

    /**
     * Companion-pack root for a BOOK-scoped pack (docs/companion-packs.md §7.1) —
     * `data/companion/` for a real single-book folder, `data/companion_<slug>/` for one member of
     * an AUTO cluster. Same slug-namespacing rule as [docFileName]/[coverBaseName]/
     * [mappingFileName]: [dataDir] collapses every cluster sibling onto one `data/` dir (it peels
     * off the `::` suffix), so a bare `companion/` here would let two books in one folder fight
     * over the same pack directory the way an un-slugged doc file would. One book can have several
     * packs, each in its own `<packId>` subdirectory.
     */
    fun companionRootDir(folderKey: String): File {
        val s = stem(folderKey)
        val name = if (s == null) "companion" else "companion_${slug(s)}"
        return File(dataDir(folderKey), name)
    }

    /** `data/companion[_<slug>]/<packId>/` for a BOOK-scoped pack. */
    fun bookPackDir(folderKey: String, packId: String): File = File(companionRootDir(folderKey), packId)

    /**
     * Deterministic, collision-resistant filename fragment for a cluster/epub stem: a readable
     * prefix plus a short hash tail. The existing `safeFileName()` used elsewhere for cover
     * naming (AudioFileScanner) collapses e.g. "A B" and "A-B" to the same string — two cluster
     * members in one folder would then fight over one data file. The hash tail (derived from the
     * RAW stem, before the readable prefix discards punctuation) makes that collision astronomically
     * unlikely while keeping the filename human-scannable in a file manager.
     */
    fun slug(stem: String): String {
        val safe = stem.replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').lowercase().take(40)
            .ifBlank { "book" }
        return "$safe~${sha1Hex(stem).take(8)}"
    }

    private fun sha1Hex(s: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** Strips [dir]'s absolute path off the front of [absolutePath] when it's actually inside
     *  [dir], leaving a portable relative path (e.g. "data/cover.jpg"); falls back to the
     *  original absolute path otherwise (mirrors BackupManager.relPath's own fallback rule). */
    fun relativizeToDir(absolutePath: String, dir: File): String {
        val dirPath = dir.absolutePath
        return if (absolutePath.startsWith(dirPath)) {
            absolutePath.removePrefix(dirPath).trimStart('/', '\\')
        } else absolutePath
    }

    /** Inverse of [relativizeToDir]: resolves a stored path back to a real [File], whether it was
     *  recorded relative (the normal case) or absolute (the outside-the-folder fallback). */
    fun resolveRelOrAbs(dir: File, relOrAbs: String): File =
        if (File(relOrAbs).isAbsolute) File(relOrAbs) else File(dir, relOrAbs)

    /** A book's [folderPath] relative to the library root — the portable identity BackupManager
     *  already relies on for restore matching, moved here so every writer (BookDataStore,
     *  LibraryDataStore) shares one implementation instead of each re-deriving it. */
    fun relPath(folderPath: String, libraryFolder: String): String =
        if (libraryFolder.isNotBlank() && folderPath.startsWith(libraryFolder)) {
            folderPath.removePrefix(libraryFolder).trimStart('/', '\\')
        } else folderPath
}
