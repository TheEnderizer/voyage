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

// Sub-folder names that label a disc/part of one book rather than a separate book,
// e.g. "Mistborn 1 - The Final Empire (1 of 3)" or "Disc 2".
private val DISC_FOLDER_REGEX = Regex(
    "\\(\\d+\\s+of\\s+\\d+\\)|\\b(?:disc|disk|cd|part|pt)\\s*\\d+",
    RegexOption.IGNORE_CASE
)

// Detects an inline volume/part number in a filename, e.g. "Shadow Slave Volume 7 ...".
private val VOLUME_IN_NAME_REGEX = Regex(
    "\\b(?:volume|vol|part|pt|book)\\s*(\\d+)\\b",
    RegexOption.IGNORE_CASE
)

@Singleton
class AudioFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore
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
        val count = try {
            when (structure) {
                ImportStructure.AUTO -> scanFolder(root, seriesName = null, seriesOrder = null)
                ImportStructure.AUTHOR_SERIES_BOOK -> scanAuthorSeriesBook(root)
                ImportStructure.AUTHOR_DASH_SERIES_BOOK -> scanAuthorDashSeriesBook(root)
            }
        } catch (e: Throwable) {
            AppLog.e("Scan", "failed for $rootPath", e); throw e
        }
        // Auto-joining is disabled: series membership comes from folder structure (seriesName)
        // and playback groups are only ever created by an explicit user action.
        runCatching { reconcileAgainstDisk(root) }
            .onFailure { AppLog.e("Scan", "reconcile failed for $rootPath", it) }
        AppLog.i("Scan", "done path=$rootPath imported/updated=$count")
        count
    }

    // ── Structured import: root/author/[series/]book/files ─────────────────────
    private suspend fun scanAuthorSeriesBook(root: File): Int {
        var count = 0
        val authorDirs = root.listDirs()
        for (authorDir in authorDirs) {
            val author = authorDir.name
            // An author folder with audio directly under it is itself a single book.
            if (authorDir.hasDirectAudio()) {
                count += importFolderAsBook(authorDir, forcedAuthor = author, seriesName = null, seriesOrder = null)
                continue
            }
            for (child in authorDir.listDirs()) {
                if (child.hasDirectAudio() || child.isDiscSplitBook()) {
                    // author/book  → standalone book, no series
                    count += importFolderAsBook(child, forcedAuthor = author, seriesName = null, seriesOrder = null)
                } else {
                    // author/series/book…
                    val series = child.name
                    for ((bIndex, bookDir) in child.listDirs().withIndex()) {
                        count += importFolderAsBook(
                            bookDir, forcedAuthor = author, seriesName = series,
                            seriesOrder = seriesOrderFor(bookDir, bIndex)
                        )
                    }
                }
            }
        }
        return count
    }

    // ── Structured import: root/(author - series)/book/files ───────────────────
    private suspend fun scanAuthorDashSeriesBook(root: File): Int {
        var count = 0
        for (wrapper in root.listDirs()) {
            // A wrapper that holds audio directly (no book sub-folders) is itself one book;
            // author then comes from the file tags.
            if (wrapper.hasDirectAudio() || wrapper.isDiscSplitBook()) {
                count += importFolderAsBook(wrapper, forcedAuthor = null, seriesName = null, seriesOrder = null)
                continue
            }
            val dash = wrapper.name.indexOf(" - ")
            val author = if (dash >= 0) wrapper.name.substring(0, dash).trim().ifBlank { null } else null
            val series = if (dash >= 0) wrapper.name.substring(dash + 3).trim().ifBlank { null } else wrapper.name.trim()
            for ((bIndex, bookDir) in wrapper.listDirs().withIndex()) {
                count += importFolderAsBook(
                    bookDir, forcedAuthor = author, seriesName = series,
                    seriesOrder = seriesOrderFor(bookDir, bIndex)
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
        seriesOrder: Float?
    ): Int {
        val direct = bookDir.listAudioFiles()
        val files: List<File>
        val preserveOrder: Boolean
        if (direct.isNotEmpty()) {
            files = direct
            preserveOrder = false
        } else if (bookDir.isDiscSplitBook()) {
            files = bookDir.listDirs().filter { it.listAudioFiles().isNotEmpty() }.flatMap { disc ->
                disc.listAudioFiles().sortedWith(compareBy({ extractTrackNumber(it.nameWithoutExtension) }, { it.name }))
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
        val n = extractTrackNumber(bookDir.name)
        return if (n != Int.MAX_VALUE) n.toFloat() else (index + 1).toFloat()
    }

    private fun File.listDirs(): List<File> =
        listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun File.hasDirectAudio(): Boolean = listAudioFiles().isNotEmpty()

    /** True when this folder has no direct audio but ≥2 disc/part-labelled audio sub-folders. */
    private fun File.isDiscSplitBook(): Boolean {
        if (hasDirectAudio()) return false
        val audioSubs = listDirs().filter { it.listAudioFiles().isNotEmpty() }
        return audioSubs.size >= 2 && audioSubs.all { looksLikePartFolder(it) }
    }

    // ── Reconcile DB against disk (hide missing books, drop missing files) ──────
    private suspend fun reconcileAgainstDisk(root: File) {
        // Never prune when we can't actually read the tree — a revoked permission would
        // otherwise report every file as missing and hide the whole library.
        if (!root.exists() || !root.canRead()) return
        val rootPath = root.absolutePath
        val books = repository.getAllBooksIncludingIgnoredOnce()
        for (book in books) {
            val bookRoot = book.folderPath.substringBefore("::")
            // only touch books under this root (exact dir or a descendant path)
            if (bookRoot != rootPath && !bookRoot.startsWith("$rootPath${File.separator}")) continue
            val files = repository.getAudioFilesOnce(book.id)
            if (files.isEmpty()) continue
            val present = files.filter { File(it.filePath).exists() }
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
    private suspend fun scanFolder(dir: File, seriesName: String?, seriesOrder: Float?): Int {
        val directAudio = dir.listAudioFiles()
        var count = 0

        if (directAudio.isNotEmpty()) {
            val clusters = groupFilesIntoBooks(directAudio)
            val multiBook = clusters.size > 1
            clusters.forEach { (stem, files) ->
                val folderKey = if (multiBook) "${dir.absolutePath}::$stem" else dir.absolutePath
                val defaultTitle = if (multiBook && stem.isNotBlank()) stem.titleCase() else dir.name
                importBook(dir, folderKey, defaultTitle, files, multiBook, seriesName, seriesOrder)
                count++
            }
        }

        // Recurse into sub-directories (series structure / mixed folders)
        val subdirs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (subdirs.isNotEmpty()) {
            val subdirsWithAudio = subdirs.filter { it.listAudioFiles().isNotEmpty() }
            // A folder whose only audio-bearing children are disc/part labels of the same book
            // (e.g. "(1 of 3)", "(2 of 3)") is one book split across discs, not a series.
            val isDiscContainer = directAudio.isEmpty() && subdirsWithAudio.size >= 2 &&
                subdirsWithAudio.size == subdirs.size &&
                subdirsWithAudio.all { looksLikePartFolder(it) }
            if (isDiscContainer) {
                val allFiles = subdirsWithAudio.flatMap { disc ->
                    disc.listAudioFiles().sortedWith(compareBy({ extractTrackNumber(it.nameWithoutExtension) }, { it.name }))
                }
                importBook(dir, dir.absolutePath, dir.name, allFiles, false, seriesName, seriesOrder, preserveOrder = true)
                count++
            } else {
                // Only a pure container (no direct audio) with >1 child is treated as a series
                val isSeriesContainer = directAudio.isEmpty() && subdirs.size > 1
                count += subdirs.mapIndexed { index, subdir ->
                    val childSeriesName = if (isSeriesContainer) dir.name else seriesName
                    val childOrder = if (isSeriesContainer) (index + 1).toFloat() else seriesOrder
                    scanFolder(subdir, childSeriesName, childOrder)
                }.sum()
            }
        }
        return count
    }

    // A sub-folder name that labels a disc/part of one book rather than a separate book,
    // e.g. "Mistborn 1 - The Final Empire (1 of 3)" or "Disc 2".
    private fun looksLikePartFolder(dir: File): Boolean = DISC_FOLDER_REGEX.containsMatchIn(dir.name)

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
        keepFolderTitle: Boolean = false
    ) {
        val existing = repository.getBookByFolder(folderKey)

        // Disc-merged books arrive pre-ordered (disc, then track within disc); re-sorting by
        // a filename-only track number would interleave each disc's "track 1, track 2, ..."
        // back together across discs.
        val sortedFiles = if (preserveOrder) audioFiles else audioFiles.sortedWith(
            compareBy({ extractTrackNumber(it.nameWithoutExtension) }, { it.name })
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

        val retriever = MediaMetadataRetriever()

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
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                totalDuration += durationMs

                if (index == 0) {
                    val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                    if (!metaTitle.isNullOrBlank() && !multiBook && !keepFolderTitle) bookTitle = metaTitle
                    if (!metaArtist.isNullOrBlank() && bookAuthor.isBlank()) bookAuthor = metaArtist
                    narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                        ?.takeIf { it.isNotBlank() } ?: narrator
                    genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                        ?.takeIf { it.isNotBlank() } ?: genre
                    year = parseYear(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ) ?: year
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?.takeIf { it.isNotBlank() } ?: album
                }

                val chapterTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val trackStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                val trackNum = trackStr?.split("/")?.firstOrNull()?.toIntOrNull() ?: (index + 1)

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
            } catch (_: Exception) {}
        }
        retriever.release()

        // Resolve (or create) a first-class Series row for a brand-new book so seriesId is set
        // from the start. Existing books keep whatever series membership the user has arranged.
        val resolvedSeriesId = if (existing == null && !seriesName.isNullOrBlank())
            seriesRepository.getOrCreateSeriesByName(seriesName, bookAuthor)
        else null

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
                    fileCount = sortedFiles.size,
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
                    existing.copy(totalDurationMs = totalDuration, fileCount = sortedFiles.size)
                )
            }
        }

        // Only rewrite audio files when the file set actually changed (re-inserting would
        // null out PlaybackProgress.currentFileId and reset the resume position).
        if (filesChanged) {
            repository.clearAudioFiles(bookId)
            repository.insertAudioFiles(audioEntities.map { it.copy(bookId = bookId) })
        }

        // (Re)build chapters when files changed or none exist yet.
        if (filesChanged || repository.chapterCountForBook(bookId) == 0) {
            buildChapters(bookId, embeddedByPath)
        }

        if (existing?.coverArtPath == null) {
            val coverName = if (multiBook) ".cover_${folderKey.substringAfterLast("::").safeFileName()}.jpg" else ".cover.jpg"
            extractCoverArt(sortedFiles.firstOrNull(), bookId, folder, coverName)
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
        } catch (_: Exception) {
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
    private fun groupFilesIntoBooks(files: List<File>): List<Pair<String, List<File>>> {
        val albumOf = HashMap<File, String>()
        val retriever = MediaMetadataRetriever()
        try {
            files.forEach { f ->
                val album = try {
                    retriever.setDataSource(f.absolutePath)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.trim().orEmpty()
                } catch (_: Exception) { "" }
                albumOf[f] = album
            }
        } finally {
            retriever.release()
        }

        val allTagged = files.all { albumOf[it]!!.isNotBlank() }
        val distinctAlbums = files.mapNotNull { albumOf[it]?.takeIf { a -> a.isNotBlank() } }
            .map { it.lowercase() }.toSet()

        return when {
            allTagged && distinctAlbums.size >= 2 ->
                files.groupBy { albumOf[it]!! }
                    .entries
                    .sortedBy { e -> e.value.minOf { extractTrackNumber(it.nameWithoutExtension) } }
                    .map { (album, fs) ->
                        album to fs.sortedWith(compareBy({ extractTrackNumber(it.nameWithoutExtension) }, { it.name }))
                    }
            allTagged && distinctAlbums.size == 1 ->
                listOf("" to files)   // single tagged album → one book
            else ->
                clusterBySimilarName(files)
        }
    }

    // ── Filename clustering ───────────────────────────────────────────────────

    /**
     * Group a folder's flat audio files into books by similar name. Files whose names
     * share a common "stem" (after stripping leading/trailing sequence numbers) cluster
     * together. The folder is only split into multiple books when there is more than one
     * stem AND at least one stem forms a real sequence (≥2 files) — this avoids shattering
     * a single book whose chapters happen to have unique titles.
     */
    private fun clusterBySimilarName(files: List<File>): List<Pair<String, List<File>>> {
        val groups = files.groupBy { stemKey(it.nameWithoutExtension) }
        // Only split when there are ≥2 genuine sequences. One sequence plus a few stray
        // files (intro/outro/bonus) is far more likely a single book than several books.
        val sequenceGroups = groups.values.count { it.size >= 2 }
        if (groups.size <= 1 || sequenceGroups < 2) {
            return splitBySequentialVolumeNumber(files) ?: listOf("" to files)
        }
        // Stable order: by the earliest track number / name within each group
        return groups.entries
            .sortedBy { entry ->
                entry.value.minOf { extractTrackNumber(it.nameWithoutExtension) }
            }
            .map { it.key to it.value }
    }

    /**
     * Fallback for folders where every file is its own one-file "sequence" (so the stem
     * clustering above refuses to split them), but each filename still carries a distinct
     * inline volume/part number — e.g. "Shadow Slave Volume 7 ..." / "... Volume 8 ...".
     * `stemKey` only strips leading/trailing sequence tokens, so a mid-filename number like
     * this survives into the stem and the two files never share a stem.
     */
    private fun splitBySequentialVolumeNumber(files: List<File>): List<Pair<String, List<File>>>? {
        if (files.size < 2) return null
        val numbered = files.map { f ->
            f to VOLUME_IN_NAME_REGEX.find(f.nameWithoutExtension)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (numbered.any { it.second == null }) return null
        val numbers = numbered.map { it.second!! }
        if (numbers.toSet().size != files.size) return null
        return numbered.sortedBy { it.second }.map { (f, n) -> "vol$n" to listOf(f) }
    }

    private fun stemKey(name: String): String {
        var s = name.lowercase().trim()
        // strip a leading sequence token: "01 - ", "1. ", "3) "
        s = s.replace(Regex("^\\s*\\d{1,4}\\s*[-_.)\\]]*\\s*"), "")
        // strip a trailing sequence token (optionally prefixed by a word like part/track/cd)
        s = s.replace(
            Regex(
                "[\\s\\-_.(\\[]*(?:cd|disc|disk|part|pt|track|chapter|chap|ch|vol|volume|book|episode|ep)?[\\s\\-_.#]*\\d{1,4}\\s*[)\\]]*\\s*$"
            ),
            ""
        )
        return s.replace(Regex("[\\s\\-_.]+"), " ").trim()
    }

    private fun String.titleCase(): String =
        split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun String.safeFileName(): String =
        replace(Regex("[^a-zA-Z0-9]+"), "_").trim('_').ifBlank { "book" }

    private fun parseYear(raw: String?): Int? =
        raw?.let { Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun File.listAudioFiles(): List<File> =
        listFiles()?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS } ?: emptyList()

    private fun extractTrackNumber(name: String): Int =
        Regex("^(\\d+)").find(name.trim())?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
}
