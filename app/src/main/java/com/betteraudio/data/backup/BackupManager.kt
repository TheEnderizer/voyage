package com.betteraudio.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.betteraudio.data.db.AppDatabase
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.playback.PlayerController
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupUiState(
    val exporting: Boolean = false,
    val importing: Boolean = false,
    val lastResult: BackupManager.RestoreResult? = null,
    val error: String? = null
)

/**
 * Exports/imports a portable snapshot of everything the DB + DataStore know that isn't re-derived
 * by a rescan: settings, audio presets, series, authors, and per-book progress/bookmarks/history.
 * Modeled on [com.betteraudio.data.sync.MappingFileIO] (org.json, versioned root object).
 *
 * Book identity across a reinstall/rescan is NOT the DB row id (ids are not stable — see
 * [BackupMatcher]) — it's `folderPath`/`relPath`/`{title,author}`, resolved via [BackupMatcher] at
 * restore time. Files inside a book are identified by basename, not `fileId`, for the same reason.
 *
 * Import/export are launched on [appScope] (process-lifetime), not the caller's ViewModel scope —
 * navigating away from Settings mid-import must not cancel it half-applied. [uiState] is owned
 * here so it survives the SettingsViewModel being recreated by that same navigation.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore,
    private val playerController: PlayerController,
    private val restoreOps: com.betteraudio.data.diskstore.RestoreOps,
    private val diskMirror: com.betteraudio.data.diskstore.DiskMirror,
    @ApplicationScope private val appScope: CoroutineScope
) {
    companion object {
        const val SCHEMA_VERSION = 1
        // Gives the position-save the stop triggers on the service side (fire-and-forget, over
        // IPC) time to land before restore starts reading/writing progress for the same book.
        private const val STOP_SETTLE_MS = 400L
    }

    data class RestoreResult(
        val booksMatched: Int,
        val booksSkippedNoMatch: Int,
        val booksSkippedAmbiguous: Int,
        val booksSkippedStale: Int, // matched, but local progress was newer and forceOverwrite was off
        val bookmarksRestored: Int,
        val sessionsRestored: Int,
        val skipEventsRestored: Int,
        val presetsRestored: Int,
        val seriesRestored: Int,
    )

    // ── UI-facing state + entry points (survive navigation away from the caller's screen) ─────
    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun clearResult() = _uiState.update { it.copy(lastResult = null, error = null) }

    fun exportFile(uri: android.net.Uri, includeApiKey: Boolean) {
        if (_uiState.value.exporting) return
        appScope.launch {
            _uiState.update { it.copy(exporting = true, error = null) }
            try {
                withContext(Dispatchers.IO) {
                    // "wt" truncates on overwrite — plain "w" can leave trailing bytes from a
                    // previous longer file on some SAF providers.
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        export(out, includeApiKey)
                    } ?: throw java.io.IOException("Could not open the chosen file for writing")
                }
                _uiState.update { it.copy(exporting = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(exporting = false, error = "Export failed: ${e.message}") }
            }
        }
    }

    fun importFile(uri: android.net.Uri, forceOverwrite: Boolean) {
        if (_uiState.value.importing) return
        appScope.launch {
            _uiState.update { it.copy(importing = true, error = null, lastResult = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        restore(input, forceOverwrite)
                    } ?: throw java.io.IOException("Could not open the chosen file for reading")
                }
                _uiState.update { it.copy(importing = false, lastResult = result) }
            } catch (e: Exception) {
                _uiState.update { it.copy(importing = false, error = "Import failed: ${e.message}") }
            }
        }
    }

    /** Writes a share-ready copy (API key always stripped) to filesDir and returns it. */
    suspend fun writeShareBackupFile(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "backup_share").apply { mkdirs() }
        val file = File(dir, "voyage-backup.json")
        file.outputStream().use { out -> exportForSharing(out) }
        file
    }

    // ── Settings whitelist ──────────────────────────────────────────────────────
    // The (name, type, getter, setter) list itself now lives in SettingsSpecs — shared with the
    // disk-mirror's settings.json, so the two exporters can't drift apart. Device-local keys are
    // excluded there; see its class doc for the full list and reasoning.

    // ── Export ───────────────────────────────────────────────────────────────

    suspend fun export(out: OutputStream, includeApiKey: Boolean) {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("settings", gatherSettings(includeApiKey))
        root.put("presets", gatherPresets())
        root.put("authors", gatherAuthors())
        root.put("series", gatherSeries())
        root.put("books", gatherBooks())
        out.bufferedWriter().use { it.write(root.toString()) }
    }

    /** Export with the API key always stripped, regardless of the caller's include-key setting —
     *  used by "Share backup" so a shared file never carries the user's Gemini key. */
    suspend fun exportForSharing(out: OutputStream) = export(out, includeApiKey = false)

    private suspend fun gatherSettings(includeApiKey: Boolean): JSONArray {
        val arr = JSONArray()
        (com.betteraudio.data.diskstore.SettingsSpecs.coreSpecs() + com.betteraudio.data.diskstore.SettingsSpecs.pathSpecs()).forEach { spec ->
            val value = spec.get(settings) ?: return@forEach
            arr.put(JSONObject().apply { put("name", spec.name); put("type", spec.type); put("value", value) })
        }
        if (includeApiKey) {
            com.betteraudio.data.diskstore.SettingsSpecs.apiKeySpec().get(settings)?.let { key ->
                arr.put(JSONObject().apply { put("name", "gemini_api_key"); put("type", "string"); put("value", key) })
            }
        }
        return arr
    }

    private suspend fun gatherPresets(): JSONArray {
        val arr = JSONArray()
        repository.getAllAudioPresets().first().forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("type", p.type)
                put("speedMult", p.speedMult)
                put("boostDb", p.boostDb)
                p.eqBandsJson?.let { put("eqBandsJson", it) }
                put("isDefault", p.isDefault)
            })
        }
        return arr
    }

    private suspend fun gatherAuthors(): JSONArray {
        val arr = JSONArray()
        repository.getAllAuthorMetaOnce().forEach { a -> arr.put(JSONObject().apply { put("name", a.name) }) }
        return arr
    }

    private fun relPath(folderPath: String, libraryFolder: String): String =
        if (libraryFolder.isNotBlank() && folderPath.startsWith(libraryFolder)) {
            folderPath.removePrefix(libraryFolder).trimStart('/', '\\')
        } else folderPath

    private suspend fun gatherSeries(): JSONArray {
        val libraryFolder = settings.libraryFolder.first()
        val arr = JSONArray()
        seriesRepository.getAllSeriesOnce().forEach { s ->
            val members = seriesRepository.getBooksInSeriesOnce(s.id)
            arr.put(JSONObject().apply {
                put("name", s.name)
                s.author?.let { put("author", it) }
                s.narrator?.let { put("narrator", it) }
                s.description?.let { put("description", it) }
                s.playbackSpeed?.let { put("playbackSpeed", it) }
                s.boostDb?.let { put("boostDb", it) }
                s.eqBandsJson?.let { put("eqBandsJson", it) }
                s.skipSilenceEnabled?.let { put("skipSilenceEnabled", it) }
                put("members", JSONArray().apply {
                    members.forEach { b ->
                        put(JSONObject().apply {
                            put("folderPath", b.folderPath)
                            put("relPath", relPath(b.folderPath, libraryFolder))
                            put("title", b.title)
                            put("author", b.author)
                            b.seriesOrder?.let { put("seriesOrder", it) }
                        })
                    }
                })
            })
        }
        return arr
    }

    private suspend fun gatherBooks(): JSONArray {
        val libraryFolder = settings.libraryFolder.first()
        val arr = JSONArray()
        repository.getAllBooksIncludingIgnoredOnce().forEach { book ->
            val files = repository.getAudioFilesOnce(book.id)
            val fileNameById = files.associate { it.id to (it.fileName to it.durationMs) }
            val progress = repository.getProgressForBookOnce(book.id)
            val bookmarks = repository.getBookmarksForBook(book.id).first()
            val sessions = repository.getSessionsForBook(book.id).first()
            val skips = repository.getSkipsForBook(book.id).first()

            arr.put(JSONObject().apply {
                put("folderPath", book.folderPath)
                put("relPath", relPath(book.folderPath, libraryFolder))
                put("title", book.title)
                put("author", book.author)
                book.titleOverride?.let { put("titleOverride", it) }
                book.authorOverride?.let { put("authorOverride", it) }
                book.narrator?.let { put("narrator", it) }
                put("skipSilenceEnabled", book.skipSilenceEnabled)
                put("status", book.status.name)
                put("isIgnored", book.isIgnored)
                book.synopsis?.let { put("synopsis", it) }

                if (progress != null) {
                    put("progress", JSONObject().apply {
                        put("positionMs", progress.positionMs)
                        put("lastPlayedMs", progress.lastPlayedMs)
                        put("playbackSpeed", progress.playbackSpeed)
                        put("boostDb", progress.boostDb)
                        progress.eqBandsJson?.let { put("eqBandsJson", it) }
                        put("isCompleted", progress.isCompleted)
                        progress.completedDateMs?.let { put("completedDateMs", it) }
                        progress.lastPausedAt.let { put("lastPausedAt", it) }
                        progress.currentFileId?.let { fid ->
                            fileNameById[fid]?.let { (name, dur) ->
                                put("currentFile", JSONObject().apply {
                                    put("fileName", name); put("durationMs", dur)
                                })
                            }
                        }
                    })
                }

                put("bookmarks", JSONArray().apply {
                    bookmarks.forEach { bm ->
                        val fileName = fileNameById[bm.fileId]?.first ?: return@forEach
                        put(JSONObject().apply {
                            put("fileName", fileName)
                            put("positionInFileMs", bm.positionInFileMs)
                            put("absolutePositionMs", bm.absolutePositionMs)
                            put("comment", bm.comment)
                            put("createdAt", bm.createdAt)
                        })
                    }
                })

                put("sessions", JSONArray().apply {
                    sessions.forEach { s ->
                        put(JSONObject().apply {
                            put("startMs", s.startMs)
                            put("endMs", s.endMs)
                            put("startChapterIndex", s.startChapterIndex)
                            put("startChapterName", s.startChapterName)
                            put("endChapterIndex", s.endChapterIndex)
                            put("endChapterName", s.endChapterName)
                            put("startPositionInChapterMs", s.startPositionInChapterMs)
                            put("endPositionInChapterMs", s.endPositionInChapterMs)
                            put("endBookPositionMs", s.endBookPositionMs)
                            put("listenedMs", s.listenedMs)
                        })
                    }
                })

                put("skipEvents", JSONArray().apply {
                    skips.forEach { sk ->
                        put(JSONObject().apply {
                            put("atMs", sk.atMs)
                            put("kind", sk.kind)
                            put("fromPositionMs", sk.fromPositionMs)
                            put("toPositionMs", sk.toPositionMs)
                            put("chapterIndex", sk.chapterIndex)
                            put("chapterName", sk.chapterName)
                            sk.fromSpineIndex?.let { put("fromSpineIndex", it) }
                            sk.fromFraction?.let { put("fromFraction", it) }
                            sk.toSpineIndex?.let { put("toSpineIndex", it) }
                            sk.toFraction?.let { put("toFraction", it) }
                            sk.toSpineTitle?.let { put("toSpineTitle", it) }
                        })
                    }
                })
            })
        }
        return arr
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    /** Result of restoring one matched book's progress/bookmarks/sessions/skip-events. */
    private data class BookRestoreCounts(
        val bookmarksInserted: Int,
        val sessionsInserted: Int,
        val skipEventsInserted: Int,
        // true when the backup had progress for this book but local progress was newer and
        // forceOverwrite was off, so it was intentionally left untouched.
        val progressKeptLocal: Boolean
    )

    /**
     * Reads and applies a backup. Stops any active playback FIRST and awaits its final position
     * write (via [PlayerController.stopAndFlush]) — a merely fire-and-forget save can otherwise
     * land after restore has already read/written progress for the same book, either hiding the
     * restored position behind a fresher `lastPlayedMs` or clobbering it back afterwards. Merges
     * rather than replaces: a book's progress is only overwritten if the backup's `lastPlayedMs`
     * is newer (or [forceOverwrite] is set). Tolerant of unknown/missing JSON fields for
     * forward-compat with older backups after later schema additions. The DB-touching portion
     * runs in one transaction so navigating away or a mid-file JSON error can't leave a half
     * applied restore.
     */
    suspend fun restore(input: InputStream, forceOverwrite: Boolean): RestoreResult {
        // PlayerController wraps a MediaController, which is main-thread-only (see its class doc);
        // restore() itself normally runs on Dispatchers.IO (BackupManager.importFile), so this hop
        // is required — calling it straight from IO throws "MediaController method is called from
        // a wrong thread".
        withContext(Dispatchers.Main) { playerController.stopAndFlush() }
        // Give the service's own onIsPlayingChanged(false)-triggered save (fire-and-forget, over
        // IPC) a moment to land before we start reading/writing progress for the same books.
        delay(STOP_SETTLE_MS)

        val root = JSONObject(input.bufferedReader().readText())

        restoreSettings(root.optJSONArray("settings") ?: JSONArray())

        var bookmarksRestored = 0
        var sessionsRestored = 0
        var skipEventsRestored = 0
        var matched = 0
        var noMatch = 0
        var ambiguous = 0
        var staleSkipped = 0
        var presetsRestored = 0
        var seriesRestored = 0

        // Suppressed: restoreBook/applyPresets/applyAuthors/applySeries each call several
        // individually-flush-hooked repository methods per book/preset/series — without this, a
        // large backup means hundreds of full JSON re-serializations mid-transaction instead of
        // one flush after it commits.
        diskMirror.suppressed {
            db.withTransaction {
                val libDoc = parseLibraryEntries(root)
                presetsRestored = libDoc?.let { restoreOps.applyPresets(it.presets) } ?: 0
                libDoc?.let { restoreOps.applyAuthors(it.authors) }

                val libraryFolder = settings.libraryFolder.first()
                val currentBooks = repository.getAllBooksIncludingIgnoredOnce().map { b ->
                    BookCandidate(b.id, BookIdentity(b.folderPath, relPath(b.folderPath, libraryFolder), b.title, b.author))
                }

                val booksArr = root.optJSONArray("books") ?: JSONArray()
                for (i in 0 until booksArr.length()) {
                    val entry = booksArr.getJSONObject(i)
                    val identity = BookIdentity(
                        folderPath = entry.optString("folderPath"),
                        relPath = entry.optString("relPath"),
                        title = entry.optString("title"),
                        author = entry.optString("author")
                    )
                    when (val result = BackupMatcher.matchBooks(listOf(identity), currentBooks).single()) {
                        is MatchResult.Matched -> {
                            matched++
                            val counts = restoreBook(result.bookId, entry, forceOverwrite)
                            bookmarksRestored += counts.bookmarksInserted
                            sessionsRestored += counts.sessionsInserted
                            skipEventsRestored += counts.skipEventsInserted
                            if (counts.progressKeptLocal) staleSkipped++
                        }
                        MatchResult.NoMatch -> noMatch++
                        MatchResult.Ambiguous -> ambiguous++
                    }
                }

                seriesRestored = libDoc?.let { restoreOps.applySeries(it.series, currentBooks) } ?: 0
            }
        }
        diskMirror.flushDirty()
        diskMirror.flushLibrary()

        AppLog.i(LogCat.BACKUP, "restore complete: matched=$matched noMatch=$noMatch ambiguous=$ambiguous stale=$staleSkipped bookmarks=$bookmarksRestored sessions=$sessionsRestored skips=$skipEventsRestored presets=$presetsRestored series=$seriesRestored")

        return RestoreResult(
            booksMatched = matched,
            booksSkippedNoMatch = noMatch,
            booksSkippedAmbiguous = ambiguous,
            booksSkippedStale = staleSkipped,
            bookmarksRestored = bookmarksRestored,
            sessionsRestored = sessionsRestored,
            skipEventsRestored = skipEventsRestored,
            presetsRestored = presetsRestored,
            seriesRestored = seriesRestored
        )
    }

    private suspend fun restoreSettings(arr: JSONArray) {
        val coreByName = com.betteraudio.data.diskstore.SettingsSpecs.coreSpecs().associateBy { it.name }
        val pathByName = com.betteraudio.data.diskstore.SettingsSpecs.pathSpecs().associateBy { it.name }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val value = o.optString("value")
            try {
                when {
                    name == "gemini_api_key" -> {
                        // Not in the whitelist map — opt-in only; present in the file at all means
                        // the exporter chose to include it, so restore always honors that.
                        settings.setGeminiApiKey(value)
                    }
                    pathByName.containsKey(name) -> {
                        // A path from another device/reinstall is meaningless and would just 404
                        // in the UI — only apply it if it still resolves on this device.
                        if (value.isNotBlank() && File(value).exists()) pathByName.getValue(name).set(settings, value)
                    }
                    else -> coreByName[name]?.set(settings, value) // unknown key (older/newer schema) — skip, don't throw
                }
            } catch (e: Exception) {
                AppLog.e(LogCat.BACKUP, "failed to restore setting '$name'", e)
            }
        }
    }

    /** Wraps the backup's separate presets/authors/series arrays into one library.json-shaped
     *  document so RestoreOps' single implementation of each can be reused verbatim — the field
     *  names line up exactly with what gatherPresets/gatherAuthors/gatherSeries already write. */
    private fun parseLibraryEntries(root: JSONObject): com.betteraudio.data.diskstore.LibraryDocument? =
        com.betteraudio.data.diskstore.LibraryDataCodec.decodeOrNull(
            JSONObject().apply {
                put("libraryRoot", "")
                put("presets", root.optJSONArray("presets") ?: JSONArray())
                put("authors", root.optJSONArray("authors") ?: JSONArray())
                put("series", root.optJSONArray("series") ?: JSONArray())
            }.toString()
        )

    /** Parses one backup book entry into a [com.betteraudio.data.diskstore.BookDocument] — the
     *  field names match book.json's shape exactly (gatherBooks was in fact the template book.json
     *  was lifted from), so the existing codec parses a backup entry as-is; fields book.json has
     *  that a backup doesn't (cover/series/ebook/files/…) simply come back null/empty and are
     *  never touched by RestoreOps.applyBookDocument. */
    private fun parseBookEntry(entry: JSONObject): com.betteraudio.data.diskstore.BookDocument? =
        com.betteraudio.data.diskstore.BookDataCodec.decodeOrNull(entry.toString())

    private suspend fun restoreBook(bookId: Long, entry: JSONObject, forceOverwrite: Boolean): BookRestoreCounts {
        val doc = parseBookEntry(entry) ?: return BookRestoreCounts(0, 0, 0, false)
        val mode = if (forceOverwrite) com.betteraudio.data.diskstore.ApplyMode.FORCE else com.betteraudio.data.diskstore.ApplyMode.MERGE
        val counts = restoreOps.applyBookDocument(bookId, doc, mode)
        return BookRestoreCounts(counts.bookmarksInserted, counts.sessionsInserted, counts.skipEventsInserted, counts.progressKeptLocal)
    }
}
