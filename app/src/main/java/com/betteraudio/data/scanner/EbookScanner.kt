package com.betteraudio.data.scanner

import android.content.Context
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans/reconciles standalone ebooks (no matching audiobook) and attaches epubs to audiobooks —
 * shared by the auto-attach hook in [AudioFileScanner] and manual "Connect EPUB" from the UI.
 *
 * Standalone ebook-only Book rows use the synthetic `"<parentDir>::epub::<stem>"` folderPath
 * convention (same `::` marker the audio scanner uses for multi-book folders) so they never
 * collide with a real audiobook folder, and have `fileCount = 0` / `totalDurationMs = 0`.
 */
@Singleton
class EbookScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository
) {

    /** Recursively scans [rootPath] for `.epub` files not already attached/standalone elsewhere,
     *  creating an ebook-only Book row for each. Returns the count of new/updated rows. */
    suspend fun scanEbookDirectory(rootPath: String): Int = withContext(Dispatchers.IO) {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            AppLog.w("EbookScan", "skipped — path missing or not a dir: $rootPath")
            return@withContext 0
        }
        var count = 0
        root.walkEpubFiles().forEach { epub ->
            if (importStandaloneEpub(epub)) count++
        }
        AppLog.i("EbookScan", "done path=$rootPath imported/updated=$count")
        count
    }

    /** Hides/unhides ebook rows whose backing file vanished or reappeared. Mirrors
     *  `AudioFileScanner.reconcileAgainstDisk`'s hide-don't-delete philosophy. */
    suspend fun reconcileEbooks() = withContext(Dispatchers.IO) {
        for (book in repository.getAllWithEbookOnce()) {
            val path = book.ebookPath ?: continue
            if (File(path).exists()) continue
            if (book.fileCount > 0) {
                // Connected to an audiobook: just detach — the audiobook itself is unaffected.
                AppLog.i("EbookScan", "ebook missing for book id=${book.id}, detaching")
                repository.setEbook(book.id, null, 0)
            } else if (!book.isIgnored) {
                // Standalone ebook-only row: hide it (restorable via Settings > Hidden Books).
                AppLog.i("EbookScan", "hiding missing ebook-only book id=${book.id}")
                repository.setBookIgnored(book.id, true)
            }
        }
    }

    /** Non-hidden `.epub` directly inside [folder] (audiobook auto-attach only looks one level
     *  deep — the epub must sit next to the audio files). Preference: filename stem matching the
     *  folder name, else first alphabetically. */
    fun findEpubIn(folder: File): File? {
        val candidates = folder.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("epub", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: return null
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.first()
        val folderNameNorm = folder.name.lowercase()
        return candidates.firstOrNull { it.nameWithoutExtension.lowercase() == folderNameNorm } ?: candidates.first()
    }

    /**
     * Attach [epubFile] to the audiobook [bookId]. If a standalone ebook-only row already exists
     * for this exact file, its reading progress is preferred (carried onto the audiobook's
     * progress row only if the audiobook has none yet) and the standalone row is deleted so the
     * same epub is never listed twice.
     */
    suspend fun attachEpubToBook(bookId: Long, epubFile: File): Boolean = withContext(Dispatchers.IO) {
        val path = epubFile.absolutePath
        val info = runCatching { EpubParser(epubFile).use { it.parse() } }.getOrNull()
        if (info == null || info.encrypted) {
            AppLog.w("EbookScan", "refusing epub (parse failed or DRM): $path")
            return@withContext false
        }
        val standalone = repository.getBookByEbookPath(path)?.takeIf { it.id != bookId && it.fileCount == 0 }
        repository.setEbook(bookId, path, info.spine.size)
        if (standalone != null) {
            repository.mergeStandaloneEbookProgress(fromBookId = standalone.id, toBookId = bookId)
            repository.deleteBook(standalone.id, deleteFiles = false)
        }
        true
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun File.walkEpubFiles(): Sequence<File> =
        walkTopDown().filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("epub", ignoreCase = true) }

    /** Upserts an ebook-only Book row for [epubFile]. Returns false when the file is already
     *  attached to an audiobook or already represented by another standalone row (dedupe), or
     *  when it fails to parse / is DRM-protected. */
    private suspend fun importStandaloneEpub(epubFile: File): Boolean {
        val path = epubFile.absolutePath
        val existingByPath = repository.getBookByEbookPath(path)
        if (existingByPath != null && existingByPath.fileCount > 0) return false // attached elsewhere

        val info = runCatching { EpubParser(epubFile).use { it.parse() } }.getOrNull()
        if (info == null) {
            AppLog.w("EbookScan", "failed to parse $path")
            return false
        }
        if (info.encrypted) {
            AppLog.w("EbookScan", "skipping DRM-protected epub: $path")
            return false
        }

        val folderPath = "${epubFile.parentFile?.absolutePath}::epub::${epubFile.nameWithoutExtension}"
        val title = info.meta.title?.takeIf { it.isNotBlank() } ?: epubFile.nameWithoutExtension
        val author = info.meta.author ?: ""

        val bookId = repository.upsertEbookOnlyBook(
            folderPath = folderPath, title = title, author = author,
            coverArtPath = null, ebookPath = path, spineCount = info.spine.size
        )

        // Extract a cover next to the epub, hidden from the gallery — only when this is a
        // brand-new row (existing rows keep whatever cover the user has set/searched for).
        if (existingByPath == null) {
            val coverFile = File(epubFile.parentFile, ".cover_${epubFile.nameWithoutExtension}.epub.jpg")
            val wrote = runCatching { EpubParser(epubFile).use { it.extractCover(coverFile) } }.getOrDefault(false)
            if (wrote) {
                val nomedia = File(epubFile.parentFile, ".nomedia")
                if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
                repository.updateCoverArt(bookId, coverFile.absolutePath)
            }
        }
        return true
    }
}
