package com.betteraudio.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "ogg", "flac", "aac", "opus", "wav")

/** Every tag [AudioFileScanner] reads off a file via [MediaMetadataRetriever], read once per
 *  file and shared by both the clustering heuristic (which only needs [album]) and the actual
 *  import (which needs the rest) — previously each opened its own retriever on the same files.
 *  A missing entry for a file in the enclosing map means the read failed (logged at read time);
 *  callers each apply their own fallback for that case, matching prior behaviour exactly. */
private data class FileTags(
    val durationMs: Long,
    val album: String?,
    val albumArtist: String?,
    val artist: String?,
    val author: String?,
    val composer: String?,
    val genre: String?,
    val year: String?,
    val date: String?,
    val chapterTitle: String?,
    val trackStr: String?
)

/** Memoizes `File.listFiles()` per directory for the duration of one [AudioFileScanner.scanDirectory]
 *  call — [File.hasDirectAudio], [File.isDiscSplitBook] and the walkers below each ask the same
 *  directory's contents more than once (e.g. a folder's own audio check, then its disc-split
 *  check re-derives the same fact) and previously re-listed the directory every time. */
private class DirCache {
    private val entries = HashMap<String, Array<File>>()
    fun listFiles(dir: File): Array<File> = entries.getOrPut(dir.absolutePath) { dir.listFiles() ?: emptyArray() }
}

@Singleton
class AudioFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore,
    private val ebookScanner: EbookScanner,
    private val bookDataStore: com.betteraudio.data.diskstore.BookDataStore,
    private val libraryDataStore: com.betteraudio.data.diskstore.LibraryDataStore,
    private val restoreOps: com.betteraudio.data.diskstore.RestoreOps,
    private val diskMirror: com.betteraudio.data.diskstore.DiskMirror
) {

    /**
     * Import the library at [rootPath] using the user-selected [ImportStructure]. Every scan
     * also reconciles the DB against disk — files that vanished are dropped and books whose
     * files are all gone are hidden (never deleted, so progress survives if they come back).
     */
    suspend fun scanDirectory(rootPath: String): Int = withContext(Dispatchers.IO) {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            AppLog.w("Scan", "skipped — path missing or not a dir: $rootPath")
            return@withContext 0
        }
        val structure = ImportStructure.fromName(settings.importStructure.first())
        AppLog.i("Scan", "start path=$rootPath structure=$structure")
        val cache = DirCache()
        val count = try {
            when (structure) {
                ImportStructure.AUTO -> scanFolder(root, seriesName = null, seriesOrder = null, cache = cache)
                ImportStructure.AUTHOR_SERIES_BOOK -> scanAuthorSeriesBook(root, cache)
                ImportStructure.AUTHOR_DASH_SERIES_BOOK -> scanAuthorDashSeriesBook(root, cache)
            }
        } catch (e: Throwable) {
            AppLog.e("Scan", "failed for $rootPath", e); throw e
        }
        runCatching { reconcileLibraryFromDisk() }
            .onFailure { AppLog.e("Scan", "library.json reconcile failed for $rootPath", it) }
        // Auto-joining is disabled: series membership comes from folder structure (seriesName)
        // and playback groups are only ever created by an explicit user action.
        runCatching { reconcileAgainstDisk(root) }
            .onFailure { AppLog.e("Scan", "reconcile failed for $rootPath", it) }
        // One "rescan" covers both libraries: also sweep the (separate) standalone-ebook folder.
        runCatching {
            settings.ebookFolder.first().takeIf { it.isNotBlank() }
                ?.let { ebookScanner.scanEbookDirectory(it) }
            ebookScanner.reconcileEbooks()
        }.onFailure { AppLog.e("Scan", "ebook scan/reconcile failed", it) }
        AppLog.i("Scan", "done path=$rootPath imported/updated=$count")
        count
    }

    // ── Structured import: root/author/[series/]book/files ─────────────────────
    private suspend fun scanAuthorSeriesBook(root: File, cache: DirCache): Int {
        var count = 0
        val authorDirs = root.listDirs(cache)
        for (authorDir in authorDirs) {
            val author = authorDir.name
            // An author folder with audio directly under it is itself a single book.
            if (authorDir.hasDirectAudio(cache)) {
                count += importFolderAsBook(authorDir, forcedAuthor = author, seriesName = null, seriesOrder = null, cache = cache)
                continue
            }
            for (child in authorDir.listDirs(cache)) {
                if (child.hasDirectAudio(cache) || child.isDiscSplitBook(cache)) {
                    // author/book  → standalone book, no series
                    count += importFolderAsBook(child, forcedAuthor = author, seriesName = null, seriesOrder = null, cache = cache)
                } else {
                    // author/series/book…
                    val series = child.name
                    for ((bIndex, bookDir) in child.listDirs(cache).withIndex()) {
                        count += importFolderAsBook(
                            bookDir, forcedAuthor = author, seriesName = series,
                            seriesOrder = seriesOrderFor(bookDir, bIndex), cache = cache
                        )
                    }
                }
            }
        }
        return count
    }

    // ── Structured import: root/(author - series)/book/files ───────────────────
    private suspend fun scanAuthorDashSeriesBook(root: File, cache: DirCache): Int {
        var count = 0
        for (wrapper in root.listDirs(cache)) {
            // A wrapper that holds audio directly (no book sub-folders) is itself one book;
            // author then comes from the file tags.
            if (wrapper.hasDirectAudio(cache) || wrapper.isDiscSplitBook(cache)) {
                count += importFolderAsBook(wrapper, forcedAuthor = null, seriesName = null, seriesOrder = null, cache = cache)
                continue
            }
            val dash = wrapper.name.indexOf(" - ")
            val author = if (dash >= 0) wrapper.name.substring(0, dash).trim().ifBlank { null } else null
            val series = if (dash >= 0) wrapper.name.substring(dash + 3).trim().ifBlank { null } else wrapper.name.trim()
            for ((bIndex, bookDir) in wrapper.listDirs(cache).withIndex()) {
                count += importFolderAsBook(
                    bookDir, forcedAuthor = author, seriesName = series,
                    seriesOrder = seriesOrderFor(bookDir, bIndex), cache = cache
                )
            }
        }
        return count
    }

    /**
     * Import a single folder as exactly one book (its audio files become the chapters).
     * Handles the disc-split case where the book's audio lives in "(1 of 3)"-style
     * sub-folders. Returns 1 if a book was imported, 0 if the folder had no audio.
     */
    private suspend fun importFolderAsBook(
        bookDir: File,
        forcedAuthor: String?,
        seriesName: String?,
        seriesOrder: Float?,
        cache: DirCache
    ): Int {
        val direct = bookDir.listAudioFiles(cache)
        val files: List<File>
        val preserveOrder: Boolean
        if (direct.isNotEmpty()) {
            files = direct
            preserveOrder = false
        } else if (bookDir.isDiscSplitBook(cache)) {
            files = bookDir.listDirs(cache).filter { it.listAudioFiles(cache).isNotEmpty() }.flatMap { disc ->
                disc.listAudioFiles(cache).sortedWith(compareBy({ ScannerHeuristics.extractTrackNumber(it.nameWithoutExtension) }, { it.name }))
            }
            preserveOrder = true
        } else {
            return 0
        }
        importBook(
            folder = bookDir, folderKey = bookDir.absolutePath, defaultTitle = bookDir.name,
            audioFiles = files, multiBook = false, seriesName = seriesName, seriesOrder = seriesOrder,
            preserveOrder = preserveOrder, forcedAuthor = forcedAuthor, keepFolderTitle = true
        )
        return 1
    }

    /** Series position from a leading number in the book-folder name, else scan order. */
    private fun seriesOrderFor(bookDir: File, index: Int): Float {
        val n = ScannerHeuristics.extractTrackNumber(bookDir.name)
        return if (n != Int.MAX_VALUE) n.toFloat() else (index + 1).toFloat()
    }

    private fun File.listDirs(cache: DirCache): List<File> =
        cache.listFiles(this).filter { it.isDirectory && isScannableDir(cache) }.sortedBy { it.name.lowercase() }

    private fun File.hasDirectAudio(cache: DirCache): Boolean = listAudioFiles(cache).isNotEmpty()

    /** True when this directory should be walked into by the scanner. Excludes dot-directories
     *  (".voyage", etc.) unconditionally, and excludes a directory literally named "data" unless
     *  it genuinely holds audio — Voyage's own per-book data folder is never audio-bearing, but a
     *  user's own rip that happens to live in a folder called "data" must still be scanned. */
    private fun File.isScannableDir(cache: DirCache): Boolean =
        !ScannerHeuristics.isHiddenDirName(name) &&
            (name != ScannerHeuristics.DATA_DIR_NAME || hasDirectAudio(cache))

    /** True when this folder has no direct audio but ≥2 disc/part-labelled audio sub-folders. */
    private fun File.isDiscSplitBook(cache: DirCache): Boolean {
        if (hasDirectAudio(cache)) return false
        val audioSubs = listDirs(cache).filter { it.listAudioFiles(cache).isNotEmpty() }
        return audioSubs.size >= 2 && audioSubs.all { ScannerHeuristics.looksLikePartFolder(it) }
    }

    // ── Reconcile library-wide disk data (presets/authors/series) ───────────────
    /**
     * Applies `.voyage/library.json` — presets, authors, and series membership — to the DB.
     * Gated on the doc's mtime vs. [SettingsStore.libraryJsonAppliedAt] so a routine rescan
     * doesn't re-parse and re-apply it every time; a bootstrap restore forces this by resetting
     * that timestamp to 0 before triggering its own scan (see LibraryBootstrapper). Runs before
     * [reconcileAgainstDisk], so member books already exist for [RestoreOps.applySeries] to match
     * against by the time it does.
     */
    private suspend fun reconcileLibraryFromDisk() {
        val root = settings.libraryFolder.first()
        if (root.isBlank() || !File(root).canRead()) return
        val lastApplied = settings.libraryJsonAppliedAt.first()
        val docMtime = libraryDataStore.lastModified()
        if (docMtime == 0L || docMtime <= lastApplied) return
        val doc = libraryDataStore.read() ?: return

        val currentBooks = repository.getAllBooksIncludingIgnoredOnce().map { b ->
            com.betteraudio.data.backup.BookCandidate(
                b.id,
                com.betteraudio.data.backup.BookIdentity(
                    b.folderPath,
                    com.betteraudio.data.diskstore.BookDataPaths.relPath(b.folderPath, root),
                    b.title, b.author
                )
            )
        }
        diskMirror.suppressed {
            restoreOps.applyPresets(doc.presets)
            restoreOps.applyAuthors(doc.authors)
            restoreOps.applySeries(doc.series, currentBooks)
        }
        diskMirror.flushLibrary()
        settings.setLibraryJsonAppliedAt(System.currentTimeMillis())
    }

    // ── Reconcile DB against disk (hide missing books, drop missing files) ──────
    private suspend fun reconcileAgainstDisk(root: File) {
        // Never prune when we can't actually read the tree — a revoked permission would
        // otherwise report every file as missing and hide the whole library.
        if (!root.exists() || !root.canRead()) return
        val rootPath = root.absolutePath
        val books = repository.getAllBooksIncludingIgnoredOnce().filter { book ->
            val bookRoot = book.folderPath.substringBefore("::")
            bookRoot == rootPath || bookRoot.startsWith("$rootPath${File.separator}")
        }
        if (books.isEmpty()) return

        // One query for every audio file in the DB, grouped in memory, instead of one
        // getAudioFilesOnce(bookId) query per book.
        val bookIds = books.map { it.id }.toSet()
        val filesByBook = repository.getAllAudioFilesOnce().filter { it.bookId in bookIds }.groupBy { it.bookId }

        // One listFiles() per distinct parent folder, checked by filename, instead of one
        // File(path).exists() stat per row.
        val namesByDir = HashMap<String, Set<String>>()
        fun namesIn(dir: File): Set<String> =
            namesByDir.getOrPut(dir.absolutePath) { dir.listFiles()?.map { it.name }?.toSet() ?: emptySet() }

        for (book in books) {
            val files = filesByBook[book.id].orEmpty()
            if (files.isEmpty()) continue
            val present = files.filter { af ->
                val parent = File(af.filePath).parentFile
                parent != null && File(af.filePath).name in namesIn(parent)
            }
            when {
                present.size == files.size -> Unit                       // all good
                present.isEmpty() -> {
                    // Whole book gone → hide it (keep the record + progress).
                    if (!book.isIgnored) {
                        AppLog.i("Scan", "hiding missing book id=${book.id} '${book.title}'")
                        repository.setBookIgnored(book.id, true)
                    }
                }
                else -> {
                    // Some files vanished → drop them and rebuild the book's chapters/stats.
                    AppLog.i("Scan", "book id=${book.id} lost ${files.size - present.size} file(s)")
                    dropMissingFiles(book.id, present)
                }
            }
        }
    }

    /**
     * Deletes ONLY the rows whose file vanished, leaving every survivor's `id` untouched.
     *
     * Deliberately not clear-and-reinsert: `AudioFile.id` is autogenerated, so re-inserting the
     * survivors hands each of them a brand-new id and silently orphans everything anchored to the
     * old one — `Bookmark.fileId` (which has no FK, so the rows quietly survive pointing nowhere),
     * `PlaybackProgress.currentFileId`, and `Chapter.fileId`. Losing one file out of a book would
     * otherwise invalidate the anchors of every *other* file's bookmarks too, which is exactly the
     * case file-relative bookmarks exist to survive. Chapters are still rebuilt from scratch below.
     */
    private suspend fun dropMissingFiles(bookId: Long, present: List<AudioFile>) {
        val totalDuration = present.sumOf { it.durationMs }
        val presentIds = present.mapTo(HashSet()) { it.id }
        val goneIds = repository.getAudioFilesOnce(bookId).map { it.id }.filterNot { it in presentIds }
        repository.deleteAudioFilesByIds(goneIds)
        repository.updateBookFileStats(bookId, totalDuration, present.size)
        val embeddedByPath = present.associate { it.filePath to ChapterExtractor.extract(it.filePath, File(it.filePath).extension) }
        buildChapters(bookId, embeddedByPath)
    }

    // A folder with direct audio files is one or more books (split by filename clustering).
    // A folder of only sub-dirs is a series container; each child becomes a book in that series.
    private suspend fun scanFolder(dir: File, seriesName: String?, seriesOrder: Float?, depth: Int = 0, cache: DirCache): Int {
        val directAudio = dir.listAudioFiles(cache)
        var count = 0

        if (directAudio.isNotEmpty()) {
            val tags = readTags(directAudio)
            val clusters = groupFilesIntoBooks(directAudio, tags)
            val multiBook = clusters.size > 1
            val liveFolderKeys = mutableSetOf<String>()
            clusters.forEach { (stem, files) ->
                val folderKey = if (multiBook) "${dir.absolutePath}::$stem" else dir.absolutePath
                val defaultTitle = if (multiBook && stem.isNotBlank()) stem.titleCase() else dir.name
                importBook(dir, folderKey, defaultTitle, files, multiBook, seriesName, seriesOrder, tags = tags)
                liveFolderKeys.add(folderKey)
                count++
            }
            // Only meaningful for a cluster: several books sharing one data/ dir means a member
            // that disappears (file deleted/renamed away, changing the cluster split) can leave
            // its doc behind with no book to claim it. A single-book folder's data/ dir is never
            // swept here — nothing about a routine rescan should touch it.
            if (multiBook) bookDataStore.sweepOrphans(dir, liveFolderKeys)
        }

        // Recurse into sub-directories (series structure / mixed folders)
        val subdirs = cache.listFiles(dir).filter { it.isDirectory && it.isScannableDir(cache) }.sortedBy { it.name }
        if (subdirs.isNotEmpty()) {
            val subdirsWithAudio = subdirs.filter { it.listAudioFiles(cache).isNotEmpty() }
            // A folder whose only audio-bearing children are disc/part labels of the same book
            // (e.g. "(1 of 3)", "(2 of 3)") is one book split across discs, not a series.
            val isDiscContainer = directAudio.isEmpty() && subdirsWithAudio.size >= 2 &&
                subdirsWithAudio.size == subdirs.size &&
                subdirsWithAudio.all { ScannerHeuristics.looksLikePartFolder(it) }
            if (isDiscContainer) {
                val allFiles = subdirsWithAudio.flatMap { disc ->
                    disc.listAudioFiles(cache).sortedWith(compareBy({ ScannerHeuristics.extractTrackNumber(it.nameWithoutExtension) }, { it.name }))
                }
                importBook(dir, dir.absolutePath, dir.name, allFiles, false, seriesName, seriesOrder, preserveOrder = true)
                count++
            } else {
                // Only a pure container (no direct audio) with >1 child is treated as a series —
                // and only below the library root (depth > 0), or the root itself becomes a
                // phantom series the first time it holds more than one top-level folder.
                val isSeriesContainer = depth > 0 && directAudio.isEmpty() && subdirs.size > 1
                count += subdirs.mapIndexed { index, subdir ->
                    val childSeriesName = if (isSeriesContainer) dir.name else seriesName
                    val childOrder = if (isSeriesContainer) (index + 1).toFloat() else seriesOrder
                    scanFolder(subdir, childSeriesName, childOrder, depth + 1, cache)
                }.sum()
            }
        }
        return count
    }

    private suspend fun importBook(
        folder: File,
        folderKey: String,
        defaultTitle: String,
        audioFiles: List<File>,
        multiBook: Boolean,
        seriesName: String?,
        seriesOrder: Float?,
        preserveOrder: Boolean = false,
        // Structured import: the folder name IS the author/title, so don't let embedded
        // ALBUM/ARTIST tags override them. forcedAuthor = null falls back to file tags.
        forcedAuthor: String? = null,
        keepFolderTitle: Boolean = false,
        // Pre-read tags for these exact files (the AUTO-structure caller already read them once
        // to decide clustering); null means "read them now" — every other caller's files were
        // never read yet.
        tags: Map<File, FileTags?>? = null
    ) {
        val existing = repository.getBookByFolder(folderKey)

        // Disc-merged books arrive pre-ordered (disc, then track within disc); re-sorting by
        // a filename-only track number would interleave each disc's "track 1, track 2, ..."
        // back together across discs.
        val sortedFiles = if (preserveOrder) audioFiles else audioFiles.sortedWith(
            compareBy({ ScannerHeuristics.extractTrackNumber(it.nameWithoutExtension) }, { it.name })
        )

        // Refresh is additive: for a book already in the library, only re-read files when the
        // file set actually changed or its chapters need (re)building. A plain refresh that
        // found nothing new leaves the existing book completely untouched — title, author,
        // series, progress, group membership and manual grouping are never overwritten.
        val existingPaths = existing
            ?.let { repository.getAudioFilesOnce(it.id).map { f -> f.filePath }.toSet() }
            ?: emptySet()
        val currentPaths = sortedFiles.map { it.absolutePath }.toSet()
        val filesChanged = existing == null || existingPaths != currentPaths
        val needChapters = existing != null && repository.chapterCountForBook(existing.id) == 0

        // Disk-first reconciliation. A brand-new row always reads its doc (nothing to compare
        // against yet — see the FRESH_IMPORT overlay below). An existing row only re-reads/re-
        // parses when a cheap mtime check says the doc changed since it was last applied
        // (Book.dataAppliedAtMs), OR when filesChanged — a folderKey shift (an AUTO cluster
        // gaining/losing a member) can only be found via BookDataStore.read's own relocation
        // fallback, which needs read() to actually run. Both guards exist so a static library
        // doesn't re-parse every book's doc on every routine rescan.
        //
        // consultedDisk is tracked separately from "diskDoc != null" because the cover-priority
        // chain below has to tell "we read the doc and it records no user-picked cover" (safe to
        // let a folder cover.png win) apart from "we skipped the read to save a parse" (must NOT
        // let cover.png win — it would revert an in-app cover pick).
        val consultedDisk = existing == null || filesChanged ||
            bookDataStore.lastModified(folderKey) > existing.dataAppliedAtMs
        val diskDoc: com.betteraudio.data.diskstore.BookDocument? =
            if (consultedDisk) bookDataStore.read(folderKey, sortedFiles.map { it.name }) else null
        if (existing != null && diskDoc != null) {
            diskMirror.suppressed {
                restoreOps.applyBookDocument(existing.id, diskDoc, com.betteraudio.data.diskstore.ApplyMode.MERGE)
            }
            repository.markDataApplied(existing.id, System.currentTimeMillis())
        }

        if (existing != null && !filesChanged && !needChapters) return

        // One MediaMetadataRetriever pass per file, shared by clustering (already done, if this
        // folder needed it) and the import below — reuse it if the caller already read these
        // exact files, else read them now (their first and only read).
        val fileTags = tags ?: readTags(sortedFiles)

        var totalDuration = 0L
        val audioEntities = mutableListOf<AudioFile>()
        // path -> embedded chapters (empty if none)
        val embeddedByPath = mutableMapOf<String, List<RawChapter>>()

        // Derived metadata only feeds a brand-new book; existing books keep their stored values.
        var bookTitle = defaultTitle
        var bookAuthor = forcedAuthor?.takeIf { it.isNotBlank() } ?: ""
        var narrator: String? = null
        var genre: String? = null
        var year: Int? = null
        var album: String? = null

        sortedFiles.forEachIndexed { index, file ->
            val ft = fileTags[file] ?: return@forEachIndexed // read failed; already logged by readTags
            val durationMs = ft.durationMs
            totalDuration += durationMs

            if (index == 0) {
                val metaTitle = ft.album
                val metaArtist = ft.albumArtist ?: ft.artist ?: ft.author
                if (!metaTitle.isNullOrBlank() && !multiBook && !keepFolderTitle) bookTitle = metaTitle
                if (!metaArtist.isNullOrBlank() && bookAuthor.isBlank()) bookAuthor = metaArtist
                narrator = ft.composer?.takeIf { it.isNotBlank() } ?: narrator
                genre = ft.genre?.takeIf { it.isNotBlank() } ?: genre
                year = parseYear(ft.year ?: ft.date) ?: year
                album = ft.album?.takeIf { it.isNotBlank() } ?: album
            }

            val chapterTitle = ft.chapterTitle
            val trackNum = ft.trackStr?.split("/")?.firstOrNull()?.toIntOrNull() ?: (index + 1)

            embeddedByPath[file.absolutePath] =
                ChapterExtractor.extract(file.absolutePath, file.extension)

            audioEntities.add(
                AudioFile(
                    bookId = existing?.id ?: 0L,
                    filePath = file.absolutePath,
                    fileName = file.name,
                    trackNumber = trackNum,
                    title = chapterTitle,
                    durationMs = durationMs,
                    fileSizeBytes = file.length(),
                    chapterTitle = chapterTitle
                )
            )
        }

        // Resolve (or create) a first-class Series row for a brand-new book so seriesId is set
        // from the start. Existing books keep whatever series membership the user has arranged.
        // The scanner's own structural signal (folder-derived seriesName, AUTHOR_SERIES_BOOK
        // mode) wins when present; a disk doc's series is only a fallback for AUTO mode, where
        // the scanner itself never derives one from folder structure.
        val effectiveSeriesName = seriesName?.takeIf { it.isNotBlank() } ?: diskDoc?.series?.name
        val effectiveSeriesOrder = seriesOrder ?: diskDoc?.series?.order
        val resolvedSeriesId = if (existing == null && !effectiveSeriesName.isNullOrBlank())
            seriesRepository.getOrCreateSeriesByName(effectiveSeriesName, bookAuthor)
        else null

        // Count the rows we can actually insert, not the files on disk — a file whose metadata read
        // failed above has no AudioFile row, and claiming otherwise produces a book that can never
        // play and that reconcileAgainstDisk will not repair.
        val importedCount = audioEntities.size

        val bookId: Long
        if (existing == null) {
            bookId = repository.upsertBook(
                Book(
                    title = bookTitle,
                    author = bookAuthor,
                    folderPath = folderKey,
                    seriesId = resolvedSeriesId,
                    seriesName = effectiveSeriesName,
                    seriesOrder = effectiveSeriesOrder,
                    totalDurationMs = totalDuration,
                    fileCount = importedCount,
                    narrator = narrator,
                    genre = genre,
                    year = year,
                    album = album,
                    // A restored original add date (if this book existed before a reinstall) beats
                    // "now" — otherwise a reinstall would silently reshuffle "recently added" sort.
                    addedDateMs = diskDoc?.addedDateMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
                )
            )
            // Overlay the precious fields a disk doc carries — overrides, synopsis, status,
            // isIgnored, skipSilence, progress, bookmarks, sessions, skips — onto the row just
            // created. This is what makes a rescan after a reinstall actually reinstate them.
            if (diskDoc != null) {
                diskMirror.suppressed {
                    restoreOps.applyBookDocument(bookId, diskDoc, com.betteraudio.data.diskstore.ApplyMode.FRESH_IMPORT)
                }
                repository.markDataApplied(bookId, System.currentTimeMillis())
            }
        } else {
            bookId = existing.id
            // Existing book whose file set grew/shrank: refresh only the file-derived stats,
            // preserving every user-facing field (title, overrides, status, series, group…).
            if (filesChanged) {
                repository.upsertBook(
                    existing.copy(totalDurationMs = totalDuration, fileCount = importedCount)
                )
            }
        }

        // Only rewrite audio files when the file set actually changed (re-inserting would
        // null out PlaybackProgress.currentFileId and reset the resume position). Files and
        // their chapters commit atomically — a process death here must never leave a book
        // whose fileCount/audio_files disagree, or whose files changed but chapters didn't.
        if (filesChanged || repository.chapterCountForBook(bookId) == 0) {
            repository.withTransaction {
                if (filesChanged) {
                    // Carry each surviving file's existing `id` (and its cached damage scan) over
                    // by matching on filePath, and delete only the rows whose file is really gone.
                    // A blanket clear-and-reinsert would autogenerate new ids for files that never
                    // moved, orphaning every anchor onto them — see [dropMissingFiles].
                    val storedByPath = repository.getAudioFilesOnce(bookId).associateBy { it.filePath }
                    val rewritten = audioEntities.map { e ->
                        val prior = storedByPath[e.filePath]
                        e.copy(
                            id = prior?.id ?: 0L,
                            bookId = bookId,
                            damageRangesJson = prior?.damageRangesJson
                        )
                    }
                    val keptPaths = audioEntities.mapTo(HashSet()) { it.filePath }
                    repository.deleteAudioFilesByIds(
                        storedByPath.values.filterNot { it.filePath in keptPaths }.map { it.id }
                    )
                    repository.insertAudioFiles(rewritten)
                }
                buildChapters(bookId, embeddedByPath)
            }
        }

        // Cover priority (see the storage-redesign plan's "Cover unification" section):
        //  1. a "user" cover recorded in the disk doc (gallery pick / online search) always wins,
        //     even over a legacy cover.png — fixes a real bug: without this check, re-scanning a
        //     folder that still has an old cover.png in it would silently stomp a cover the user
        //     picked in-app afterwards, every single time.
        //  2. an explicit "cover.png" dropped in the book's own folder. Skipped for multi-book
        //     folders (a synthetic "::" split): one cover.png there is ambiguous as to which of
        //     the cluster's books it belongs to. Note this whole block is only reached on import
        //     or when the file set / chapters changed (see the early return above), so dropping a
        //     cover.png into an otherwise-unchanged folder is picked up on the next scan that has
        //     some other reason to re-read the book, not literally every scan.
        //  3. whatever coverArtPath the book already has — left untouched.
        //  4. a legacy hidden cover this app itself extracted in a pre-redesign version
        //     (.cover.jpg / .cover_<stem>.jpg) — detected in place, not migrated.
        //  5. extract fresh from the embedded audio tags, into data/ (the canonical location now).
        val userCoverFile = diskDoc?.cover
            ?.takeIf { it.source == "user" }
            ?.let { bookDataStore.resolveCover(folderKey, it.relPath) }
        val explicitCover = if (userCoverFile == null && !multiBook)
            File(folder, "cover.png").takeIf { it.isFile } else null
        val legacyCoverName = if (multiBook) ".cover_${folderKey.substringAfterLast("::").safeFileName()}.jpg" else ".cover.jpg"
        val legacyExtractedCover = if (userCoverFile == null && explicitCover == null)
            File(folder, legacyCoverName).takeIf { it.isFile } else null
        when {
            userCoverFile != null -> {
                if (existing?.coverArtPath != userCoverFile.absolutePath) {
                    repository.updateCoverArt(bookId, userCoverFile.absolutePath)
                }
            }
            // Doc not read this pass (mtime gate closed), so rule 1 could not be evaluated —
            // an existing cover therefore outranks cover.png here rather than risk silently
            // reverting a gallery/online pick that IS recorded in the doc we didn't open.
            !consultedDisk && existing?.coverArtPath != null -> Unit
            explicitCover != null -> {
                if (existing?.coverArtPath != explicitCover.absolutePath) {
                    repository.updateCoverArt(bookId, explicitCover.absolutePath)
                }
            }
            existing?.coverArtPath != null -> Unit // rule 3: keep
            legacyExtractedCover != null -> repository.updateCoverArt(bookId, legacyExtractedCover.absolutePath)
            else -> extractCoverArt(sortedFiles.firstOrNull(), bookId, folderKey)
        }

        // A disk-doc-recorded ebook connection (restored from a prior install) wins outright for
        // a brand-new row when the epub it points at still exists, carrying its chapter-alignment
        // map along (setEbook itself always nulls chapterMapJson, so it's re-applied after).
        // Otherwise: auto-attach an .epub sitting next to the audio, once. Skipped for multi-book
        // folders (a synthetic "::" split) — ambiguous which of the cluster's books the epub
        // belongs to; the user can still connect it manually from that book's options.
        var ebookRestoredFromDisk = false
        if (existing == null && diskDoc?.ebook != null) {
            val epubFile = com.betteraudio.data.diskstore.BookDataPaths.resolveRelOrAbs(
                com.betteraudio.data.diskstore.BookDataPaths.containingDir(folderKey), diskDoc.ebook.relPath
            )
            if (epubFile.isFile) {
                repository.setEbook(bookId, epubFile.absolutePath, diskDoc.ebook.spineCount)
                diskDoc.ebook.chapterMap?.let { cm -> repository.setChapterMap(bookId, org.json.JSONArray(cm).toString()) }
                ebookRestoredFromDisk = true
            }
        }
        if (!multiBook && !ebookRestoredFromDisk && existing?.ebookPath == null) {
            ebookScanner.findEpubIn(folder)?.let { epub ->
                runCatching { ebookScanner.attachEpubToBook(bookId, epub) }
                    .onFailure { AppLog.e("Scan", "auto-attach epub failed for book=$bookId", it) }
            }
        }

        // Auto-import a bundled mapping (paragraph-resolution sync data — see MappingFileIO)
        // sitting in the book's data/ folder (or the pre-redesign location), once an epub is
        // connected and only while the book has no anchors yet, so a bundled file is picked up
        // without overwriting a fresher on-device alignment the user already ran. Clusters get
        // their own <slug>.mapping.json now, so this is no longer skipped for multiBook.
        runCatching { importMappingFileIfPresent(bookId, folderKey) }
            .onFailure { AppLog.e("Scan", "mapping.json import failed for book=$bookId", it) }
    }

    /**
     * One [MediaMetadataRetriever] pass over [files], keyed by [File] (equality is by path).
     * A file whose read fails maps to `null` — logged here once, at the point of failure — and
     * every caller applies its own fallback for that: clustering treats it as a blank album,
     * import drops the file from the book entirely, matching the pre-refactor behaviour of each.
     */
    private fun readTags(files: List<File>): Map<File, FileTags?> {
        if (files.isEmpty()) return emptyMap()
        // Recreated on failure: a MediaMetadataRetriever whose setDataSource() threw is left in
        // an undefined state, and reusing it can poison every later file in the same folder.
        var retriever = MediaMetadataRetriever()
        val result = LinkedHashMap<File, FileTags?>()
        for (file in files) {
            try {
                retriever.setDataSource(file.absolutePath)
                // MediaMetadataRetriever returns no duration for very large m4b files (7 GB / 261 h
                // observed) without throwing. A 0 duration collapses every chapter to position 0 in
                // buildChapters, so fall back to the MP4's own mvhd header.
                var durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0L) {
                    durationMs = Mp4Probe.durationMs(file.absolutePath, file.extension)
                    if (durationMs > 0L) {
                        AppLog.i("Scan", "duration from mvhd for ${file.name}: ${durationMs}ms")
                    }
                }
                result[file] = FileTags(
                    durationMs = durationMs,
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                    composer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE),
                    year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                    date = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
                    chapterTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    trackStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                )
            } catch (e: Exception) {
                // Was silently swallowed: the file vanished from audioEntities while the Book was
                // still created with fileCount = sortedFiles.size, leaving a book that claims files
                // it has no rows for and that reconcileAgainstDisk skips forever.
                AppLog.e("Scan", "metadata read failed, skipping ${file.absolutePath} (${file.length()} bytes)", e)
                result[file] = null
                runCatching { retriever.release() }
                retriever = MediaMetadataRetriever()
            }
        }
        retriever.release()
        return result
    }

    private suspend fun importMappingFileIfPresent(bookId: Long, folderKey: String) {
        val mapping = com.betteraudio.data.sync.MappingFileIO.read(folderKey) ?: return
        val book = repository.getBookOnce(bookId) ?: return
        if (book.ebookPath == null) return
        if (repository.syncAnchorCount(bookId).first() > 0) return
        mapping.chapterMapJson?.let { repository.setChapterMap(bookId, it) }
        if (mapping.anchors.isNotEmpty()) {
            repository.insertSyncAnchors(mapping.anchors.map { it.copy(bookId = bookId) })
            AppLog.i("Scan", "imported mapping.json for book=$bookId anchors=${mapping.anchors.size}")
        }
    }

    /**
     * Build Chapter rows for a book: embedded markers where present, otherwise exactly one row
     * per file (the whole file). A book is never artificially divided — chapters only exist when
     * the audio's own metadata defines them.
     */
    private suspend fun buildChapters(bookId: Long, embeddedByPath: Map<String, List<RawChapter>>) {
        val storedFiles = repository.getAudioFilesOnce(bookId) // sorted by track/name
        val chapters = mutableListOf<Chapter>()
        var order = 0
        storedFiles.forEach { file ->
            val embedded = embeddedByPath[file.filePath].orEmpty()
            when {
                embedded.isNotEmpty() -> {
                    embedded.forEachIndexed { i, raw ->
                        val nextStart = embedded.getOrNull(i + 1)?.startMs ?: file.durationMs
                        chapters.add(
                            Chapter(
                                bookId = bookId,
                                fileId = file.id,
                                title = raw.title,
                                startInFileMs = raw.startMs.coerceIn(0, file.durationMs),
                                durationMs = (nextStart - raw.startMs).coerceAtLeast(0),
                                orderIndex = order++,
                                source = "embedded"
                            )
                        )
                    }
                }
                else -> {
                    chapters.add(
                        Chapter(
                            bookId = bookId,
                            fileId = file.id,
                            title = file.chapterTitle?.takeIf { it.isNotBlank() }
                                ?: file.fileName.substringBeforeLast('.'),
                            startInFileMs = 0,
                            durationMs = file.durationMs,
                            orderIndex = order++,
                            source = "per_file"
                        )
                    )
                }
            }
        }
        repository.replaceChapters(bookId, chapters)
    }

    /** Extracts embedded cover art (once) into [folderKey]'s own `data/` folder — the canonical
     *  cover location; a legacy hidden `.cover*.jpg` next to the audio is only ever detected in
     *  place now, never written fresh (see the priority chain in [importBook]). */
    private suspend fun extractCoverArt(file: File?, bookId: Long, folderKey: String) {
        file ?: return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture ?: return
            val coverPath = bookDataStore.writeCoverBytes(folderKey, "embedded", "jpg", art) ?: return
            // BookDataStore puts a .nomedia in data/, but keep writing one in the book's own
            // folder too — that is pre-existing behaviour for every book this ever extracted a
            // cover for, and dropping it would make previously-hidden audiobook folders start
            // appearing in the phone's gallery/music apps.
            val folder = File(file.parent ?: return)
            val nomedia = File(folder, ".nomedia")
            if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
            repository.updateCoverArt(bookId, coverPath)
        } catch (e: Exception) {
            AppLog.e("Scan", "extractCoverArt failed for ${file.path}", e)
        } finally {
            retriever.release()
        }
    }

    // ── Book grouping within a folder ─────────────────────────────────────────

    /**
     * Decide how a folder's loose audio files split into books. Embedded ALBUM tags are the
     * most reliable signal, so they take priority:
     *  - every file tagged and ≥2 distinct albums → one book per album (loose multi-book folder)
     *  - every file tagged with a single album    → exactly one book (never shatter it)
     *  - otherwise (untagged / mixed)             → fall back to the filename-stem heuristic
     */
    private fun groupFilesIntoBooks(files: List<File>, tags: Map<File, FileTags?>): List<Pair<String, List<File>>> {
        val albumOf = files.associateWith { (tags[it]?.album ?: "").trim() }

        val allTagged = files.all { albumOf[it]!!.isNotBlank() }
        val distinctAlbums = files.mapNotNull { albumOf[it]?.takeIf { a -> a.isNotBlank() } }
            .map { it.lowercase() }.toSet()

        return when {
            allTagged && distinctAlbums.size >= 2 ->
                files.groupBy { albumOf[it]!! }
                    .entries
                    .sortedBy { e -> e.value.minOf { ScannerHeuristics.extractTrackNumber(it.nameWithoutExtension) } }
                    .map { (album, fs) ->
                        album to fs.sortedWith(compareBy({ ScannerHeuristics.extractTrackNumber(it.nameWithoutExtension) }, { it.name }))
                    }
            allTagged && distinctAlbums.size == 1 ->
                listOf("" to files)   // single tagged album → one book
            else ->
                ScannerHeuristics.clusterBySimilarName(files)
        }
    }

    private fun String.titleCase(): String =
        split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun String.safeFileName(): String =
        replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').ifBlank { "book" }

    private fun parseYear(raw: String?): Int? =
        raw?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun File.listAudioFiles(cache: DirCache): List<File> =
        cache.listFiles(this).filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
}
