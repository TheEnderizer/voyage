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
    private val ebookScanner: EbookScanner
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
        cache.listFiles(this).filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }

    private fun File.hasDirectAudio(cache: DirCache): Boolean = listAudioFiles(cache).isNotEmpty()

    /** True when this folder has no direct audio but ≥2 disc/part-labelled audio sub-folders. */
    private fun File.isDiscSplitBook(cache: DirCache): Boolean {
        if (hasDirectAudio(cache)) return false
        val audioSubs = listDirs(cache).filter { it.listAudioFiles(cache).isNotEmpty() }
        return audioSubs.size >= 2 && audioSubs.all { ScannerHeuristics.looksLikePartFolder(it) }
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

    private suspend fun dropMissingFiles(bookId: Long, present: List<AudioFile>) {
        val totalDuration = present.sumOf { it.durationMs }
        repository.clearAudioFiles(bookId)
        repository.insertAudioFiles(present.map { it.copy(id = 0L, bookId = bookId) })
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
            clusters.forEach { (stem, files) ->
                val folderKey = if (multiBook) "${dir.absolutePath}::$stem" else dir.absolutePath
                val defaultTitle = if (multiBook && stem.isNotBlank()) stem.titleCase() else dir.name
                importBook(dir, folderKey, defaultTitle, files, multiBook, seriesName, seriesOrder, tags = tags)
                count++
            }
        }

        // Recurse into sub-directories (series structure / mixed folders)
        val subdirs = cache.listFiles(dir).filter { it.isDirectory }.sortedBy { it.name }
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
        val resolvedSeriesId = if (existing == null && !seriesName.isNullOrBlank())
            seriesRepository.getOrCreateSeriesByName(seriesName, bookAuthor)
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
                    seriesName = seriesName,
                    seriesOrder = seriesOrder,
                    totalDurationMs = totalDuration,
                    fileCount = importedCount,
                    narrator = narrator,
                    genre = genre,
                    year = year,
                    album = album
                )
            )
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
                    repository.clearAudioFiles(bookId)
                    repository.insertAudioFiles(audioEntities.map { it.copy(bookId = bookId) })
                }
                buildChapters(bookId, embeddedByPath)
            }
        }

        // Cover priority: an explicit "cover.png" dropped in the book's own folder always wins
        // (checked on every scan, not just import, so adding one later and rescanning picks it
        // up) — otherwise fall back to the embedded-metadata extraction, done once. Skipped for
        // multi-book folders (a synthetic "::" split): one cover.png there is ambiguous as to
        // which of the cluster's books it belongs to, same as the epub auto-attach below.
        val explicitCover = if (!multiBook) File(folder, "cover.png").takeIf { it.isFile } else null
        if (explicitCover != null) {
            if (existing?.coverArtPath != explicitCover.absolutePath) {
                repository.updateCoverArt(bookId, explicitCover.absolutePath)
            }
        } else if (existing?.coverArtPath == null) {
            val coverName = if (multiBook) ".cover_${folderKey.substringAfterLast("::").safeFileName()}.jpg" else ".cover.jpg"
            extractCoverArt(sortedFiles.firstOrNull(), bookId, folder, coverName)
        }

        // Auto-attach an .epub sitting next to the audio, once. Skipped for multi-book folders
        // (a synthetic "::" split) — ambiguous which of the cluster's books the epub belongs to;
        // the user can still connect it manually from that book's options.
        if (!multiBook && existing?.ebookPath == null) {
            ebookScanner.findEpubIn(folder)?.let { epub ->
                runCatching { ebookScanner.attachEpubToBook(bookId, epub) }
                    .onFailure { AppLog.e("Scan", "auto-attach epub failed for book=$bookId", it) }
            }
        }

        // Auto-import a bundled "mapping.json" (paragraph-resolution sync data — see
        // MappingFileIO) sitting in the book's folder, once an epub is connected and only while
        // the book has no anchors yet, so a bundled file is picked up without overwriting a
        // fresher on-device alignment the user already ran. Same multi-book caveat as above.
        if (!multiBook) {
            runCatching { importMappingFileIfPresent(bookId, folder) }
                .onFailure { AppLog.e("Scan", "mapping.json import failed for book=$bookId", it) }
        }
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

    private suspend fun importMappingFileIfPresent(bookId: Long, folder: File) {
        val mapping = com.betteraudio.data.sync.MappingFileIO.read(folder) ?: return
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

    private suspend fun extractCoverArt(file: File?, bookId: Long, folder: File, coverName: String) {
        file ?: return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val art = retriever.embeddedPicture ?: return
            val coverFile = File(folder, coverName)
            coverFile.writeBytes(art)
            // Keep cover art out of the phone gallery
            val nomedia = File(folder, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()
            repository.updateCoverArt(bookId, coverFile.absolutePath)
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
