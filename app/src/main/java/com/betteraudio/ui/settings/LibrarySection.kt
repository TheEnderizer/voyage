package com.betteraudio.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.components.FolderBrowser
import com.betteraudio.ui.components.ImportStructureDialog
import com.betteraudio.ui.components.label
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.material.MaterialStyle
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import com.betteraudio.util.AppLog
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.betteraudio.ui.haptics.*

internal fun LazyListScope.librarySection(
    context: Context,
    storageGranted: Boolean,
    libraryFolder: String,
    bookCount: Int,
    rescanRunning: Boolean,
    coverRefreshRunning: Boolean,
    coverRefreshProgress: Pair<Int, Int>?,
    resetRunning: Boolean,
    ignoredBooks: List<com.betteraudio.data.db.entities.Book>,
    importStructure: com.betteraudio.data.scanner.ImportStructure,
    storageSettingsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    onBrowse: () -> Unit,
    ebookFolder: String,
    onBrowseEbooks: () -> Unit,
    viewModel: SettingsViewModel
) {
    item {
        SettingsCard(
            icon = if (storageGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
            iconTint = if (storageGranted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            title = if (storageGranted) "All-files access granted" else "All-files access needed",
            subtitle = if (storageGranted) "Voyage can read your files" else "Required to read audiobook files",
            trailing = {
                if (!storageGranted) {
                    HapticButton(
                        onClick = { storageSettingsLauncher.launch(allFilesAccessIntent(context)) },
                        shape = Pill
                    ) { Text("Grant") }
                }
            }
        )
    }
    item {
        SettingsCard(
            icon = Icons.Default.Folder,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Audiobook folder",
            subtitle = libraryFolder.ifBlank { "Not set — tap to choose" },
            subtitleMono = true,
            onClick = onBrowse
        )
    }
    if (com.betteraudio.util.FeatureFlags.EBOOKS_UI) {
        item {
            SettingsCard(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                iconTint = MaterialTheme.colorScheme.primary,
                title = "Ebook folder",
                subtitle = ebookFolder.ifBlank { "Not set — standalone ebooks live here" },
                subtitleMono = ebookFolder.isNotBlank(),
                onClick = onBrowseEbooks
            )
        }
    }
    item {
        var showStructureDialog by remember { mutableStateOf(false) }
        SettingsCard(
            icon = Icons.AutoMirrored.Filled.List,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Library structure",
            subtitle = importStructure.label(),
            onClick = { showStructureDialog = true }
        )
        if (showStructureDialog) {
            ImportStructureDialog(
                initial = importStructure,
                confirmLabel = "Save & rescan",
                onConfirm = { chosen ->
                    viewModel.setImportStructure(chosen, rescan = true)
                    showStructureDialog = false
                },
                onDismiss = { showStructureDialog = false }
            )
        }
    }
    item {
        SettingsCard(
            icon = Icons.Default.Refresh,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Rescan library",
            subtitle = "$bookCount book${if (bookCount != 1) "s" else ""} in library",
            onClick = if (libraryFolder.isNotBlank() && !rescanRunning) ({ viewModel.rescan() }) else null,
            trailing = {
                if (rescanRunning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        )
    }
    item {
        SettingsCard(
            icon = Icons.Default.AutoAwesome,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Refresh all cover effects",
            subtitle = if (coverRefreshRunning && coverRefreshProgress != null)
                "Baking ${coverRefreshProgress.first} / ${coverRefreshProgress.second}… (tap to cancel)"
            else "Re-bake the blur effect for every book",
            onClick = if (coverRefreshRunning) ({ viewModel.cancelCoverRefresh() }) else ({ viewModel.refreshAllCoverEffects() }),
            trailing = {
                if (coverRefreshRunning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        )
    }
    item {
        var showResetConfirm by remember { mutableStateOf(false) }
        SettingsCard(
            icon = Icons.Default.DeleteSweep,
            iconTint = MaterialTheme.colorScheme.error,
            title = "Reset library",
            subtitle = "Remove all books from the app (your audio files are kept)",
            onClick = if (!resetRunning) ({ showResetConfirm = true }) else null,
            trailing = {
                if (resetRunning) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        )
        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                icon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Reset library?") },
                text = {
                    Text(
                        "This removes every book, series, group, bookmark and listening-history " +
                        "entry from the app. Your audio files on the device are not deleted — " +
                        "rescan to import them again."
                    )
                },
                confirmButton = {
                    HapticTextButton(
                        onClick = { viewModel.resetLibrary(); showResetConfirm = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reset") }
                },
                dismissButton = {
                    HapticTextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }

    item {
        val restructure by viewModel.restructure.collectAsStateWithLifecycle()
        var showPicker by remember { mutableStateOf(false) }
        var showRestructure by remember { mutableStateOf(false) }
        SettingsCard(
            icon = Icons.AutoMirrored.Filled.DriveFileMove,
            iconTint = MaterialTheme.colorScheme.secondary,
            title = "Restructure files on disk",
            subtitle = "Move audio files to match a chosen Author / Series / Book layout",
            onClick = if (!restructure.running) ({ showPicker = true }) else null,
            trailing = {
                if (restructure.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
        )
        if (showPicker) {
            ImportStructureDialog(
                initial = importStructure,
                confirmLabel = "Next",
                onConfirm = { chosen ->
                    viewModel.chooseRestructureStructure(chosen)
                    showPicker = false
                    showRestructure = true
                },
                onDismiss = { showPicker = false }
            )
        }
        if (showRestructure) {
            AlertDialog(
                onDismissRequest = { if (!restructure.running) { showRestructure = false; viewModel.clearRestructure() } },
                icon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, tint = MaterialTheme.colorScheme.secondary) },
                title = { Text("Restructure files?") },
                text = {
                    Column {
                        val result = restructure.result
                        when {
                            result != null -> Text(
                                "Done. Moved ${result.moved}, skipped ${result.skipped}" +
                                    (if (result.failed > 0) ", failed ${result.failed}" else "") + "."
                            )
                            restructure.running -> Text("Moving… ${restructure.done}/${restructure.total}")
                            else -> Text(
                                "This will move ${restructure.planCount ?: "…"} book folder(s) on your device " +
                                    "to match the chosen library structure, using each book's author and series. " +
                                    "Files are copied and verified before the originals are removed, so nothing is lost. " +
                                    "Books already in place or without a real folder are skipped."
                            )
                        }
                    }
                },
                confirmButton = {
                    val result = restructure.result
                    if (result != null || restructure.running) {
                        HapticTextButton(
                            onClick = { showRestructure = false; viewModel.clearRestructure() },
                            enabled = !restructure.running
                        ) { Text("Done") }
                    } else {
                        HapticTextButton(
                            onClick = { viewModel.runRestructure() },
                            enabled = (restructure.planCount ?: 0) > 0
                        ) { Text("Restructure") }
                    }
                },
                dismissButton = {
                    if (restructure.result == null && !restructure.running) {
                        HapticTextButton(onClick = { showRestructure = false; viewModel.clearRestructure() }) { Text("Cancel") }
                    }
                }
            )
        }
    }

    item {
        val healthy by viewModel.diskMirrorHealthy.collectAsStateWithLifecycle()
        val lastError by viewModel.diskMirrorLastError.collectAsStateWithLifecycle()
        val exportState by viewModel.diskExportState.collectAsStateWithLifecycle()
        var showForgetConfirm by remember { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Reinstall-proof library data")
            Text(
                "Every book's title, cover, progress and bookmarks — plus your presets, series and " +
                    "settings — are mirrored into the audiobook folder itself, so they survive a " +
                    "delete + reinstall. Just pick the same folder again and rescan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SettingsCard(
                icon = if (healthy) Icons.Default.CheckCircle else Icons.Default.Warning,
                iconTint = if (healthy) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                title = if (healthy) "Library data mirror healthy" else "Library data mirror unhealthy",
                subtitle = when {
                    exportState.running -> "Exporting… ${exportState.done}/${exportState.total}"
                    !healthy && lastError != null -> lastError!!
                    else -> "Tap to re-export now"
                },
                onClick = if (!exportState.running) ({ viewModel.reExportDiskData() }) else null,
                trailing = {
                    if (exportState.running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            )
            SettingsCard(
                icon = Icons.Default.DeleteSweep,
                iconTint = MaterialTheme.colorScheme.error,
                title = "Forget disk data for this library",
                subtitle = "Delete the on-disk mirror only (audio files kept), then rebuild it fresh from the app",
                onClick = if (!exportState.running) ({ showForgetConfirm = true }) else null
            )
        }
        if (showForgetConfirm) {
            AlertDialog(
                onDismissRequest = { showForgetConfirm = false },
                icon = { Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error) },
                title = { Text("Forget disk data?") },
                text = {
                    Text(
                        "This deletes every book's mirrored data file, cover copy and library.json — " +
                        "not your audio files — then writes a fresh copy from what's currently in the " +
                        "app. Use this if a mirrored file ever looks corrupted."
                    )
                },
                confirmButton = {
                    HapticTextButton(
                        onClick = { viewModel.forgetDiskData(); showForgetConfirm = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Forget & rebuild") }
                },
                dismissButton = {
                    HapticTextButton(onClick = { showForgetConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }

    if (ignoredBooks.isNotEmpty()) {
        item { SectionHeader("Hidden Books") }
        item {
            CardContainer {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ignoredBooks.forEach { book ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(book.displayTitle, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (book.displayAuthor.isNotBlank()) {
                                    Text(book.displayAuthor, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                            }
                            HapticTextButton(onClick = { viewModel.restoreBook(book.id) }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }
}

