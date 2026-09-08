package com.betteraudio.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.betteraudio.BuildConfig
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.scanner.AudioFileScanner
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.update.ReleaseInfo
import com.betteraudio.data.update.UpdateChecker
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

sealed class SettingsSection {
    object Root : SettingsSection()
    object Theme : SettingsSection()
    object Library : SettingsSection()
    object Playback : SettingsSection()
    object Presets : SettingsSection()
    object Widget : SettingsSection()
    object AI : SettingsSection()
    object Backup : SettingsSection()
    object Updates : SettingsSection()
    object About : SettingsSection()
    object Diagnostics : SettingsSection()
}

data class UpdateUiState(
    val checking: Boolean = false,
    val available: ReleaseInfo? = null,
    val upToDate: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Int = 0,
    val error: String? = null
)

data class WhatsNewState(
    val loading: Boolean = false,
    val version: String = "",
    val notes: String = "",
    val error: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsStore,
    private val scanner: AudioFileScanner,
    private val ebookScanner: com.betteraudio.data.scanner.EbookScanner,
    private val updateChecker: UpdateChecker,
    private val repository: AudiobookRepository,
    private val restructurer: com.betteraudio.data.files.LibraryRestructurer,
    private val voskModelManager: com.betteraudio.data.transcribe.VoskModelManager,
    private val widgetUpdater: com.betteraudio.widget.WidgetUpdater,
    private val backupManager: com.betteraudio.data.backup.BackupManager,
    private val diskMirror: com.betteraudio.data.diskstore.DiskMirror,
    private val diskExportMigration: com.betteraudio.data.diskstore.DiskExportMigration,
    private val playerController: com.betteraudio.playback.PlayerController
) : ViewModel() {

    // ── App icon (see util/AppIconManager.kt) ──────────────────────────────
    // A one-shot read, not a StateFlow: PackageManager has no change-notification API for this,
    // and it doesn't need one — changeAppIcon ends the process the moment a switch actually
    // happens, so the next value that matters is read fresh on the next launch's first composition.
    fun currentAppIcon(): com.betteraudio.util.AppIconManager.AppIcon =
        com.betteraudio.util.AppIconManager.current(appContext)

    /** Plain snapshot, not collected — only read once, when the confirm dialog opens, to decide
     *  whether to show its "Playback will stop" line. */
    fun isPlaying(): Boolean = playerController.playbackState.value.isPlaying

    /**
     * Saves the current playback position, switches the launcher icon, then deliberately ends
     * this process and relaunches MainActivity fresh — see [AppIconManager.apply]'s KDoc for why
     * the switch itself must fully land (via DONT_KILL_APP) before anything is allowed to kill
     * this process. MainActivity is targeted explicitly rather than through a launcher lookup:
     * it's the one component that's never disabled by this switch, unlike the icon aliases
     * themselves, so this works regardless of which alias state has just landed.
     */
    fun changeAppIcon(icon: com.betteraudio.util.AppIconManager.AppIcon) {
        viewModelScope.launch {
            playerController.pauseAndFlush()
            com.betteraudio.util.AppIconManager.apply(appContext, icon)
            AppLog.i(LogCat.SETTINGS, "changeAppIcon: switched to ${icon.id}, restarting")
            val relaunch = Intent(appContext, com.betteraudio.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            appContext.startActivity(relaunch)
            kotlin.system.exitProcess(0)
        }
    }

    // ── Disk mirror (reinstall-proof library data) ────────────────────────────
    val diskMirrorHealthy: StateFlow<Boolean> =
        diskMirror.healthy.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val diskMirrorLastError: StateFlow<String?> =
        diskMirror.lastError.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val diskExportState: StateFlow<com.betteraudio.data.diskstore.ExportState> =
        diskExportMigration.state.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), com.betteraudio.data.diskstore.ExportState()
        )

    /** Manual "Re-export library data" — the recovery path for a volume that was unmounted/
     *  unwritable for a while and missed some flushes; ignores the once-per-version guard. */
    fun reExportDiskData() {
        viewModelScope.launch {
            try { diskExportMigration.runNow() } catch (e: Exception) {
                AppLog.e(LogCat.SETTINGS, "manual disk re-export failed", e)
                _operationError.value = "Couldn't re-export library data: ${e.message ?: "unknown error"}"
            }
        }
    }

    /** "Forget disk data for this library" — deletes every book's on-disk doc/cover/mapping file
     *  and .voyage/library.json (never the audio itself), then re-exports a clean mirror from
     *  whatever's actually in Room right now. The escape hatch for a corrupt/stale doc, now that a
     *  rescan restores from disk instead of wiping on reset. */
    fun forgetDiskData() {
        viewModelScope.launch {
            try {
                diskExportMigration.forgetAllDiskData()
                diskExportMigration.runNow()
            } catch (e: Exception) {
                AppLog.e(LogCat.SETTINGS, "forget disk data failed", e)
                _operationError.value = "Couldn't forget library data: ${e.message ?: "unknown error"}"
            }
        }
    }

    // ── Listen↔read sync speech model ─────────────────────────────────────────
    val voskModelState: StateFlow<com.betteraudio.data.transcribe.ModelState> =
        voskModelManager.state.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.betteraudio.data.transcribe.ModelState.NotDownloaded
        )
    fun downloadVoskModel() = viewModelScope.launch { voskModelManager.download() }
    fun deleteVoskModel() = voskModelManager.delete()

    // ── File restructure ─────────────────────────────────────────────────────
    data class RestructureUi(
        val planCount: Int? = null,      // null = not yet computed
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val result: com.betteraudio.data.files.LibraryRestructurer.Result? = null
    )
    private val _restructure = MutableStateFlow(RestructureUi())
    val restructure: StateFlow<RestructureUi> = _restructure.asStateFlow()

    /** Set the target structure (also becomes the app's structure) then compute the dry-run plan. */
    fun chooseRestructureStructure(structure: com.betteraudio.data.scanner.ImportStructure) {
        viewModelScope.launch {
            settings.setImportStructure(structure.name)
            _restructure.value = RestructureUi(planCount = restructurer.plan().size)
        }
    }

    fun runRestructure() {
        if (_restructure.value.running) return
        viewModelScope.launch {
            _restructure.update { it.copy(running = true, done = 0, total = 0, result = null) }
            val result = restructurer.run { done, total ->
                _restructure.update { it.copy(done = done, total = total) }
            }
            _restructure.update { it.copy(running = false, result = result) }
        }
    }

    fun clearRestructure() { _restructure.value = RestructureUi() }

    val libraryFolder: StateFlow<String> =
        settings.libraryFolder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val ebookFolder: StateFlow<String> =
        settings.ebookFolder.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Persist the standalone-ebook root and immediately scan it. */
    fun setEbookFolder(path: String) = viewModelScope.launch {
        settings.setEbookFolder(path)
        ebookScanner.scanEbookDirectory(path)
    }

    val skipForwardMs: StateFlow<Long> =
        settings.skipForwardMs.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_SKIP_FORWARD_MS
        )

    val skipBackMs: StateFlow<Long> =
        settings.skipBackMs.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_SKIP_BACK_MS
        )


    val bookCount: StateFlow<Int> =
        repository.getAllBooks().map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val ignoredBooks: StateFlow<List<com.betteraudio.data.db.entities.Book>> =
        repository.getAllIgnoredBooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restoreBook(bookId: Long) {
        viewModelScope.launch { repository.setBookIgnored(bookId, false) }
    }

    val importStructure: StateFlow<com.betteraudio.data.scanner.ImportStructure> =
        settings.importStructure
            .map { com.betteraudio.data.scanner.ImportStructure.fromName(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.data.scanner.ImportStructure.AUTO)

    /** Persist a new import structure; optionally kick off a rescan so it takes effect now. */
    fun setImportStructure(structure: com.betteraudio.data.scanner.ImportStructure, rescan: Boolean) {
        viewModelScope.launch {
            settings.setImportStructure(structure.name)
            if (rescan) rescan()
        }
    }

    private val _rescanRunning = MutableStateFlow(false)
    val rescanRunning: StateFlow<Boolean> = _rescanRunning.asStateFlow()

    private val _coverRefreshRunning = MutableStateFlow(false)
    val coverRefreshRunning: StateFlow<Boolean> = _coverRefreshRunning.asStateFlow()

    /** (done, total) books baked so far in the current sweep, or null when idle. */
    private val _coverRefreshProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val coverRefreshProgress: StateFlow<Pair<Int, Int>?> = _coverRefreshProgress.asStateFlow()

    private var coverRefreshJob: Job? = null

    private val _resetRunning = MutableStateFlow(false)
    val resetRunning: StateFlow<Boolean> = _resetRunning.asStateFlow()

    // User-visible error for the fire-and-forget operations below (reset/cover refresh/rescan) —
    // otherwise a failure was previously invisible: no UI change, nothing in Logcat, nothing in
    // the app's own log file.
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError.asStateFlow()
    fun dismissOperationError() { _operationError.value = null }

    /** Clear the whole library from the DB (audio files on disk are kept). */
    fun resetLibrary() {
        if (_resetRunning.value) return
        viewModelScope.launch {
            _resetRunning.value = true
            try { repository.resetLibrary() } catch (e: Exception) {
                AppLog.e(LogCat.SETTINGS, "resetLibrary failed", e)
                _operationError.value = "Couldn't reset the library: ${e.message ?: "unknown error"}"
            }
            _resetRunning.value = false
        }
    }

    fun refreshAllCoverEffects() {
        if (_coverRefreshRunning.value) return
        coverRefreshJob = viewModelScope.launch {
            _coverRefreshRunning.value = true
            try {
                repository.regenerateAllCoverFx { done, total -> _coverRefreshProgress.value = done to total }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(LogCat.SETTINGS, "regenerateAllCoverFx failed", e)
                _operationError.value = "Couldn't refresh cover effects: ${e.message ?: "unknown error"}"
            } finally {
                _coverRefreshRunning.value = false
                _coverRefreshProgress.value = null
            }
        }
    }

    /** Stop an in-progress "refresh all cover effects" sweep between books. */
    fun cancelCoverRefresh() {
        coverRefreshJob?.cancel()
    }

    val geminiApiKey: StateFlow<String> =
        settings.geminiApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // ── Audio presets (unified bundles + global default) ──────────────────────
    val presets: StateFlow<List<AudioPreset>> =
        repository.getAllAudioPresets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Insert a new preset (id == 0) or update an existing one. */
    fun savePreset(preset: AudioPreset) = viewModelScope.launch {
        if (preset.id == 0L) repository.insertAudioPreset(preset) else repository.updateAudioPreset(preset)
    }

    fun deletePreset(id: Long) = viewModelScope.launch { repository.deleteAudioPreset(id) }

    /** Make [id] the global default applied to every book unless the book overrides it. */
    fun setDefaultPreset(id: Long) = viewModelScope.launch {
        repository.setDefaultAudioPreset(id)
        settings.setDefaultAudioPresetId(id)
    }

    fun clearDefaultPreset() = viewModelScope.launch {
        repository.clearDefaultAudioPreset()
        settings.setDefaultAudioPresetId(-1L)
    }

    // ── Widget default cover ──────────────────────────────────────────────────
    val widgetDefaultCover: StateFlow<String> =
        settings.widgetDefaultCoverPath
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Copy the picked image to a fixed file the widget process can read, then persist the path. */
    fun setWidgetDefaultCover(uri: android.net.Uri) = viewModelScope.launch {
        val path = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val dest = java.io.File(appContext.filesDir, "widget_default_cover.jpg")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                dest.absolutePath
            } catch (_: Exception) { null }
        }
        if (path != null) {
            settings.setWidgetDefaultCoverPath(path)
            widgetUpdater.requestRender()
        }
    }

    fun clearWidgetDefaultCover() = viewModelScope.launch {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.io.File(appContext.filesDir, "widget_default_cover.jpg").delete() }
        }
        settings.setWidgetDefaultCoverPath("")
        widgetUpdater.requestRender()
    }

    // ── Widget maker ─────────────────────────────────────────────────────────
    val widgetHideWhenIdle: StateFlow<Boolean> =
        settings.widgetHideWhenIdle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setWidgetHideWhenIdle(enabled: Boolean) = viewModelScope.launch {
        settings.setWidgetHideWhenIdle(enabled)
        widgetUpdater.requestRender()
    }

    // ── App theme ────────────────────────────────────────────────────────────
    val appTheme: StateFlow<com.betteraudio.ui.theme.AppTheme> =
        settings.appTheme
            .map { com.betteraudio.ui.theme.AppTheme.from(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU)

    val themeColorSource: StateFlow<com.betteraudio.ui.theme.ThemeColorSource> =
        settings.themeColorSource
            .map { com.betteraudio.ui.theme.ThemeColorSource.from(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.theme.ThemeColorSource.WALLPAPER)

    fun setAppTheme(theme: com.betteraudio.ui.theme.AppTheme) =
        viewModelScope.launch { settings.setAppTheme(theme.name) }

    fun setThemeColorSource(source: com.betteraudio.ui.theme.ThemeColorSource) =
        viewModelScope.launch { settings.setThemeColorSource(source.name) }

    val customThemeColor: StateFlow<String> =
        settings.customThemeColor.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "default")

    /** Pinned accents by cover path — see CoverAccentCodec. Empty = every cover is on Automatic. */
    val coverAccents: StateFlow<Map<String, Int>> =
        settings.coverAccents
            .map { com.betteraudio.data.settings.CoverAccentCodec.decode(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** [argb] null returns [coverPath] to the automatic pick. */
    fun setCoverAccent(coverPath: String, argb: Int?) =
        viewModelScope.launch { settings.setCoverAccent(coverPath, argb) }

    /** The one-colour-for-the-whole-library override, as ARGB, or null when it is off. It
     *  outranks every per-cover pin while set — see MainActivity's coverAccentOverride. */
    val globalAccent: StateFlow<Int?> =
        settings.globalAccent
            .map { hex ->
                hex.takeIf { it.isNotBlank() }
                    ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** [argb] null turns the override off, handing every cover back to its own pin or the
     *  automatic pick. The per-cover map is deliberately left intact underneath. */
    fun setGlobalAccent(argb: Int?) =
        viewModelScope.launch { settings.setGlobalAccent(argb?.let { "#%08X".format(it) }) }

    val miniCoverStyle: StateFlow<com.betteraudio.ui.player.MiniCoverStyle> =
        settings.miniCoverStyle
            .map { com.betteraudio.ui.player.MiniCoverStyle.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.player.MiniCoverStyle.CAP
            )

    fun setMiniCoverStyle(style: com.betteraudio.ui.player.MiniCoverStyle) =
        viewModelScope.launch { settings.setMiniCoverStyle(style.name) }

    // Two preferences, one card. Both looks offer all five designs; they are stored apart only so
    // each keeps the default it shipped with (Immersive EMBER, Material You CLASSIC) rather than
    // one look's pick restyling the other — see SettingsStore.Keys.SCRUBBER_STYLE.
    val scrubberStyle: StateFlow<com.betteraudio.ui.components.ScrubberStyle> =
        settings.scrubberStyle
            .map { com.betteraudio.ui.components.ScrubberStyle.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.components.ScrubberStyle.EMBER
            )

    val scrubberStyleMaterial: StateFlow<com.betteraudio.ui.components.ScrubberStyle> =
        settings.scrubberStyleMaterial
            .map { com.betteraudio.ui.components.ScrubberStyle.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.components.ScrubberStyle.CLASSIC
            )

    fun setScrubberStyle(style: com.betteraudio.ui.components.ScrubberStyle) =
        viewModelScope.launch { settings.setScrubberStyle(style.name) }

    fun setScrubberStyleMaterial(style: com.betteraudio.ui.components.ScrubberStyle) =
        viewModelScope.launch { settings.setScrubberStyleMaterial(style.name) }

    val hapticStrength: StateFlow<com.betteraudio.ui.haptics.HapticStrength> =
        settings.hapticStrength
            .map { com.betteraudio.ui.haptics.HapticStrength.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.haptics.HapticStrength.FULL
            )

    fun setHapticStrength(strength: com.betteraudio.ui.haptics.HapticStrength) =
        viewModelScope.launch { settings.setHapticStrength(strength.name) }

    val darkMode: StateFlow<com.betteraudio.ui.theme.DarkMode> =
        settings.darkMode
            .map { com.betteraudio.ui.theme.DarkMode.from(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.theme.DarkMode.AUTO)

    val pureBlack: StateFlow<Boolean> =
        settings.pureBlack.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val dynamicPills: StateFlow<Boolean> =
        settings.dynamicPills.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Immersive backdrop darkening, 0 (none) → 1 (lower backdrop solid black). */
    val backdropDim: StateFlow<Float> =
        settings.backdropDim.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            com.betteraudio.data.settings.BACKDROP_DIM_DEFAULT
        )

    /** Which landscape layout the Material You player uses — see `LandscapePlayerStyle`. */
    val landscapePlayerStyle: StateFlow<com.betteraudio.ui.material.player.LandscapePlayerStyle> =
        settings.playerLandscapeStyle
            .map { com.betteraudio.ui.material.player.LandscapePlayerStyle.fromName(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.material.player.LandscapePlayerStyle.RAILS
            )

    fun setLandscapePlayerStyle(style: com.betteraudio.ui.material.player.LandscapePlayerStyle) =
        viewModelScope.launch { settings.setPlayerLandscapeStyle(style.name) }

    fun setCustomThemeColor(value: String) =
        viewModelScope.launch { settings.setCustomThemeColor(value) }

    fun setDarkMode(mode: com.betteraudio.ui.theme.DarkMode) =
        viewModelScope.launch { settings.setDarkMode(mode.name) }

    fun setPureBlack(enabled: Boolean) =
        viewModelScope.launch { settings.setPureBlack(enabled) }

    fun setDynamicPills(enabled: Boolean) =
        viewModelScope.launch { settings.setDynamicPills(enabled) }

    fun setBackdropDim(amount: Float) =
        viewModelScope.launch { settings.setBackdropDim(amount) }

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _whatsNew = MutableStateFlow(WhatsNewState())
    val whatsNew: StateFlow<WhatsNewState> = _whatsNew.asStateFlow()

    // Changelog bundled as a raw resource — channel determined by the "b" version suffix.
    val changelog: String by lazy {
        val isBeta = runCatching { currentVersion.endsWith("b") }.getOrDefault(false)
        val resId = if (isBeta) com.betteraudio.R.raw.changelog_beta else com.betteraudio.R.raw.changelog_stable
        runCatching { appContext.resources.openRawResource(resId).bufferedReader().readText() }.getOrDefault("")
    }

    // Read the version from the installed package at runtime, NOT from BuildConfig. BuildConfig
    // constants are inlined at compile time, so a version bump that doesn't force a recompile of
    // this file leaves a stale value baked in (manifest updates, but this string wouldn't). The
    // PackageManager always reflects the actually-installed APK — the same source the system
    // App-info screen uses. BuildConfig stays only as a defensive fallback.
    private val packageInfo get() = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    val currentVersion: String
        get() = runCatching { packageInfo.versionName }.getOrNull() ?: BuildConfig.VERSION_NAME

    val currentVersionCode: Int
        get() = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode.toInt()
            else @Suppress("DEPRECATION") packageInfo.versionCode
        }.getOrNull() ?: BuildConfig.VERSION_CODE

    private val _section = MutableStateFlow<SettingsSection>(SettingsSection.Root)
    val currentSection: StateFlow<SettingsSection> = _section.asStateFlow()
    fun navigateTo(s: SettingsSection) { _section.value = s }

    fun setLibraryFolder(path: String) = viewModelScope.launch { settings.setLibraryFolder(path) }
    fun setSkipForward(ms: Long) = viewModelScope.launch { settings.setSkipForwardMs(ms) }
    fun setSkipBack(ms: Long) = viewModelScope.launch { settings.setSkipBackMs(ms) }
    fun setGeminiApiKey(key: String) = viewModelScope.launch { settings.setGeminiApiKey(key) }

    val autoRewindSeconds: StateFlow<Int> =
        settings.autoRewindSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_AUTO_REWIND_SECONDS)
    val autoRewindThresholdMinutes: StateFlow<Int> =
        settings.autoRewindThresholdMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_AUTO_REWIND_THRESHOLD_MINUTES)
    fun setAutoRewindSeconds(s: Int) = viewModelScope.launch { settings.setAutoRewindSeconds(s) }
    fun setAutoRewindThresholdMinutes(m: Int) = viewModelScope.launch { settings.setAutoRewindThresholdMinutes(m) }

    val skipSilenceMinMs: StateFlow<Long> =
        settings.skipSilenceMinMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SILENCE_MIN_MS)
    val skipSilenceThreshold: StateFlow<Int> =
        settings.skipSilenceThreshold.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SILENCE_THRESHOLD)
    val skipSilencePaddingMs: StateFlow<Long> =
        settings.skipSilencePaddingMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SILENCE_PADDING_MS)
    fun setSkipSilenceMinMs(ms: Long) = viewModelScope.launch { settings.setSkipSilenceMinMs(ms) }
    fun setSkipSilenceThreshold(level: Int) = viewModelScope.launch { settings.setSkipSilenceThreshold(level) }
    fun setSkipSilencePaddingMs(ms: Long) = viewModelScope.launch { settings.setSkipSilencePaddingMs(ms) }

    // ── Sleep timer ──────────────────────────────────────────────────────────
    val sleepFadeSeconds: StateFlow<Int> =
        settings.sleepFadeSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_FADE_SECONDS)
    val sleepShakeEnabled: StateFlow<Boolean> =
        settings.sleepShakeEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SHAKE_ENABLED)
    val sleepShakeResetMinutes: StateFlow<Int> =
        settings.sleepShakeResetMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SHAKE_RESET_MINUTES)
    val sleepScheduleEnabled: StateFlow<Boolean> =
        settings.sleepScheduleEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val sleepScheduleStartMinutes: StateFlow<Int> =
        settings.sleepScheduleStartMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SCHEDULE_START_MINUTES)
    val sleepScheduleEndMinutes: StateFlow<Int> =
        settings.sleepScheduleEndMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SCHEDULE_END_MINUTES)
    val sleepScheduleDefaultMinutes: StateFlow<Int> =
        settings.sleepScheduleDefaultMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SCHEDULE_DEFAULT_MINUTES)

    fun setSleepFadeSeconds(seconds: Int) = viewModelScope.launch { settings.setSleepFadeSeconds(seconds) }
    fun setSleepShakeEnabled(enabled: Boolean) = viewModelScope.launch { settings.setSleepShakeEnabled(enabled) }
    fun setSleepShakeResetMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepShakeResetMinutes(minutes) }
    fun setSleepScheduleEnabled(enabled: Boolean) = viewModelScope.launch { settings.setSleepScheduleEnabled(enabled) }
    fun setSleepScheduleStartMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepScheduleStartMinutes(minutes) }
    fun setSleepScheduleEndMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepScheduleEndMinutes(minutes) }
    fun setSleepScheduleDefaultMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepScheduleDefaultMinutes(minutes) }

    // ── Headset multi-press mapping ──────────────────────────────────────────
    val headsetMultiPressEnabled: StateFlow<Boolean> =
        settings.headsetMultiPressEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val headsetDoublePressAction: StateFlow<String> =
        settings.headsetDoublePressAction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_HEADSET_DOUBLE_PRESS_ACTION)
    val headsetTriplePressAction: StateFlow<String> =
        settings.headsetTriplePressAction.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_HEADSET_TRIPLE_PRESS_ACTION)

    fun setHeadsetMultiPressEnabled(enabled: Boolean) = viewModelScope.launch { settings.setHeadsetMultiPressEnabled(enabled) }
    fun setHeadsetDoublePressAction(action: String) = viewModelScope.launch { settings.setHeadsetDoublePressAction(action) }
    fun setHeadsetTriplePressAction(action: String) = viewModelScope.launch { settings.setHeadsetTriplePressAction(action) }

    // ── Bluetooth/headphone auto-resume ─────────────────────────────────────
    val btAutoResumeEnabled: StateFlow<Boolean> =
        settings.btAutoResumeEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val btAutoResumeWindowMinutes: StateFlow<Int> =
        settings.btAutoResumeWindowMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_BT_AUTO_RESUME_WINDOW_MINUTES)

    fun setBtAutoResumeEnabled(enabled: Boolean) = viewModelScope.launch { settings.setBtAutoResumeEnabled(enabled) }
    fun setBtAutoResumeWindowMinutes(minutes: Int) = viewModelScope.launch { settings.setBtAutoResumeWindowMinutes(minutes) }

    // ── Diagnostics ──────────────────────────────────────────────────────────
    /** "OFF" | "ON" | "VERBOSE". */
    val logLevel: StateFlow<String> =
        settings.logLevel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_LOG_LEVEL)
    fun setLogLevel(level: String) = viewModelScope.launch { settings.setLogLevel(level) }

    val logBudgetMb: StateFlow<Float> =
        settings.logBudgetMb.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_LOG_BUDGET_MB)
    /** Live UI value while the user is dragging/typing — [setLogBudgetMb] persists it (debounced
     *  by the caller) once they settle on one. */
    private val _logBudgetInput = MutableStateFlow<Float?>(null)
    val logBudgetInput: StateFlow<Float?> = _logBudgetInput.asStateFlow()
    fun setLogBudgetInput(mb: Float) { _logBudgetInput.value = mb }
    fun commitLogBudgetMb(mb: Float) {
        _logBudgetInput.value = null
        viewModelScope.launch { settings.setLogBudgetMb(mb) }
    }

    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()
    private val _logStats = MutableStateFlow<com.betteraudio.util.log.LogEngine.LogStats?>(null)
    val logStats: StateFlow<com.betteraudio.util.log.LogEngine.LogStats?> = _logStats.asStateFlow()
    private val _logLoading = MutableStateFlow(false)
    val logLoading: StateFlow<Boolean> = _logLoading.asStateFlow()

    /** Loads/refreshes the Diagnostics viewer's text and size/coverage readout. Both do file I/O
     *  (recentText() can decompress older segments) — always dispatched off the main thread, since
     *  the old code's direct AppLog.recentText() call during composition was a real ANR risk once
     *  the budget can go up to 20MB. */
    fun refreshLog(redactPaths: Boolean = true) {
        _logLoading.value = true
        viewModelScope.launch {
            val (text, stats) = withContext(Dispatchers.IO) {
                AppLog.recentTextForShare(redactPaths = redactPaths) to AppLog.stats()
            }
            _logText.value = text
            _logStats.value = stats
            _logLoading.value = false
        }
    }

    fun clearLog() {
        viewModelScope.launch(Dispatchers.IO) {
            AppLog.clear()
            withContext(Dispatchers.Main) { _logText.value = "" }
        }
    }

    /** Builds the share zip (redacted by default) at [target] off the main thread; caller
     *  (DiagnosticsSection) launches the share Intent once this returns. */
    suspend fun buildLogShareBundle(target: java.io.File, redactPaths: Boolean) =
        withContext(Dispatchers.IO) { AppLog.buildShareBundle(target, redactPaths) }

    fun rescan() {
        val path = libraryFolder.value
        if (path.isBlank() || _rescanRunning.value) return
        viewModelScope.launch {
            _rescanRunning.value = true
            try { scanner.scanDirectory(path) } catch (e: Exception) {
                AppLog.e(LogCat.SETTINGS, "rescan of $path failed", e)
                _operationError.value = "Couldn't scan $path: ${e.message ?: "unknown error"}"
            }
            _rescanRunning.value = false
        }
    }

    fun checkForUpdate() {
        if (_updateState.value.checking) return
        viewModelScope.launch {
            _updateState.update { UpdateUiState(checking = true) }
            _updateState.update {
                when (val result = updateChecker.checkForUpdate()) {
                    is com.betteraudio.data.update.UpdateCheckResult.Available -> UpdateUiState(available = result.info)
                    is com.betteraudio.data.update.UpdateCheckResult.UpToDate -> UpdateUiState(upToDate = true)
                    is com.betteraudio.data.update.UpdateCheckResult.Failed -> UpdateUiState(error = result.reason)
                }
            }
        }
    }

    fun downloadAndInstall() {
        val url = _updateState.value.available?.apkDownloadUrl ?: return
        viewModelScope.launch {
            _updateState.update { it.copy(downloading = true, downloadProgress = 0, error = null) }
            val file = updateChecker.downloadApk(url) { progress ->
                _updateState.update { it.copy(downloadProgress = progress) }
            }
            if (file == null) {
                _updateState.update { it.copy(downloading = false, error = "Download failed. Try again.") }
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                _updateState.update { it.copy(downloading = false, downloadProgress = 100) }
            } catch (_: Exception) {
                _updateState.update { it.copy(downloading = false, error = "Could not open installer.") }
            }
        }
    }

    // ── Backup & restore ─────────────────────────────────────────────────────
    // Owned by BackupManager (a Singleton), not this ViewModel — an import launched from Settings
    // must keep running (and its result must still be observable) even if the user navigates away
    // and this ViewModel gets recreated.
    val backupState: StateFlow<com.betteraudio.data.backup.BackupUiState> = backupManager.uiState

    val autoBackupEnabled: StateFlow<Boolean> =
        settings.autoBackupEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val autoBackupFolderUri: StateFlow<String> =
        settings.autoBackupFolderUri.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val autoBackupLastRunMs: StateFlow<Long> =
        settings.autoBackupLastRunMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)
    val autoBackupLastStatus: StateFlow<String> =
        settings.autoBackupLastStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val backupIncludeApiKey: StateFlow<Boolean> =
        settings.backupIncludeApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setBackupIncludeApiKey(enabled: Boolean) = viewModelScope.launch { settings.setBackupIncludeApiKey(enabled) }

    fun exportBackup(uri: android.net.Uri, includeApiKey: Boolean) = backupManager.exportFile(uri, includeApiKey)

    fun importBackup(uri: android.net.Uri, forceOverwrite: Boolean) = backupManager.importFile(uri, forceOverwrite)

    fun clearBackupResult() = backupManager.clearResult()

    /** Writes a share-ready copy (API key always stripped) to filesDir and returns it. */
    suspend fun writeShareBackupFile(): java.io.File = backupManager.writeShareBackupFile()

    /** Only ever called after a folder was actually picked (see the auto-backup switch in
     *  SettingsScreen) — always safe to enable here without leaving a folder-less toggle on. */
    fun setAutoBackupFolder(uri: android.net.Uri) = viewModelScope.launch {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        settings.setAutoBackupFolderUri(uri.toString())
        settings.setAutoBackupEnabled(true)
        com.betteraudio.data.backup.AutoBackupWorker.schedule(appContext)
    }

    fun setAutoBackupEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.setAutoBackupEnabled(enabled)
        if (enabled && settings.autoBackupFolderUri.first().isNotBlank()) {
            com.betteraudio.data.backup.AutoBackupWorker.schedule(appContext)
        } else {
            com.betteraudio.data.backup.AutoBackupWorker.cancel(appContext)
        }
    }

    fun runAutoBackupNow() {
        com.betteraudio.data.backup.AutoBackupWorker.runNow(appContext)
    }

    fun loadWhatsNew() {
        if (_whatsNew.value.loading || _whatsNew.value.notes.isNotEmpty()) return
        viewModelScope.launch {
            _whatsNew.update { WhatsNewState(loading = true) }
            val result = updateChecker.fetchLatestReleaseNotes()
            if (result != null) {
                _whatsNew.update { WhatsNewState(version = result.first, notes = result.second) }
            } else {
                _whatsNew.update { WhatsNewState(error = true) }
            }
        }
    }
}
