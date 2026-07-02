package com.betteraudio.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.betteraudio.BuildConfig
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

sealed class SettingsSection {
    object Root : SettingsSection()
    object Library : SettingsSection()
    object Playback : SettingsSection()
    object AI : SettingsSection()
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
    private val updateChecker: UpdateChecker,
    private val repository: AudiobookRepository,
    private val restructurer: com.betteraudio.data.files.LibraryRestructurer
) : ViewModel() {

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
    fun setSkipSilenceMinMs(ms: Long) = viewModelScope.launch { settings.setSkipSilenceMinMs(ms) }
    fun setSkipSilenceThreshold(level: Int) = viewModelScope.launch { settings.setSkipSilenceThreshold(level) }

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
