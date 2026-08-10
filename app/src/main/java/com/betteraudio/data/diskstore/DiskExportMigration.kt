package com.betteraudio.data.diskstore

import android.content.Context
import android.os.Build
import android.os.Environment
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlayerController
import com.betteraudio.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ExportState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val failed: Int = 0
)

/**
 * One-time (per [TARGET_VERSION]) pass that writes the disk mirror for every book already in the
 * DB, plus the library-root `.voyage/` files, so an install that predates the storage redesign
 * becomes reinstall-proof on its first launch after updating — without this, only books touched
 * AFTER the update would ever get a `data/` folder.
 *
 * Also copies cover bytes that today live outside a book's own folder (gallery-picked covers in
 * `filesDir/covers/`, series/author covers likewise) into their new disk-mirror home and repoints
 * the DB — recording only the path would leave the exact bug this feature exists to fix (a cover
 * lost on uninstall) unfixed for every book that already has one.
 *
 * Launched from [com.betteraudio.VoyageApp.onCreate] on `@ApplicationScope`, same shape as
 * `cleanupPhantomSeries` and for the same reason: it needs the DataStore library folder, which a
 * Room migration cannot read.
 */
@Singleton
class DiskExportMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val bookDataStore: BookDataStore,
    private val libraryDataStore: LibraryDataStore,
    private val settingsFileStore: SettingsFileStore,
    private val diskMirror: DiskMirror,
    private val playerController: PlayerController
) {
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    /** Guarded, idempotent — safe to call on every launch; it's a no-op once the version flag is
     *  current. Each guard RETURNS WITHOUT setting the flag, so a not-yet-ready precondition
     *  (no folder chosen, permission not granted, volume unmounted) just retries next launch. */
    suspend fun runIfNeeded() {
        if (settings.diskExportVersion.first() >= TARGET_VERSION) return
        val libraryFolder = settings.libraryFolder.first()
        if (libraryFolder.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return
        if (!File(libraryFolder).isDirectory) return
        // Don't jank an active listening session — retry on a later launch instead of trying to
        // interleave pauses between every book write.
        if (playerController.playbackState.value.isPlaying) return
        runExport(libraryFolder)
    }

    /** Manual re-run (Settings → Library "Re-export library data") — ignores the version guard,
     *  the recovery path for "the SD card was unmounted for a week" or any other missed writes. */
    suspend fun runNow() {
        val libraryFolder = settings.libraryFolder.first()
        if (libraryFolder.isBlank() || !File(libraryFolder).isDirectory) return
        runExport(libraryFolder)
    }

    /**
     * "Forget disk data for this library" — the escape hatch a disk-first model needs now that
     * [AudiobookRepository.resetLibrary] no longer wipes a book on rescan (a rescan restores it
     * from disk, which is the whole point). Deletes every book's own doc/cover/mapping file and
     * `.voyage/library.json`, WITHOUT touching the DB or the audio itself — the DB stays the
     * source of truth for the very next launch's export pass, which regenerates a clean disk
     * mirror from whatever's actually in Room right now. Does not delete `.voyage/settings.json`
     * or `covers/` — a corrupt book.json is the actual complaint this exists for, not settings.
     */
    suspend fun forgetAllDiskData() {
        val libraryFolder = settings.libraryFolder.first()
        if (libraryFolder.isBlank()) return
        repository.getAllBooksIncludingIgnoredOnce().forEach { book ->
            runCatching { bookDataStore.delete(book) }
        }
        VoyageLayout.libraryFile(libraryFolder)?.let { runCatching { it.delete() } }
        settings.setDiskExportVersion(0)
        settings.setLibraryJsonAppliedAt(0L)
        AppLog.i("DiskExport", "forgot all disk data for $libraryFolder")
    }

    private suspend fun runExport(libraryFolder: String) {
        val root = File(libraryFolder)
        VoyageLayout.rootDir(libraryFolder)?.let { it.mkdirs(); ensureNoMedia(it) }

        runCatching { writeSettingsSnapshot(libraryFolder) }
            .onFailure { AppLog.e("DiskExport", "settings.json write failed", it) }
        runCatching { migrateSeriesCovers(root) }
            .onFailure { AppLog.e("DiskExport", "series cover migration failed", it) }
        runCatching { migrateAuthorCovers(root) }
            .onFailure { AppLog.e("DiskExport", "author cover migration failed", it) }
        diskMirror.flushLibrary()

        val books = repository.getAllBooksIncludingIgnoredOnce()
        _state.value = ExportState(running = true, done = 0, total = books.size, failed = 0)
        var failed = 0
        books.forEachIndexed { index, book ->
            currentCoroutineContext().ensureActive()
            runCatching { migrateBookCover(book, root) }
                .onFailure { AppLog.e("DiskExport", "cover migration failed for book=${book.id}", it) }
            val ok = runCatching { bookDataStore.write(book.id) }.getOrElse { false }
            if (!ok) failed++
            _state.value = ExportState(running = true, done = index + 1, total = books.size, failed = failed)
        }
        _state.value = ExportState(running = false, done = books.size, total = books.size, failed = failed)

        if (failed == 0) {
            settings.setDiskExportVersion(TARGET_VERSION)
            AppLog.i("DiskExport", "export complete: ${books.size} book(s)")
        } else {
            AppLog.w("DiskExport", "export finished with $failed failure(s) of ${books.size} — version flag NOT set, will retry")
        }
    }

    private suspend fun writeSettingsSnapshot(libraryFolder: String) {
        val values = (SettingsSpecs.coreSpecs() + SettingsSpecs.pathSpecs()).mapNotNull { spec ->
            spec.get(settings)?.let { SettingsDocument.SettingValue(spec.name, spec.type, it) }
        } + if (settings.backupIncludeApiKey.first()) {
            SettingsSpecs.apiKeySpec().get(settings)
                ?.let { listOf(SettingsDocument.SettingValue("gemini_api_key", "string", it)) }
                ?: emptyList()
        } else emptyList()
        settingsFileStore.write(libraryFolder, values)
    }

    /** A cover living outside the book's own folder (gallery pick → filesDir/covers/<id>.jpg,
     *  or the synthetic-folderPath online-search fallback) is copied into data/ and the DB
     *  repointed; a cover already next to the audio (embedded extraction, online search into the
     *  book folder, a legacy cover.png) is left exactly where it is. */
    private suspend fun migrateBookCover(book: Book, root: File) {
        val path = book.coverArtPath ?: return
        val src = File(path)
        if (!src.isFile) return
        val dir = BookDataPaths.containingDir(book.folderPath)
        if (src.absolutePath.startsWith(dir.absolutePath)) return
        val ext = src.extension.ifBlank { "jpg" }
        val newPath = bookDataStore.writeCoverFile(book.folderPath, "user", ext, src) ?: return
        repository.updateCoverArt(book.id, newPath)
    }

    private suspend fun migrateSeriesCovers(root: File) {
        val voyageRoot = VoyageLayout.rootDir(root.absolutePath) ?: return
        seriesRepository.getAllSeriesOnce().forEach { series ->
            val path = series.coverArtPath ?: return@forEach
            val src = File(path)
            if (!src.isFile || src.absolutePath.startsWith(voyageRoot.absolutePath)) return@forEach
            val ext = src.extension.ifBlank { "jpg" }
            val newPath = libraryDataStore.writeSeriesCoverFile(series.name, ext, src) ?: return@forEach
            seriesRepository.setSeriesCover(series.id, newPath)
        }
    }

    private suspend fun migrateAuthorCovers(root: File) {
        val voyageRoot = VoyageLayout.rootDir(root.absolutePath) ?: return
        repository.getAllAuthorMetaOnce().forEach { author ->
            val path = author.coverArtPath ?: return@forEach
            val src = File(path)
            if (!src.isFile || src.absolutePath.startsWith(voyageRoot.absolutePath)) return@forEach
            val ext = src.extension.ifBlank { "jpg" }
            val newPath = libraryDataStore.writeAuthorCoverFile(author.name, ext, src) ?: return@forEach
            repository.setAuthorCover(author.name, newPath)
        }
    }

    companion object {
        /** Bump to re-run the export after a disk-schema addition. */
        const val TARGET_VERSION = 1
    }
}
