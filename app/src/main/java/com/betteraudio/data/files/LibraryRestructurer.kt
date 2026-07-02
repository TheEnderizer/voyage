package com.betteraudio.data.files

import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.scanner.ImportStructure
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves each book's folder on disk so the library layout matches the chosen [ImportStructure],
 * using the effective author/series names (so in-app series/author edits are reflected on disk).
 *
 * Safety: every move is **copy → verify (file count + total size) → delete original**, so an
 * interrupted or mismatched move never loses data — the original is only removed once the copy is
 * verified. Collisions (target already exists) and books without a real folder (synthetic `::`
 * multi-book keys) are skipped, not forced.
 */
@Singleton
class LibraryRestructurer @Inject constructor(
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore
) {
    data class Move(val bookId: Long, val title: String, val from: File, val to: File)
    data class Result(val moved: Int, val skipped: Int, val failed: Int)

    /** Books that would move, without touching anything (dry run for the confirmation dialog). */
    suspend fun plan(): List<Move> = withContext(Dispatchers.IO) {
        val structure = ImportStructure.fromName(settings.importStructure.first())
        val root = settings.libraryFolder.first()
        if (structure == ImportStructure.AUTO || root.isBlank()) return@withContext emptyList()
        repository.getAllBooksIncludingIgnoredOnce().mapNotNull { book ->
            val from = File(book.folderPath)
            if (!from.isDirectory) return@mapNotNull null       // synthetic key / missing folder
            val to = targetFolder(book, structure, root) ?: return@mapNotNull null
            if (to.absolutePath == from.absolutePath) return@mapNotNull null
            Move(book.id, book.displayTitle, from, to)
        }
    }

    suspend fun run(onProgress: (done: Int, total: Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val moves = plan()
        var moved = 0; var skipped = 0; var failed = 0
        moves.forEachIndexed { index, move ->
            try {
                when (moveOne(move)) {
                    MoveOutcome.MOVED -> moved++
                    MoveOutcome.SKIPPED -> skipped++
                    MoveOutcome.FAILED -> failed++
                }
            } catch (e: Exception) {
                AppLog.e("Restructure", "move failed for '${move.title}'", e); failed++
            }
            onProgress(index + 1, moves.size)
        }
        AppLog.i("Restructure", "done moved=$moved skipped=$skipped failed=$failed")
        Result(moved, skipped, failed)
    }

    private enum class MoveOutcome { MOVED, SKIPPED, FAILED }

    private suspend fun moveOne(move: Move): MoveOutcome {
        val from = move.from
        val to = move.to
        if (!from.isDirectory) return MoveOutcome.SKIPPED
        if (to.exists()) return MoveOutcome.SKIPPED                 // don't merge into an existing folder
        to.parentFile?.mkdirs()

        // Copy → verify → then (and only then) remove the original.
        val copied = runCatching { from.copyRecursively(to, overwrite = false) }.getOrDefault(false)
        if (!copied || !verify(from, to)) {
            if (to.exists()) runCatching { to.deleteRecursively() }
            return MoveOutcome.FAILED
        }

        // Repoint the DB before deleting the source, so a crash mid-way leaves the (verified) copy
        // referenced rather than a deleted original.
        val fromPath = from.absolutePath
        val toPath = to.absolutePath
        repository.getAudioFilesOnce(move.bookId).forEach { af ->
            if (af.filePath.startsWith(fromPath)) {
                repository.updateAudioFilePath(af.id, toPath + af.filePath.removePrefix(fromPath))
            }
        }
        val book = repository.getAllBooksIncludingIgnoredOnce().firstOrNull { it.id == move.bookId }
        val newCover = book?.coverArtPath?.let {
            if (it.startsWith(fromPath)) toPath + it.removePrefix(fromPath) else it
        }
        repository.updateBookLocation(move.bookId, toPath, newCover)

        runCatching { from.deleteRecursively() }
        return MoveOutcome.MOVED
    }

    /** Verify the copy: same set of relative file paths and matching sizes. */
    private fun verify(from: File, to: File): Boolean {
        fun index(dir: File): Map<String, Long> =
            dir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(dir).path to it.length() }
        val a = index(from); val b = index(to)
        return a.isNotEmpty() && a == b
    }

    /** Target folder for [book] under [root] per the chosen [structure]; null when unresolved. */
    private suspend fun targetFolder(book: Book, structure: ImportStructure, root: String): File? {
        val series = book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
        val author = sanitize(book.displayAuthor.ifBlank { series?.author ?: "" })
        val seriesName = series?.name?.let { sanitize(it) }?.takeIf { it.isNotBlank() }
        val leaf = File(book.folderPath).name.ifBlank { sanitize(book.displayTitle) }

        val parent: File = when (structure) {
            ImportStructure.AUTHOR_SERIES_BOOK -> {
                var p = File(root)
                if (author.isNotBlank()) p = File(p, author)
                if (seriesName != null) p = File(p, seriesName)
                p
            }
            ImportStructure.AUTHOR_DASH_SERIES_BOOK -> {
                val wrapper = when {
                    author.isNotBlank() && seriesName != null -> "$author - $seriesName"
                    seriesName != null -> seriesName
                    author.isNotBlank() -> author
                    else -> null
                }
                if (wrapper != null) File(File(root), sanitize(wrapper)) else File(root)
            }
            ImportStructure.AUTO -> return null
        }
        return File(parent, leaf)
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().trim('.').take(120)
}
