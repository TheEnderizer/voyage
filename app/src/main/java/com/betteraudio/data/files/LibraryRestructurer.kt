package com.betteraudio.data.files

import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.DiskMirror
import com.betteraudio.data.diskstore.VoyageLayout
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.scanner.ImportStructure
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
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
    private val settings: SettingsStore,
    private val diskMirror: DiskMirror
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

    /**
     * Targeted restructure for specific books — same move/verify safety as [run], scoped to just
     * [bookIds] instead of sweeping the whole library. Used right after a metadata edit (author,
     * series rename, …) so the on-disk folder tracks the in-app change automatically. [plan]'s
     * `ImportStructure != AUTO` / blank-root guards live in [plan] itself, which this bypasses (it
     * doesn't sweep every book), so they're re-asserted here.
     */
    suspend fun restructureBooks(bookIds: Collection<Long>): Result = withContext(Dispatchers.IO) {
        if (bookIds.isEmpty()) return@withContext Result(0, 0, 0)
        val structure = ImportStructure.fromName(settings.importStructure.first())
        val root = settings.libraryFolder.first()
        if (structure == ImportStructure.AUTO || root.isBlank()) return@withContext Result(0, 0, 0)

        val idSet = bookIds.toSet()
        val moves = repository.getAllBooksIncludingIgnoredOnce()
            .filter { it.id in idSet }
            .mapNotNull { book ->
                val from = File(book.folderPath)
                if (!from.isDirectory) return@mapNotNull null       // synthetic key / missing folder
                val to = targetFolder(book, structure, root) ?: return@mapNotNull null
                if (to.absolutePath == from.absolutePath) return@mapNotNull null
                Move(book.id, book.displayTitle, from, to)
            }

        // Suppressed: a background flush (e.g. a pause-triggered write) landing in a book's
        // data/ folder between moveOne's copy and its verify step would make verify see a
        // mismatch and report a false FAILED, deleting the already-copied data. One flush after
        // the whole batch also means each moved book's doc is written once at its new location,
        // not once per intermediate DB repoint inside moveOne.
        var moved = 0; var skipped = 0; var failed = 0
        diskMirror.suppressed {
            moves.forEach { move ->
                try {
                    when (moveOne(move)) {
                        MoveOutcome.MOVED -> moved++
                        MoveOutcome.SKIPPED -> skipped++
                        MoveOutcome.FAILED -> failed++
                    }
                } catch (e: Exception) {
                    AppLog.e(LogCat.SCAN, "targeted move failed for '${move.title}'", e); failed++
                }
            }
        }
        diskMirror.flushDirty()
        if (moved > 0) cleanupEmptyDirs(File(root))
        AppLog.i(LogCat.SCAN, "targeted done moved=$moved skipped=$skipped failed=$failed")
        Result(moved, skipped, failed)
    }

    suspend fun run(onProgress: (done: Int, total: Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val moves = plan()
        var moved = 0; var skipped = 0; var failed = 0
        diskMirror.suppressed {
            moves.forEachIndexed { index, move ->
                try {
                    when (moveOne(move)) {
                        MoveOutcome.MOVED -> moved++
                        MoveOutcome.SKIPPED -> skipped++
                        MoveOutcome.FAILED -> failed++
                    }
                } catch (e: Exception) {
                    AppLog.e(LogCat.SCAN, "move failed for '${move.title}'", e); failed++
                }
                onProgress(index + 1, moves.size)
            }
        }
        diskMirror.flushDirty()
        if (moved > 0) {
            val root = settings.libraryFolder.first()
            if (root.isNotBlank()) cleanupEmptyDirs(File(root))
        }
        AppLog.i(LogCat.SCAN, "done moved=$moved skipped=$skipped failed=$failed")
        Result(moved, skipped, failed)
    }

    /** Remove folders left empty by the moves (bottom-up), keeping the library root itself. A
     *  folder holding only a leftover .nomedia is treated as empty, and so is a "data" folder
     *  holding no doc JSON (e.g. after deleteBook(deleteFiles=false) removed the doc but left the
     *  folder + its .nomedia behind) — without this, an orphaned data/ dir (and the now-otherwise-
     *  empty book folder around it) would never get swept by a later restructure. */
    private fun cleanupEmptyDirs(root: File) {
        val voyageDir = VoyageLayout.rootDir(root.absolutePath)
        root.walkBottomUp().forEach { d ->
            if (!d.isDirectory || d.absolutePath == root.absolutePath) return@forEach
            // Never descend into .voyage/ — it is app-managed state, not a user audio folder, and
            // its sub-dirs are legitimately empty until there's a series/author cover or a widget
            // image to put in them. Without this guard walkBottomUp deletes covers/ and
            // widgets/images/ whenever they're empty, and (on a library whose settings.json /
            // library.json write hasn't landed yet) .voyage itself — which silently costs the
            // reinstall setup-skip that this whole storage model exists to provide.
            if (voyageDir != null && (d == voyageDir || d.startsWithDir(voyageDir))) return@forEach
            val meaningful = d.listFiles()?.filter { child ->
                !(child.isFile && child.name == ".nomedia") &&
                    !(child.isDirectory && child.name == BookDataPaths.DATA_DIR_NAME && isEmptyDataDir(child))
            } ?: emptyList()
            if (meaningful.isEmpty()) runCatching { d.deleteRecursively() }
        }
    }

    /** True when this file sits somewhere beneath [dir] — separator-aware, so a sibling named
     *  ".voyage-backup" is not mistaken for a child of ".voyage". */
    private fun File.startsWithDir(dir: File): Boolean =
        absolutePath.startsWith(dir.absolutePath + File.separator)

    private fun isEmptyDataDir(dataDir: File): Boolean =
        dataDir.listFiles()?.none { it.isFile && it.name.endsWith(".json") } ?: true

    private enum class MoveOutcome { MOVED, SKIPPED, FAILED }

    private suspend fun moveOne(move: Move): MoveOutcome {
        val from = move.from
        val to = move.to
        if (!from.isDirectory) return MoveOutcome.SKIPPED
        if (to.exists()) return MoveOutcome.SKIPPED                 // don't merge into an existing folder
        to.parentFile?.mkdirs()

        // Copy → verify → then (and only then) remove the original.
        val copyResult = runCatching { from.copyRecursively(to, overwrite = false) }
        val copied = copyResult.getOrDefault(false)
        if (!copied) {
            AppLog.w(LogCat.SCAN, "restructure move '${move.title}': copy '${from.absolutePath}' -> '${to.absolutePath}' failed: ${copyResult.exceptionOrNull()?.message ?: "copyRecursively returned false"}")
            if (to.exists()) runCatching { to.deleteRecursively() }
            return MoveOutcome.FAILED
        }
        val mismatch = verify(from, to)
        if (mismatch != null) {
            AppLog.w(LogCat.SCAN, "restructure move '${move.title}': verify failed after copy, discarding the copy — $mismatch")
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
            .onFailure { AppLog.w(LogCat.SCAN, "restructure move '${move.title}': verified copy is at '${to.absolutePath}' but deleting the original '${from.absolutePath}' failed (${it.message}) — both now exist on disk") }
        return MoveOutcome.MOVED
    }

    /** Verify the copy: same set of relative file paths and matching sizes. Returns null when it
     *  matches, else a human-readable description of the mismatch (missing/extra files, size
     *  differences) — the caller logs this so a discarded copy leaves a real reason behind. */
    private fun verify(from: File, to: File): String? {
        fun index(dir: File): Map<String, Long> =
            dir.walkTopDown().filter { it.isFile }
                .associate { it.relativeTo(dir).path to it.length() }
        val a = index(from); val b = index(to)
        if (a.isEmpty()) return "source folder reports zero files (unexpected)"
        if (a == b) return null
        val missing = a.keys - b.keys
        val extra = b.keys - a.keys
        val sizeMismatch = a.keys.intersect(b.keys).filter { a[it] != b[it] }
        return "expected ${a.size} file(s), got ${b.size}" +
            (if (missing.isNotEmpty()) "; missing=$missing" else "") +
            (if (extra.isNotEmpty()) "; extra=$extra" else "") +
            (if (sizeMismatch.isNotEmpty()) "; size-mismatch=$sizeMismatch" else "")
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
