package com.betteraudio.data.backup

import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.AuthorMeta
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlayerController
import com.betteraudio.util.AppLog
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exports/imports a portable snapshot of everything the DB + DataStore know that isn't re-derived
 * by a rescan: settings, audio presets, series, authors, and per-book progress/bookmarks/history.
 * Modeled on [com.betteraudio.data.sync.MappingFileIO] (org.json, versioned root object).
 *
 * Book identity across a reinstall/rescan is NOT the DB row id (ids are not stable — see
 * [BackupMatcher]) — it's `folderPath`/`relPath`/`{title,author}`, resolved via [BackupMatcher] at
 * restore time. Files inside a book are identified by basename, not `fileId`, for the same reason.
 */
@Singleton
class BackupManager @Inject constructor(
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore,
    private val playerController: PlayerController
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }

    data class RestoreResult(
        val booksMatched: Int,
        val booksSkippedNoMatch: Int,
        val booksSkippedAmbiguous: Int,
        val bookmarksRestored: Int,
        val sessionsRestored: Int,
        val skipEventsRestored: Int,
        val presetsRestored: Int,
        val seriesRestored: Int,
    )

    // ── Settings whitelist ──────────────────────────────────────────────────────
    // Explicit (name, type, getter, setter) per key — deliberately not reflection/generic, so a
    // type mismatch is a compile error, not a runtime crash on restore. Device-local keys
    // (LAST_OPEN_BOOK_ID, LAST_PLAYED_BOOK_ID, APP_STOPPED_AT, WIDGET_APP_COLOR,
    // SKIPPED_UPDATE_VERSION) and LIBRARY_FOLDER (device-specific path, never overwritten by a
    // restore) are intentionally excluded.
    private data class SettingSpec(
        val name: String,
        val type: String, // "string" | "int" | "long" | "float" | "boolean"
        val get: suspend () -> String?, // null = omit from the export (unset/blank)
        val set: suspend (String) -> Unit
    )

    private fun coreSettingSpecs(): List<SettingSpec> = listOf(
        SettingSpec("skip_forward_ms", "long", { settings.skipForwardMs.first().toString() }, { settings.setSkipForwardMs(it.toLong()) }),
        SettingSpec("skip_back_ms", "long", { settings.skipBackMs.first().toString() }, { settings.setSkipBackMs(it.toLong()) }),
        SettingSpec("default_speed", "float", { settings.defaultSpeed.first().toString() }, { settings.setDefaultSpeed(it.toFloat()) }),
        SettingSpec("sort_option", "string", { settings.sortOption.first() }, { name -> settings.setSort(name, settings.sortDirection.first()) }),
        SettingSpec("sort_direction", "string", { settings.sortDirection.first() }, { dir -> settings.setSort(settings.sortOption.first(), dir) }),
        SettingSpec("auto_rewind_seconds", "int", { settings.autoRewindSeconds.first().toString() }, { settings.setAutoRewindSeconds(it.toInt()) }),
        SettingSpec("auto_rewind_threshold_minutes", "int", { settings.autoRewindThresholdMinutes.first().toString() }, { settings.setAutoRewindThresholdMinutes(it.toInt()) }),
        SettingSpec("skip_silence_min_ms", "long", { settings.skipSilenceMinMs.first().toString() }, { settings.setSkipSilenceMinMs(it.toLong()) }),
        SettingSpec("skip_silence_threshold", "int", { settings.skipSilenceThreshold.first().toString() }, { settings.setSkipSilenceThreshold(it.toInt()) }),
        SettingSpec("skip_silence_padding_ms", "long", { settings.skipSilencePaddingMs.first().toString() }, { settings.setSkipSilencePaddingMs(it.toLong()) }),
        SettingSpec("import_structure", "string", { settings.importStructure.first().takeIf { it.isNotBlank() } }, { settings.setImportStructure(it) }),
        SettingSpec("home_view_mode", "string", { settings.homeViewMode.first() }, { settings.setHomeViewMode(it) }),
        SettingSpec("player_show_series_cover", "boolean", { settings.playerShowSeriesCover.first().toString() }, { settings.setPlayerShowSeriesCover(it.toBoolean()) }),
        SettingSpec("app_theme", "string", { settings.appTheme.first().takeIf { it.isNotBlank() } }, { settings.setAppTheme(it) }),
        SettingSpec("theme_color_source", "string", { settings.themeColorSource.first() }, { settings.setThemeColorSource(it) }),
        SettingSpec("custom_theme_color", "string", { settings.customThemeColor.first() }, { settings.setCustomThemeColor(it) }),
        SettingSpec("dark_mode", "string", { settings.darkMode.first() }, { settings.setDarkMode(it) }),
        SettingSpec("pure_black", "boolean", { settings.pureBlack.first().toString() }, { settings.setPureBlack(it.toBoolean()) }),
        SettingSpec("reader_font_size", "int", { settings.readerFontSize.first().toString() }, { settings.setReaderFontSize(it.toInt()) }),
        SettingSpec("home_section", "string", { settings.homeSection.first() }, { settings.setHomeSection(it) }),
        SettingSpec("widget_hide_when_idle", "boolean", { settings.widgetHideWhenIdle.first().toString() }, { settings.setWidgetHideWhenIdle(it.toBoolean()) }),
    )

    // Path-valued settings: exported always, but restored only if the path still exists on this
    // device (a path from another device/reinstall is meaningless and would just 404 in the UI).
    private fun pathSettingSpecs(): List<SettingSpec> = listOf(
        SettingSpec("widget_default_cover_path", "string", { settings.widgetDefaultCoverPath.first().takeIf { it.isNotBlank() } }, { settings.setWidgetDefaultCoverPath(it) }),
        SettingSpec("ebook_folder", "string", { settings.ebookFolder.first().takeIf { it.isNotBlank() } }, { settings.setEbookFolder(it) }),
    )

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
        (coreSettingSpecs() + pathSettingSpecs()).forEach { spec ->
            val value = spec.get() ?: return@forEach
            arr.put(JSONObject().apply { put("name", spec.name); put("type", spec.type); put("value", value) })
        }
        if (includeApiKey) {
            val key = settings.geminiApiKey.first()
            if (key.isNotBlank()) {
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
                        progress.textSpineIndex?.let { put("textSpineIndex", it) }
                        progress.textFraction?.let { put("textFraction", it) }
                        put("textOverallFraction", progress.textOverallFraction)
                        put("lastMode", progress.lastMode)
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

    /**
     * Reads and applies a backup. Stops any active playback FIRST so the service's periodic
     * position-save can't race the restore and clobber it on its next tick. Merges rather than
     * replaces: a book's progress is only overwritten if the backup's `lastPlayedMs` is newer (or
     * [forceOverwrite] is set). Tolerant of unknown/missing JSON fields for forward-compat with
     * older backups after later schema additions.
     */
    suspend fun restore(input: InputStream, forceOverwrite: Boolean): RestoreResult {
        playerController.stop()

        val root = JSONObject(input.bufferedReader().readText())

        restoreSettings(root.optJSONArray("settings") ?: JSONArray())
        val presetsRestored = restorePresets(root.optJSONArray("presets") ?: JSONArray())
        restoreAuthors(root.optJSONArray("authors") ?: JSONArray())

        val libraryFolder = settings.libraryFolder.first()
        val currentBooks = repository.getAllBooksIncludingIgnoredOnce().map { b ->
            BookCandidate(b.id, BookIdentity(b.folderPath, relPath(b.folderPath, libraryFolder), b.title, b.author))
        }

        var bookmarksRestored = 0
        var sessionsRestored = 0
        var skipEventsRestored = 0
        var matched = 0
        var noMatch = 0
        var ambiguous = 0

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
                    bookmarksRestored += counts.first
                    sessionsRestored += counts.second
                    skipEventsRestored += counts.third
                }
                MatchResult.NoMatch -> noMatch++
                MatchResult.Ambiguous -> ambiguous++
            }
        }

        val seriesRestored = restoreSeries(root.optJSONArray("series") ?: JSONArray(), currentBooks)

        AppLog.i("Backup", "restore complete: matched=$matched noMatch=$noMatch ambiguous=$ambiguous bookmarks=$bookmarksRestored sessions=$sessionsRestored skips=$skipEventsRestored presets=$presetsRestored series=$seriesRestored")

        return RestoreResult(
            booksMatched = matched,
            booksSkippedNoMatch = noMatch,
            booksSkippedAmbiguous = ambiguous,
            bookmarksRestored = bookmarksRestored,
            sessionsRestored = sessionsRestored,
            skipEventsRestored = skipEventsRestored,
            presetsRestored = presetsRestored,
            seriesRestored = seriesRestored
        )
    }

    private suspend fun restoreSettings(arr: JSONArray) {
        val specsByName = (coreSettingSpecs() + pathSettingSpecs()).associateBy { it.name }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val value = o.optString("value")
            try {
                if (name == "gemini_api_key") {
                    // Not in the whitelist map — opt-in only; present in the file at all means the
                    // exporter chose to include it, so restore always honors that.
                    settings.setGeminiApiKey(value)
                } else {
                    specsByName[name]?.set(value) // unknown key (older/newer schema) — skip, don't throw
                }
            } catch (e: Exception) {
                AppLog.e("Backup", "failed to restore setting '$name'", e)
            }
        }
    }

    private suspend fun restorePresets(arr: JSONArray): Int {
        if (arr.length() == 0) return 0
        val existing = repository.getAllAudioPresets().first()
        var defaultName: String? = null
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val preset = AudioPreset(
                id = existing.find { it.name == name }?.id ?: 0L,
                name = name,
                type = o.optString("type", AudioPreset.TYPE_BUNDLE),
                speedMult = o.optDouble("speedMult", 1.0).toFloat(),
                boostDb = o.optInt("boostDb", 0),
                eqBandsJson = o.optString("eqBandsJson").takeIf { it.isNotBlank() },
                isDefault = false // set explicitly below, once, to keep exactly one default
            )
            if (preset.id != 0L) repository.updateAudioPreset(preset) else repository.insertAudioPreset(preset)
            if (o.optBoolean("isDefault", false)) defaultName = name
            count++
        }
        defaultName?.let { name ->
            repository.getAllAudioPresets().first().find { it.name == name }?.let { repository.setDefaultAudioPreset(it.id) }
        }
        return count
    }

    private suspend fun restoreAuthors(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val name = arr.getJSONObject(i).optString("name").takeIf { it.isNotBlank() } ?: continue
            if (repository.getAllAuthorMetaOnce().none { it.name == name }) {
                repository.upsertAuthorMeta(AuthorMeta(name = name))
            }
        }
    }

    /** Restores one matched book's progress/bookmarks/sessions/skip-events. Returns
     *  (bookmarksInserted, sessionsInserted, skipEventsInserted). */
    private suspend fun restoreBook(bookId: Long, entry: JSONObject, forceOverwrite: Boolean): Triple<Int, Int, Int> {
        entry.optString("titleOverride").takeIf { it.isNotBlank() }?.let { t ->
            repository.updateBookMetadata(bookId, t, entry.optString("authorOverride").takeIf { it.isNotBlank() })
        }
        entry.optString("narrator").takeIf { it.isNotBlank() }?.let { repository.updateBookNarrator(bookId, it) }
        if (entry.has("skipSilenceEnabled")) {
            repository.setSkipSilenceEnabled(bookId, entry.optBoolean("skipSilenceEnabled"))
        }

        val fileCandidates = repository.getAudioFilesOnce(bookId).map { FileCandidate(it.id, it.fileName, it.durationMs) }

        entry.optJSONObject("progress")?.let { p ->
            val backupLastPlayed = p.optLong("lastPlayedMs", 0L)
            val existing = repository.getProgressForBookOnce(bookId)
            val shouldWrite = forceOverwrite || existing == null || backupLastPlayed > existing.lastPlayedMs
            if (shouldWrite) {
                val currentFileId = p.optJSONObject("currentFile")?.let { cf ->
                    BackupMatcher.matchFile(cf.optString("fileName"), cf.optLong("durationMs"), fileCandidates)
                }
                repository.saveProgress(
                    PlaybackProgress(
                        bookId = bookId,
                        currentFileId = currentFileId,
                        positionMs = p.optLong("positionMs", 0L),
                        lastPlayedMs = backupLastPlayed.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        playbackSpeed = p.optDouble("playbackSpeed", 1.0).toFloat(),
                        boostDb = p.optInt("boostDb", 0),
                        eqBandsJson = p.optString("eqBandsJson").takeIf { it.isNotBlank() },
                        isCompleted = p.optBoolean("isCompleted", false),
                        completedDateMs = if (p.has("completedDateMs")) p.optLong("completedDateMs") else null,
                        lastPausedAt = p.optLong("lastPausedAt", 0L),
                        textSpineIndex = if (p.has("textSpineIndex")) p.optInt("textSpineIndex") else null,
                        textFraction = if (p.has("textFraction")) p.optDouble("textFraction").toFloat() else null,
                        textOverallFraction = p.optDouble("textOverallFraction", 0.0).toFloat(),
                        lastMode = p.optString("lastMode", "AUDIO")
                    )
                )
            }
        }

        var bookmarksInserted = 0
        val bmArr = entry.optJSONArray("bookmarks") ?: JSONArray()
        if (bmArr.length() > 0) {
            val existingBookmarks = repository.getBookmarksForBook(bookId).first()
            val existingKeys = existingBookmarks.map { it.createdAt to it.absolutePositionMs }.toSet()
            for (i in 0 until bmArr.length()) {
                val o = bmArr.getJSONObject(i)
                val createdAt = o.optLong("createdAt")
                val absPos = o.optLong("absolutePositionMs")
                if ((createdAt to absPos) in existingKeys) continue // already restored / never left
                val fileId = BackupMatcher.matchFile(o.optString("fileName"), 0L, fileCandidates) ?: continue
                repository.addBookmark(
                    Bookmark(
                        bookId = bookId, fileId = fileId,
                        positionInFileMs = o.optLong("positionInFileMs"),
                        absolutePositionMs = absPos,
                        comment = o.optString("comment"),
                        createdAt = createdAt
                    )
                )
                bookmarksInserted++
            }
        }

        var sessionsInserted = 0
        val sessArr = entry.optJSONArray("sessions") ?: JSONArray()
        if (sessArr.length() > 0) {
            val existingStarts = repository.getSessionsForBook(bookId).first().map { it.startMs }.toSet()
            for (i in 0 until sessArr.length()) {
                val o = sessArr.getJSONObject(i)
                val startMs = o.optLong("startMs")
                if (startMs in existingStarts) continue
                repository.insertListeningSession(
                    ListeningSession(
                        bookId = bookId, startMs = startMs, endMs = o.optLong("endMs"),
                        startChapterIndex = o.optInt("startChapterIndex", -1),
                        startChapterName = o.optString("startChapterName"),
                        endChapterIndex = o.optInt("endChapterIndex", -1),
                        endChapterName = o.optString("endChapterName"),
                        startPositionInChapterMs = o.optLong("startPositionInChapterMs"),
                        endPositionInChapterMs = o.optLong("endPositionInChapterMs"),
                        endBookPositionMs = o.optLong("endBookPositionMs"),
                        listenedMs = o.optLong("listenedMs")
                    )
                )
                sessionsInserted++
            }
        }

        var skipEventsInserted = 0
        val skipArr = entry.optJSONArray("skipEvents") ?: JSONArray()
        if (skipArr.length() > 0) {
            val existingAt = repository.getSkipsForBook(bookId).first().map { it.atMs }.toSet()
            for (i in 0 until skipArr.length()) {
                val o = skipArr.getJSONObject(i)
                val atMs = o.optLong("atMs")
                if (atMs in existingAt) continue
                repository.insertSkipEvent(
                    SkipEvent(
                        bookId = bookId, atMs = atMs, kind = o.optString("kind", "AUDIO"),
                        fromPositionMs = o.optLong("fromPositionMs"),
                        toPositionMs = o.optLong("toPositionMs"),
                        chapterIndex = o.optInt("chapterIndex", -1),
                        chapterName = o.optString("chapterName"),
                        fromSpineIndex = if (o.has("fromSpineIndex")) o.optInt("fromSpineIndex") else null,
                        fromFraction = if (o.has("fromFraction")) o.optDouble("fromFraction").toFloat() else null,
                        toSpineIndex = if (o.has("toSpineIndex")) o.optInt("toSpineIndex") else null,
                        toFraction = if (o.has("toFraction")) o.optDouble("toFraction").toFloat() else null,
                        toSpineTitle = o.optString("toSpineTitle").takeIf { it.isNotBlank() }
                    )
                )
                skipEventsInserted++
            }
        }

        return Triple(bookmarksInserted, sessionsInserted, skipEventsInserted)
    }

    private suspend fun restoreSeries(arr: JSONArray, currentBooks: List<BookCandidate>): Int {
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val seriesId = seriesRepository.getOrCreateSeriesByName(name, o.optString("author").takeIf { it.isNotBlank() })
            val existing = seriesRepository.getSeriesOnce(seriesId)
            if (existing != null) {
                seriesRepository.updateSeries(
                    existing.copy(
                        narrator = o.optString("narrator").takeIf { it.isNotBlank() } ?: existing.narrator,
                        description = o.optString("description").takeIf { it.isNotBlank() } ?: existing.description,
                        playbackSpeed = if (o.has("playbackSpeed")) o.optDouble("playbackSpeed").toFloat() else existing.playbackSpeed,
                        boostDb = if (o.has("boostDb")) o.optInt("boostDb") else existing.boostDb,
                        eqBandsJson = o.optString("eqBandsJson").takeIf { it.isNotBlank() } ?: existing.eqBandsJson,
                        skipSilenceEnabled = if (o.has("skipSilenceEnabled")) o.optBoolean("skipSilenceEnabled") else existing.skipSilenceEnabled
                    )
                )
            }
            val members = o.optJSONArray("members") ?: JSONArray()
            for (m in 0 until members.length()) {
                val mo = members.getJSONObject(m)
                val identity = BookIdentity(
                    folderPath = mo.optString("folderPath"),
                    relPath = mo.optString("relPath"),
                    title = mo.optString("title"),
                    author = mo.optString("author")
                )
                val result = BackupMatcher.matchBooks(listOf(identity), currentBooks).single()
                if (result is MatchResult.Matched) {
                    val order = if (mo.has("seriesOrder")) mo.optDouble("seriesOrder").toFloat() else null
                    seriesRepository.addBookToSeries(result.bookId, seriesId, order)
                }
            }
            count++
        }
        return count
    }
}
