package com.betteraudio.ui.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.update.ReleaseInfo
import com.betteraudio.data.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateGateState(
    val info: ReleaseInfo? = null,   // non-null ⇒ show the update screen
    val downloading: Boolean = false,
    val progress: Int = 0,
    val error: String? = null
)

/**
 * Drives the on-launch update prompt. Checks GitHub once per app start; if a newer release
 * exists for the current channel and the user hasn't already skipped that exact version, the
 * update screen is shown. "Skip" records the version so it never prompts again until a newer
 * one is released; "Install" downloads the APK and opens the system installer.
 */
@HiltViewModel
class UpdateGateViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val updateChecker: UpdateChecker,
    private val settings: SettingsStore
) : ViewModel() {

    private val _state = MutableStateFlow(UpdateGateState())
    val state: StateFlow<UpdateGateState> = _state.asStateFlow()

    private var checked = false

    /** Run once per process launch. Safe to call from a LaunchedEffect(Unit). */
    fun checkOnLaunch() {
        if (checked) return
        checked = true
        viewModelScope.launch {
            // A failed check has nothing to show the gate for either (there's no update-prompt
            // screen appropriate for "the check itself failed" on a passive launch-time gate —
            // the manual check button in Settings is where that's surfaced), but it's now at
            // least distinguishable from UpToDate in the log instead of silently doing nothing.
            val info = when (val result = updateChecker.checkForUpdate()) {
                is com.betteraudio.data.update.UpdateCheckResult.Available -> result.info
                is com.betteraudio.data.update.UpdateCheckResult.UpToDate -> return@launch
                is com.betteraudio.data.update.UpdateCheckResult.Failed -> {
                    com.betteraudio.util.AppLog.i("Update", "launch check skipped: ${result.reason}")
                    return@launch
                }
            }
            val skipped = settings.skippedUpdateVersion.first()
            if (info.versionName != skipped) {
                _state.update { it.copy(info = info) }
            }
        }
    }

    fun skip() {
        val version = _state.value.info?.versionName ?: return
        viewModelScope.launch { settings.setSkippedUpdateVersion(version) }
        _state.value = UpdateGateState()
    }

    /** Hide for now without recording a skip — the prompt returns on the next launch. */
    fun dismissForNow() {
        if (_state.value.downloading) return
        _state.value = UpdateGateState()
    }

    fun install() {
        val url = _state.value.info?.apkDownloadUrl ?: return
        if (_state.value.downloading) return
        viewModelScope.launch {
            _state.update { it.copy(downloading = true, progress = 0, error = null) }
            val file = updateChecker.downloadApk(url) { p -> _state.update { it.copy(progress = p) } }
            if (file == null) {
                _state.update { it.copy(downloading = false, error = "Download failed. Try again.") }
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(
                    appContext, "${appContext.packageName}.fileprovider", file
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                _state.update { it.copy(downloading = false, progress = 100) }
            } catch (_: Exception) {
                _state.update { it.copy(downloading = false, error = "Could not open installer.") }
            }
        }
    }
}
