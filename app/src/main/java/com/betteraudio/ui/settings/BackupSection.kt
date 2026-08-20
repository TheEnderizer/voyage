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
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
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

// ─── Backup & restore ──────────────────────────────────────────────────────────

internal fun LazyListScope.backupSection(context: Context, viewModel: SettingsViewModel) {
    item {
        val backupState by viewModel.backupState.collectAsStateWithLifecycle()
        val includeApiKey by viewModel.backupIncludeApiKey.collectAsStateWithLifecycle()
        val autoEnabled by viewModel.autoBackupEnabled.collectAsStateWithLifecycle()
        val autoFolderUri by viewModel.autoBackupFolderUri.collectAsStateWithLifecycle()
        val autoLastRunMs by viewModel.autoBackupLastRunMs.collectAsStateWithLifecycle()
        val autoLastStatus by viewModel.autoBackupLastStatus.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var lastImportUri by remember { mutableStateOf<Uri?>(null) }
        var shareError by remember { mutableStateOf<String?>(null) }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri -> uri?.let { viewModel.exportBackup(it, includeApiKey) } }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri -> uri?.let { lastImportUri = it; viewModel.importBackup(it, forceOverwrite = false) } }

        val folderLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri -> uri?.let { viewModel.setAutoBackupFolder(it) } }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Manual backup")
            Text(
                "Save your library's progress, bookmarks, listening history, presets, and series to a file — or restore from one. Audio files themselves aren't included; only the data about them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Include AI key in export", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Off by default — the exported file won't carry your Gemini API key.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HapticSwitch(checked = includeApiKey, onCheckedChange = { viewModel.setBackupIncludeApiKey(it) })
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HapticFilledTonalButton(
                            shape = Pill,
                            enabled = !backupState.exporting,
                            onClick = {
                                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                                exportLauncher.launch("voyage-backup-$stamp.json")
                            }
                        ) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (backupState.exporting) "Exporting…" else "Export")
                        }
                        HapticFilledTonalButton(
                            shape = Pill,
                            enabled = !backupState.importing,
                            onClick = { importLauncher.launch(arrayOf("application/json")) }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (backupState.importing) "Importing…" else "Import")
                        }
                        HapticFilledTonalButton(shape = Pill, onClick = {
                            scope.launch {
                                try {
                                    val file = viewModel.writeShareBackupFile()
                                    shareBackupFile(context, file)
                                } catch (e: Exception) {
                                    shareError = "Couldn't share the backup: ${e.message}"
                                }
                            }
                        }) {
                            Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Share")
                        }
                    }
                }
            }

            SectionHeader("Automatic backup")
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Back up daily", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Writes a backup to your chosen folder once a day, keeping the last 5. Never includes your AI key.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HapticSwitch(
                            checked = autoEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && autoFolderUri.isBlank()) {
                                    // setAutoBackupFolder() enables it once a folder is actually
                                    // picked — if the user cancels the picker, the switch must
                                    // stay off rather than being left on with nothing to write to.
                                    folderLauncher.launch(null)
                                } else {
                                    viewModel.setAutoBackupEnabled(enabled)
                                }
                            }
                        )
                    }
                    HapticOutlinedButton(shape = Pill, onClick = { folderLauncher.launch(null) }) {
                        Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (autoFolderUri.isBlank()) "Choose folder" else "Change folder")
                    }
                    if (autoFolderUri.isNotBlank()) {
                        val statusText = when {
                            autoLastRunMs == 0L -> "Not run yet"
                            autoLastStatus == "ok" -> "Last backup: ${relativeTime(autoLastRunMs)}"
                            else -> "Last attempt failed: $autoLastStatus"
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (autoLastStatus != "ok" && autoLastRunMs != 0L)
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HapticTextButton(onClick = { viewModel.runAutoBackupNow() }) { Text("Back up now") }
                    }
                }
            }
        }

        backupState.error?.let { error ->
            AlertDialog(
                onDismissRequest = { viewModel.clearBackupResult() },
                title = { Text("Backup error") },
                text = { Text(error) },
                confirmButton = { HapticTextButton(onClick = { viewModel.clearBackupResult() }) { Text("OK") } }
            )
        }
        shareError?.let { error ->
            AlertDialog(
                onDismissRequest = { shareError = null },
                title = { Text("Share failed") },
                text = { Text(error) },
                confirmButton = { HapticTextButton(onClick = { shareError = null }) { Text("OK") } }
            )
        }
        backupState.lastResult?.let { result ->
            AlertDialog(
                onDismissRequest = { viewModel.clearBackupResult() },
                title = { Text("Import complete") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Books matched: ${result.booksMatched}")
                        if (result.booksSkippedNoMatch > 0) {
                            Text("Books not found on this device: ${result.booksSkippedNoMatch}")
                            Text(
                                "Tip: scan your library, then import again to match these.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (result.booksSkippedAmbiguous > 0) Text("Books skipped (ambiguous match): ${result.booksSkippedAmbiguous}")
                        if (result.booksSkippedStale > 0) Text("Kept local progress (newer than backup): ${result.booksSkippedStale}")
                        Text("Bookmarks restored: ${result.bookmarksRestored}")
                        Text("Listening sessions restored: ${result.sessionsRestored}")
                        Text("Position history restored: ${result.skipEventsRestored}")
                        Text("Presets restored: ${result.presetsRestored}")
                        Text("Series restored: ${result.seriesRestored}")
                        Text("Settings restored")
                    }
                },
                confirmButton = { HapticTextButton(onClick = { viewModel.clearBackupResult() }) { Text("OK") } },
                dismissButton = if (result.booksSkippedStale > 0 && lastImportUri != null) {
                    {
                        HapticTextButton(onClick = {
                            lastImportUri?.let { viewModel.importBackup(it, forceOverwrite = true) }
                        }) { Text("Overwrite anyway") }
                    }
                } else null
            )
        }
    }
}

private fun shareBackupFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Voyage backup")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun relativeTime(ts: Long): String {
    val diffMs = System.currentTimeMillis() - ts
    val minutes = diffMs / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

