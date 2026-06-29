package com.betteraudio.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "ogg", "flac", "aac", "opus", "wav")

@Singleton
class AudioFileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository,
    private val autoJoiner: AutoJoiner
) {

    /**
     * [autoJoin] runs the cross-book auto-grouping pass afterwards. Enabled for explicit
     * user imports; left off for the silent launch rescan so split groups aren't re-created.
     */
    suspend fun scanDirectory(rootPath: String, autoJoin: Boolean = false): Int = withContext(Dispatchers.IO) {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            AppLog.w("Scan", "skipped — path missing or not a dir: $rootPath")
            return@withContext 0
        }
        AppLog.i("Scan", "start path=$rootPath autoJoin=$autoJoin")
        val count = try {
            scanFolder(root, seriesName = null, seriesOrder = null)
        } catch (e: Throwable) {
            AppLog.e("Scan", "failed for $rootPath", e); throw e
        }
        if (autoJoin) autoJoiner.run(rootPath)
        AppLog.i("Scan", "done path=$rootPath imported/updated=$count")
        count
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
            // Only a pure container (no direct audio) with >1 child is treated as a series
            val isSeriesContainer = directAudio.isEmpty() && subdirs.size > 1
            count += subdirs.mapIndexed { index, subdir ->
                val childSeriesName = if (isSeriesContainer) dir.name else seriesName
                val childOrder = if (isSeriesContainer) (index + 1).toFloat() else seriesOrder
                scanFolder(subdir, childSeriesName, childOrder)
            }.sum()
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
        seriesOrder: Float?
    ) {
        val existing = repository.getBookByFolder(folderKey)

        val sortedFiles = audioFiles.sortedWith(
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
        var bookAuthor = ""
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
                    if (!metaTitle.isNullOrBlank() && !multiBook) bookTitle = metaTitle
                    if (!metaArtist.isNullOrBlank()) bookAuthor = metaArtist
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

        val bookId: Long
        if (existing == null) {
            bookId = repository.upsertBook(
                Book(
                    title = bookTitle,
                    author = bookAuthor,
                    folderPath = folderKey,
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
        if (groups.size <= 1 || sequenceGroups < 2) return listOf("" to files)
        // Stable order: by the earliest track number / name within each group
        return groups.entries
            .sortedBy { entry ->
                entry.value.minOf { extractTrackNumber(it.nameWithoutExtension) }
            }
            .map { it.key to it.value }
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
