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
import androidx.compose.ui.text.input.KeyboardType
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
import com.betteraudio.util.log.LogEngine
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// ─── Diagnostics (in-app log) ─────────────────────────────────────────────────

private val LOG_LEVELS = listOf("OFF" to "Off", "ON" to "On", "VERBOSE" to "Verbose")

internal fun LazyListScope.diagnosticsSection(context: Context, viewModel: SettingsViewModel) {
    item {
        val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Logging")
            Text(
                "Off keeps only crashes and errors (with a short trail leading up to them). " +
                    "On records the normal flow of what the app does. Verbose adds fine-grained " +
                    "detail — turn it on right before reproducing a bug.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LOG_LEVELS.forEachIndexed { index, (value, display) ->
                    SegmentedButton(
                        selected = logLevel == value,
                        onClick = { viewModel.setLogLevel(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, LOG_LEVELS.size)
                    ) { Text(display) }
                }
            }
        }
    }
    item {
        val persistedBudget by viewModel.logBudgetMb.collectAsStateWithLifecycle()
        val liveInput by viewModel.logBudgetInput.collectAsStateWithLifecycle()
        val stats by viewModel.logStats.collectAsStateWithLifecycle()
        var text by remember(persistedBudget) { mutableStateOf(formatMb(persistedBudget)) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader("Log size limit")
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new
                    new.toFloatOrNull()?.let { viewModel.setLogBudgetInput(it) }
                },
                modifier = Modifier.fillMaxWidth(0.5f),
                singleLine = true,
                suffix = { Text("MB") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                supportingText = { Text("Recommended: 2 · max 20") },
                trailingIcon = if (liveInput != null) {
                    { TextButton(onClick = {
                        val mb = text.toFloatOrNull() ?: return@TextButton
                        viewModel.commitLogBudgetMb(mb)
                    }) { Text("Set") } }
                } else null
            )
            stats?.let {
                Text(
                    "${formatBytes(it.bytesOnDisk)} on disk / ${formatMb(persistedBudget)} MB · " +
                        "~${it.approxLines} lines · covers ${formatDuration(it.coverageMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    item {
        val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager }
        var ignoringOptimizations by remember {
            mutableStateOf(powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true)
        }
        val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ignoringOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        if (!ignoringOptimizations) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Background reliability")
                Text(
                    "Some phone makers (this one included) aggressively restrict apps running in the " +
                        "background to save battery, which can delay the home-screen widget updating " +
                        "or occasionally interrupt playback. Exempting Voyage from battery optimization " +
                        "fixes this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    shape = Pill,
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { batteryLauncher.launch(intent) }
                    }
                ) {
                    Icon(Icons.Default.BatteryChargingFull, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Remove battery restrictions")
                }
            }
        }
    }
    item {
        val logText by viewModel.logText.collectAsStateWithLifecycle()
        val logLoading by viewModel.logLoading.collectAsStateWithLifecycle()
        var rawMode by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(rawMode) { viewModel.refreshLog(redactPaths = !rawMode) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Diagnostics log")
            Text(
                "A rolling record of what the app does — playback, scans, navigation, errors and crashes. When something goes wrong, copy or share this and send it over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = rawMode, onCheckedChange = { rawMode = it })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Include full folder/file names", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Off by default. The raw log helps diagnose scan/playback issues but " +
                            "reveals your library's folder names — off to be safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(shape = Pill, onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Voyage log", logText))
                }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Copy")
                }
                FilledTonalButton(shape = Pill, onClick = {
                    scope.launch { shareLogBundle(context, viewModel, redactPaths = !rawMode) }
                }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Share")
                }
                FilledTonalButton(shape = Pill, onClick = { viewModel.clearLog() }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Clear")
                }
                IconButton(onClick = { viewModel.refreshLog(redactPaths = !rawMode) }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
            CardContainer {
                Box(Modifier.heightIn(max = 460.dp)) {
                    if (logLoading) {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    } else {
                        Text(
                            logText.ifBlank { "(empty)" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

/** Builds the (by default redacted) share bundle off the main thread, then launches the system
 *  share sheet. Writes to a FIXED filename in backup_share/ (mirroring BackupManager's own
 *  fixed-name export) so repeated shares self-overwrite instead of accumulating. */
private suspend fun shareLogBundle(context: Context, viewModel: SettingsViewModel, redactPaths: Boolean) {
    val dir = File(context.filesDir, "backup_share").apply { mkdirs() }
    val bundle = File(dir, "voyage-logs.zip")
    viewModel.buildLogShareBundle(bundle, redactPaths)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Voyage diagnostics log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun formatMb(mb: Float): String =
    if (mb == mb.toLong().toFloat()) mb.toLong().toString() else String.format("%.1f", mb)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes % (24 * 60)) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
