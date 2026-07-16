package com.betteraudio.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.betteraudio.BuildConfig
import com.betteraudio.data.db.dao.CustomWidgetDesignDao
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.CustomWidgetDesign
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.scanner.AudioFileScanner
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.update.ReleaseInfo
import com.betteraudio.data.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class BackupUiState(
    val exporting: Boolean = false,
    val importing: Boolean = false,
    val lastResult: com.betteraudio.data.backup.BackupManager.RestoreResult? = null,
    val error: String? = null
)

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
    private val customWidgetDesignDao: CustomWidgetDesignDao,
    private val backupManager: com.betteraudio.data.backup.BackupManager
) : ViewModel() {

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

    /** Dry-run: count how many books would move (shown in the confirm dialog). */
    fun loadRestructurePlan() {
        viewModelScope.launch {
            _restructure.value = RestructureUi(planCount = restructurer.plan().size)
        }
    }

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

    val defaultSpeed: StateFlow<Float> =
        settings.defaultSpeed.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            SettingsStore.DEFAULT_SPEED
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

    private val _resetRunning = MutableStateFlow(false)
    val resetRunning: StateFlow<Boolean> = _resetRunning.asStateFlow()

    /** Clear the whole library from the DB (audio files on disk are kept). */
    fun resetLibrary() {
        if (_resetRunning.value) return
        viewModelScope.launch {
            _resetRunning.value = true
            try { repository.resetLibrary() } catch (_: Exception) {}
            _resetRunning.value = false
        }
    }

    fun refreshAllCoverEffects() {
        if (_coverRefreshRunning.value) return
        viewModelScope.launch {
            _coverRefreshRunning.value = true
            try { repository.regenerateAllCoverFx() } catch (_: Exception) {}
            _coverRefreshRunning.value = false
        }
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
            com.betteraudio.widget.WidgetRender.refresh(appContext)
        }
    }

    fun clearWidgetDefaultCover() = viewModelScope.launch {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { java.io.File(appContext.filesDir, "widget_default_cover.jpg").delete() }
        }
        settings.setWidgetDefaultCoverPath("")
        com.betteraudio.widget.WidgetRender.refresh(appContext)
    }

    // ── Custom widget maker ─────────────────────────────────────────────────────
    val widgetHideWhenIdle: StateFlow<Boolean> =
        settings.widgetHideWhenIdle.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setWidgetHideWhenIdle(enabled: Boolean) = viewModelScope.launch {
        settings.setWidgetHideWhenIdle(enabled)
    }

    val customWidgets: StateFlow<List<CustomWidgetDesign>> =
        customWidgetDesignDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteCustomWidget(id: Long) = viewModelScope.launch {
        customWidgetDesignDao.deleteById(id)
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

    val darkMode: StateFlow<com.betteraudio.ui.theme.DarkMode> =
        settings.darkMode
            .map { com.betteraudio.ui.theme.DarkMode.from(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.theme.DarkMode.AUTO)

    val pureBlack: StateFlow<Boolean> =
        settings.pureBlack.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setCustomThemeColor(value: String) =
        viewModelScope.launch { settings.setCustomThemeColor(value) }

    fun setDarkMode(mode: com.betteraudio.ui.theme.DarkMode) =
        viewModelScope.launch { settings.setDarkMode(mode.name) }

    fun setPureBlack(enabled: Boolean) =
        viewModelScope.launch { settings.setPureBlack(enabled) }

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
    fun setDefaultSpeed(speed: Float) = viewModelScope.launch { settings.setDefaultSpeed(speed) }
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

    fun rescan() {
        val path = libraryFolder.value
        if (path.isBlank() || _rescanRunning.value) return
        viewModelScope.launch {
            _rescanRunning.value = true
            try { scanner.scanDirectory(path) } catch (_: Exception) {}
            _rescanRunning.value = false
        }
    }

    fun checkForUpdate() {
        if (_updateState.value.checking) return
        viewModelScope.launch {
            _updateState.update { UpdateUiState(checking = true) }
            val info = updateChecker.checkForUpdate()
            _updateState.update {
                if (info != null) UpdateUiState(available = info)
                else UpdateUiState(upToDate = true)
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
    private val _backupState = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backupState.asStateFlow()

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

    fun exportBackup(uri: android.net.Uri, includeApiKey: Boolean) {
        if (_backupState.value.exporting) return
        viewModelScope.launch {
            _backupState.update { it.copy(exporting = true, error = null) }
            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        backupManager.export(out, includeApiKey)
                    } ?: throw java.io.IOException("Could not open the chosen file for writing")
                }
                _backupState.update { it.copy(exporting = false) }
            } catch (e: Exception) {
                _backupState.update { it.copy(exporting = false, error = "Export failed: ${e.message}") }
            }
        }
    }

    fun importBackup(uri: android.net.Uri, forceOverwrite: Boolean) {
        if (_backupState.value.importing) return
        viewModelScope.launch {
            _backupState.update { it.copy(importing = true, error = null, lastResult = null) }
            try {
                val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        backupManager.restore(input, forceOverwrite)
                    } ?: throw java.io.IOException("Could not open the chosen file for reading")
                }
                _backupState.update { it.copy(importing = false, lastResult = result) }
            } catch (e: Exception) {
                _backupState.update { it.copy(importing = false, error = "Import failed: ${e.message}") }
            }
        }
    }

    fun clearBackupResult() = _backupState.update { it.copy(lastResult = null, error = null) }

    /** Writes a share-ready copy (API key always stripped) to filesDir and returns it. */
    suspend fun writeShareBackupFile(): java.io.File = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(appContext.filesDir, "backup_share").apply { mkdirs() }
        val file = java.io.File(dir, "voyage-backup.json")
        file.outputStream().use { out -> backupManager.exportForSharing(out) }
        file
    }

    fun setAutoBackupFolder(uri: android.net.Uri) = viewModelScope.launch {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        settings.setAutoBackupFolderUri(uri.toString())
        if (settings.autoBackupEnabled.first()) {
            com.betteraudio.data.backup.AutoBackupWorker.schedule(appContext)
        }
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
