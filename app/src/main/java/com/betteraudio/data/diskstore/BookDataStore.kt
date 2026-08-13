package com.betteraudio.data.diskstore

import com.betteraudio.BuildConfig
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ListeningHistoryDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.dao.SyncAnchorDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.sync.ChapterMap
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes one book's `data/` folder — `book.json` (or `<slug>.json` / `epub.<slug>.json`
 * for a shared/ebook-only folderKey), its cover, and orphan cleanup. This is the only class that
 * translates between [BookDocument] and the real Room entities; everything above it (DiskMirror,
 * RestoreOps, the scanner) works in one language or the other, never both.
 *
 * Deliberately injects DAOs, not [com.betteraudio.data.repository.AudiobookRepository] — the
 * repository is what calls DiskMirror, which calls this, so the reverse dependency would be a
 * Hilt cycle.
 */
@Singleton
class BookDataStore @Inject constructor(
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val progressDao: PlaybackProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val listeningHistoryDao: ListeningHistoryDao,
    private val syncAnchorDao: SyncAnchorDao,
    private val settings: SettingsStore
) {
    // Cover source ("user" | "embedded" | "external") is known precisely only at the moment a
    // cover is written (see writeCover*), because Book has no column for it. This in-memory hint
    // makes the very next write(bookId) in the same process tag it correctly; across a process
    // restart, buildCoverInfo falls back to reusing whatever the on-disk doc already recorded, or
    // a filename heuristic for a cover the scanner merely detected (legacy cover.png/.cover.jpg)
    // without ever going through this store.
    private val lastCoverSource = ConcurrentHashMap<String, String>()

    /**
     * Reads the doc for [folderKey], or null if there is none / it's unreadable. When [fileNames]
     * is supplied and the direct slug lookup misses (or resolves to a doc for a different
     * folderKey — a hash collision), falls back to scanning every other doc in the same data dir
     * and matching by file-basename overlap, since folderKey is NOT stable: adding, removing, or
     * retagging a file in an AUTO cluster folder changes every member's folderKey. A match found
     * this way is rewritten under the new slug so the visible data/ folder self-heals instead of
     * accumulating stale JSON.
     */
    suspend fun read(folderKey: String, fileNames: List<String> = emptyList()): BookDocument? {
        val dir = BookDataPaths.dataDir(folderKey)
        val docFile = File(dir, BookDataPaths.docFileName(folderKey))
        readDocFile(docFile)?.let { doc -> if (doc.folderPath == folderKey) return doc }

        if (fileNames.isEmpty() || !dir.isDirectory) return null
        val wanted = fileNames.toSet()
        var bestFile: File? = null
        var bestDoc: BookDocument? = null
        var bestScore = 0.0
        var tie = false
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.forEach { candidate ->
            if (candidate == docFile) return@forEach
            val doc = readDocFile(candidate) ?: return@forEach
            val overlap = doc.files.map { it.fileName }.toSet().intersect(wanted).size
            if (overlap == 0) return@forEach
            val score = overlap.toDouble() / wanted.size
            when {
                score > bestScore -> { bestFile = candidate; bestDoc = doc; bestScore = score; tie = false }
                score == bestScore -> tie = true
            }
        }
        if (bestDoc == null || bestScore < 0.5 || tie) return null
        val relocated = bestDoc!!.copy(folderPath = folderKey)
        if (writeDoc(folderKey, relocated)) {
            val oldFile = bestFile!!
            val oldBase = oldFile.nameWithoutExtension
            val newBase = BookDataPaths.docFileName(folderKey).removeSuffix(".json")
            runCatching { oldFile.delete() }
            relocateSiblings(dir, oldBase, newBase)
            AppLog.i(LogCat.DISK, "relocated disk data for folderKey=$folderKey (was ${oldFile.name})")
        }
        return relocated
    }

    /** 0L when there is no doc for [folderKey] yet. */
    fun lastModified(folderKey: String): Long {
        val f = File(BookDataPaths.dataDir(folderKey), BookDataPaths.docFileName(folderKey))
        return if (f.isFile) f.lastModified() else 0L
    }

    /** Gathers everything precious about [bookId] from Room and writes it to disk. The one full
     *  writer — both the periodic DiskMirror flush and the scan-time FRESH_IMPORT/MERGE paths
     *  read this same shape back via [read]. */
    suspend fun write(bookId: Long): Boolean {
        val book = bookDao.getBookOnce(bookId) ?: return false
        val folderKey = book.folderPath
        val dir = BookDataPaths.containingDir(folderKey)
        val files = audioFileDao.getFilesForBookOnce(bookId)
        val progress = progressDao.getProgressForBookOnce(bookId)
        val bookmarks = bookmarkDao.getForBook(bookId).first()
        val sessions = listeningHistoryDao.getSessionsForBook(bookId).first()
        val skips = listeningHistoryDao.getSkipsForBook(bookId).first()
        val libraryFolder = settings.currentLibraryFolder
        val existing = readDocFile(File(BookDataPaths.dataDir(folderKey), BookDataPaths.docFileName(folderKey)))

        val fileById = files.associateBy { it.id }
        val doc = BookDocument(
            writtenAt = System.currentTimeMillis(),
            app = BuildConfig.VERSION_NAME,
            folderPath = folderKey,
            relPath = BookDataPaths.relPath(folderKey, libraryFolder),
            kind = if (files.isEmpty() && book.ebookPath != null) "EPUB" else "AUDIO",
            title = book.title,
            author = book.author,
            titleOverride = book.titleOverride,
            authorOverride = book.authorOverride,
            narrator = book.narrator,
            genre = book.genre,
            year = book.year,
            album = book.album,
            description = book.description,
            synopsis = book.synopsis,
            status = book.status.name,
            isIgnored = book.isIgnored,
            skipSilenceEnabled = book.skipSilenceEnabled,
            addedDateMs = book.addedDateMs,
            totalDurationMs = book.totalDurationMs,
            fileCount = book.fileCount,
            cover = buildCoverInfo(book, folderKey, dir, existing),
            series = book.seriesName?.takeIf { it.isNotBlank() }?.let { BookDocument.SeriesRef(it, book.seriesOrder) },
            ebook = book.ebookPath?.let { path ->
                BookDocument.EbookInfo(
                    relPath = BookDataPaths.relativizeToDir(path, dir),
                    spineCount = book.ebookSpineCount,
                    chapterMap = book.chapterMapJson?.let { ChapterMap.fromJson(it)?.audioToSpine }
                )
            },
            files = files.map { f ->
                BookDocument.FileEntry(f.fileName, f.durationMs, f.trackNumber, f.title, f.chapterTitle, f.damageRangesJson)
            },
            progress = progress?.let { p ->
                BookDocument.ProgressEntry(
                    positionMs = p.positionMs,
                    lastPlayedMs = p.lastPlayedMs,
                    currentFile = p.currentFileId?.let { fid -> fileById[fid] }
                        ?.let { BookDocument.CurrentFileRef(it.fileName, it.durationMs) },
                    playbackSpeed = p.playbackSpeed,
                    boostDb = p.boostDb,
                    eqBandsJson = p.eqBandsJson,
                    isCompleted = p.isCompleted,
                    completedDateMs = p.completedDateMs,
                    lastPausedAt = p.lastPausedAt,
                    textSpineIndex = p.textSpineIndex,
                    textFraction = p.textFraction,
                    textOverallFraction = p.textOverallFraction,
                    lastMode = p.lastMode
                )
            },
            bookmarks = bookmarks.mapNotNull { bm ->
                fileById[bm.fileId]?.fileName?.let { fn ->
                    BookDocument.BookmarkEntry(fn, bm.positionInFileMs, bm.absolutePositionMs, bm.comment, bm.createdAt)
                }
            },
            sessions = sessions.map { s ->
                BookDocument.SessionEntry(
                    s.startMs, s.endMs, s.startChapterIndex, s.startChapterName, s.endChapterIndex,
                    s.endChapterName, s.startPositionInChapterMs, s.endPositionInChapterMs, s.endBookPositionMs, s.listenedMs
                )
            },
            skipEvents = skips.map { sk ->
                BookDocument.SkipEventEntry(
                    sk.atMs, sk.kind, sk.source, sk.fromPositionMs, sk.toPositionMs, sk.chapterIndex, sk.chapterName,
                    sk.fromSpineIndex, sk.fromFraction, sk.toSpineIndex, sk.toFraction, sk.toSpineTitle
                )
            },
            unknown = existing?.unknown ?: emptyMap()
        )
        // Content-hash skip: writtenAt always differs, so compare with it zeroed out on both
        // sides — a flush triggered by an unrelated dirty book in the same batch, or a duplicate
        // trigger firing for the same pause/stop event, shouldn't re-serialize an unchanged doc.
        if (existing != null && existing.copy(writtenAt = 0L) == doc.copy(writtenAt = 0L)) return true
        return writeDoc(folderKey, doc)
    }

    /** Removes only [book]'s own doc/cover/mapping-file inside its data dir — never the shared
     *  data dir itself, which an AUTO-cluster sibling may still own. Safe to call for a book that
     *  never had disk data (no-op, returns true). */
    suspend fun delete(book: Book): Boolean = runCatching {
        val folderKey = book.folderPath
        val dir = BookDataPaths.dataDir(folderKey)
        if (!dir.isDirectory) return@runCatching true
        File(dir, BookDataPaths.docFileName(folderKey)).delete()
        val coverBase = BookDataPaths.coverBaseName(folderKey)
        dir.listFiles { f -> f.isFile && f.nameWithoutExtension == coverBase }?.forEach { it.delete() }
        File(dir, BookDataPaths.mappingFileName(folderKey)).delete()
        true
    }.getOrElse {
        AppLog.w(LogCat.DISK, "delete failed for ${book.folderPath}: ${it.message}")
        false
    }

    suspend fun writeCoverBytes(folderKey: String, source: String, ext: String, bytes: ByteArray): String? =
        writeCoverInternal(folderKey, source, ext) { target -> target.writeBytes(bytes) }

    suspend fun writeCoverStream(folderKey: String, source: String, ext: String, input: InputStream): String? =
        writeCoverInternal(folderKey, source, ext) { target -> target.outputStream().use { out -> input.copyTo(out) } }

    suspend fun writeCoverFile(folderKey: String, source: String, ext: String, sourceFile: File): String? =
        writeCoverInternal(folderKey, source, ext) { target -> sourceFile.copyTo(target, overwrite = true) }

    /** Resolves a doc-recorded cover path (relative to the book's containing directory, or an
     *  absolute fallback) back to a real file, or null if it no longer exists. */
    fun resolveCover(folderKey: String, relOrAbs: String): File? {
        val file = BookDataPaths.resolveRelOrAbs(BookDataPaths.containingDir(folderKey), relOrAbs)
        return file.takeIf { it.isFile }
    }

    /** Deletes any leftover doc JSON in `data/` (and its cover/mapping siblings) not claimed by [liveFolderKeys]
     *  — called once per scanned directory after its cluster loop, so a book removed from an AUTO
     *  folder (file deleted/renamed away) doesn't leave a stale doc behind forever. */
    fun sweepOrphans(dir: File, liveFolderKeys: Set<String>) {
        val dataDir = File(dir, BookDataPaths.DATA_DIR_NAME)
        if (!dataDir.isDirectory) return
        val liveDocNames = liveFolderKeys.map { BookDataPaths.docFileName(it) }.toSet()
        val liveBaseNames = liveDocNames.map { it.removeSuffix(".json") }.toSet()
        dataDir.listFiles()?.forEach { f ->
            // Only files this app itself recognizably produces (a doc, a mapping file, or a
            // cover) are ever candidates for deletion — anything else in data/ is left alone
            // unconditionally, since the folder is deliberately open for future book-specific
            // features to keep their own files here without this sweep touching them.
            if (!f.isFile || !isRecognizedDiskstoreFile(f.name)) return@forEach
            val isLiveDoc = f.name in liveDocNames
            val isLiveSibling = liveBaseNames.any { base -> f.name.startsWith("$base.") }
            if (!isLiveDoc && !isLiveSibling) runCatching { f.delete() }
        }
    }

    /**
     * Whether [name] is a file this store itself produces, and therefore a candidate for the
     * orphan sweep. Matched narrowly — the exact single-book names, or a `<slug>.`-prefixed
     * cluster name, where [BookDataPaths.slug] always ends in "~" + 8 hex chars. A blanket
     * "*.json" test would be simpler but would make the sweep delete files belonging to any
     * future per-book feature that keeps its own state in data/, which the folder is explicitly
     * meant to host.
     */
    private fun isRecognizedDiskstoreFile(name: String): Boolean =
        name == "book.json" || name == "mapping.json" || name.startsWith("cover.") ||
            SLUG_PREFIXED.containsMatchIn(name)

    /** Always on IO: the gallery-pick and online-search call sites are ViewModels writing from a
     *  Main-dispatched viewModelScope, and the target is external storage. */
    private suspend fun writeCoverInternal(
        folderKey: String, source: String, ext: String, writer: (File) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val dir = BookDataPaths.dataDir(folderKey)
        runCatching {
            dir.mkdirs()
            val baseName = BookDataPaths.coverBaseName(folderKey)
            dir.listFiles { f -> f.isFile && f.nameWithoutExtension == baseName }?.forEach { it.delete() }
            val target = File(dir, "$baseName.$ext")
            writer(target)
            ensureNoMedia(dir)
            lastCoverSource[folderKey] = source
            target.absolutePath
        }.getOrElse {
            AppLog.w(LogCat.DISK, "writeCover failed for $folderKey: ${it.message}")
            null
        }
    }

    private fun buildCoverInfo(book: Book, folderKey: String, dir: File, existing: BookDocument?): BookDocument.CoverInfo? {
        val path = book.coverArtPath ?: return null
        val relOrAbs = BookDataPaths.relativizeToDir(path, dir)
        val prevCover = existing?.cover
        val prevAbs = prevCover?.let { BookDataPaths.resolveRelOrAbs(dir, it.relPath).absolutePath }
        val knownSource = lastCoverSource[folderKey]
        return when {
            knownSource != null -> BookDocument.CoverInfo(relOrAbs, knownSource, System.currentTimeMillis())
            prevCover != null && prevAbs == path -> prevCover.copy(relPath = relOrAbs)
            else -> BookDocument.CoverInfo(relOrAbs, inferCoverSource(File(path).name), System.currentTimeMillis())
        }
    }

    /** Last-resort classification for a cover BookDataStore never wrote itself (a legacy
     *  cover.png/.cover.jpg the scanner merely detected) — see the class doc on [lastCoverSource]. */
    private fun inferCoverSource(fileName: String): String = when {
        fileName == "cover.png" -> "external"
        fileName.startsWith(".cover") -> "embedded"
        else -> "embedded"
    }

    private fun writeDoc(folderKey: String, doc: BookDocument): Boolean {
        val dir = BookDataPaths.dataDir(folderKey)
        val docFile = File(dir, BookDataPaths.docFileName(folderKey))
        val ok = writeTextAtomic(docFile, BookDataCodec.encodeToString(doc))
        if (ok) ensureNoMedia(dir)
        return ok
    }

    private fun readDocFile(file: File): BookDocument? {
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.let { BookDataCodec.decodeOrNull(it) }
    }

    /** Best-effort: renames `<oldBase>.<suffix>` siblings (cover, mapping.json) to `<newBase>.<suffix>`
     *  alongside a relocated doc. Only applies when both names follow the "<slug>.<suffix>"
     *  cluster convention — a cluster-to-single-book transition's cover/mapping is left to be
     *  rewritten fresh rather than guessed at. */
    private companion object {
        /** "<slug>." / "epub.<slug>." prefix, where a slug always ends in "~" + 8 hex chars —
         *  see [BookDataPaths.slug]. */
        val SLUG_PREFIXED = Regex("^(epub\\.)?[a-z0-9_]+~[0-9a-f]{8}\\.")
    }

    private fun relocateSiblings(dir: File, oldBase: String, newBase: String) {
        if (oldBase == newBase) return
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("$oldBase.")) {
                val suffix = f.name.removePrefix(oldBase)
                runCatching { f.renameTo(File(dir, "$newBase$suffix")) }
            }
        }
    }
}
