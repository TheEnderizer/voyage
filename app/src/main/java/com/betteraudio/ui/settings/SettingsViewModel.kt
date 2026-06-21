package com.betteraudio.ui.settings

import android.content.Context
import android.content.Intent
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
    repository: AudiobookRepository
) : ViewModel() {

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

    private val _rescanRunning = MutableStateFlow(false)
    val rescanRunning: StateFlow<Boolean> = _rescanRunning.asStateFlow()

    val geminiApiKey: StateFlow<String> =
        settings.geminiApiKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _updateState = MutableStateFlow(UpdateUiState())
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _whatsNew = MutableStateFlow(WhatsNewState())
    val whatsNew: StateFlow<WhatsNewState> = _whatsNew.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    fun setLibraryFolder(path: String) = viewModelScope.launch { settings.setLibraryFolder(path) }
    fun setSkipForward(ms: Long) = viewModelScope.launch { settings.setSkipForwardMs(ms) }
    fun setSkipBack(ms: Long) = viewModelScope.launch { settings.setSkipBackMs(ms) }
    fun setDefaultSpeed(speed: Float) = viewModelScope.launch { settings.setDefaultSpeed(speed) }
    fun setGeminiApiKey(key: String) = viewModelScope.launch { settings.setGeminiApiKey(key) }

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
