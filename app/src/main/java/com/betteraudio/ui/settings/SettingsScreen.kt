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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import com.betteraudio.util.AppLog
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val libraryFolder   by viewModel.libraryFolder.collectAsStateWithLifecycle()
    val skipForwardMs   by viewModel.skipForwardMs.collectAsStateWithLifecycle()
    val skipBackMs      by viewModel.skipBackMs.collectAsStateWithLifecycle()
    val defaultSpeed    by viewModel.defaultSpeed.collectAsStateWithLifecycle()
    val bookCount       by viewModel.bookCount.collectAsStateWithLifecycle()
    val ignoredBooks    by viewModel.ignoredBooks.collectAsStateWithLifecycle()
    val rescanRunning        by viewModel.rescanRunning.collectAsStateWithLifecycle()
    val coverRefreshRunning  by viewModel.coverRefreshRunning.collectAsStateWithLifecycle()
    val resetRunning         by viewModel.resetRunning.collectAsStateWithLifecycle()
    val geminiApiKey    by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val updateState               by viewModel.updateState.collectAsStateWithLifecycle()
    val whatsNew                  by viewModel.whatsNew.collectAsStateWithLifecycle()
    val currentSection            by viewModel.currentSection.collectAsStateWithLifecycle()
    val autoRewindSeconds         by viewModel.autoRewindSeconds.collectAsStateWithLifecycle()
    val autoRewindThresholdMinutes by viewModel.autoRewindThresholdMinutes.collectAsStateWithLifecycle()
    val skipSilenceMinMs          by viewModel.skipSilenceMinMs.collectAsStateWithLifecycle()
    val skipSilenceThreshold      by viewModel.skipSilenceThreshold.collectAsStateWithLifecycle()
    val importStructure           by viewModel.importStructure.collectAsStateWithLifecycle()

    var showBrowser by remember { mutableStateOf(false) }
    var storageGranted by remember { mutableStateOf(hasAllFilesAccess()) }

    val storageSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { storageGranted = hasAllFilesAccess() }

    LaunchedEffect(Unit) { viewModel.loadWhatsNew() }

    if (showBrowser) {
        FolderBrowser(
            startPath = libraryFolder.ifBlank { "/storage/emulated/0" },
            onSelect = {
                viewModel.setLibraryFolder(it)
                viewModel.rescan()
                showBrowser = false
            },
            onCancel = { showBrowser = false }
        )
    }

    BackHandler(enabled = currentSection != SettingsSection.Root) {
        viewModel.navigateTo(SettingsSection.Root)
    }

    val sectionTitle = when (currentSection) {
        SettingsSection.Root -> "Settings"
        SettingsSection.Library -> "Library"
        SettingsSection.Playback -> "Playback"
        SettingsSection.AI -> "AI Synopsis"
        SettingsSection.Updates -> "Updates"
        SettingsSection.About -> "About"
        SettingsSection.Diagnostics -> "Diagnostics"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(sectionTitle, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentSection == SettingsSection.Root) onBack()
                        else viewModel.navigateTo(SettingsSection.Root)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentSection,
            transitionSpec = {
                fadeIn(tween(160)) togetherWith fadeOut(tween(160))
            },
            label = "settings_section",
            modifier = Modifier.padding(padding)
        ) { section ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (section) {
                    SettingsSection.Root -> rootSection(viewModel)
                    SettingsSection.Library -> librarySection(
                        context, storageGranted, libraryFolder, bookCount, rescanRunning,
                        coverRefreshRunning, resetRunning, ignoredBooks, importStructure,
                        storageSettingsLauncher, { showBrowser = true }, viewModel
                    )
                    SettingsSection.Playback -> playbackSection(
                        skipForwardMs, skipBackMs, defaultSpeed,
                        autoRewindSeconds, autoRewindThresholdMinutes,
                        skipSilenceMinMs, skipSilenceThreshold, viewModel
                    )
                    SettingsSection.AI -> aiSection(geminiApiKey, viewModel)
                    SettingsSection.Updates -> updatesSection(updateState, whatsNew, viewModel)
                    SettingsSection.About -> aboutSection(updateState, viewModel)
                    SettingsSection.Diagnostics -> diagnosticsSection(context)
                }
            }
        }
    }
}

// ─── Section content blocks ───────────────────────────────────────────────────

private fun LazyListScope.rootSection(viewModel: SettingsViewModel) {
    val rows = listOf(
        Triple(Icons.Default.Folder, "Library", SettingsSection.Library),
        Triple(Icons.Default.Speed, "Playback", SettingsSection.Playback),
        Triple(Icons.Default.AutoAwesome, "AI Synopsis", SettingsSection.AI),
        Triple(Icons.Default.Info, "About", SettingsSection.About),
        Triple(Icons.Default.BugReport, "Diagnostics", SettingsSection.Diagnostics),
    )
    item { Spacer(Modifier.height(4.dp)) }
    rows.forEach { (icon, label, dest) ->
        item {
            NavRow(icon = icon, label = label, onClick = { viewModel.navigateTo(dest) })
        }
    }
}

private fun LazyListScope.librarySection(
    context: Context,
    storageGranted: Boolean,
    libraryFolder: String,
    bookCount: Int,
    rescanRunning: Boolean,
    coverRefreshRunning: Boolean,
    resetRunning: Boolean,
    ignoredBooks: List<com.betteraudio.data.db.entities.Book>,
    importStructure: com.betteraudio.data.scanner.ImportStructure,
    storageSettingsLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
    onBrowse: () -> Unit,
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
                    Button(
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
            subtitle = "Re-bake the blur effect for every book",
            onClick = if (!coverRefreshRunning) ({ viewModel.refreshAllCoverEffects() }) else null,
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
                    TextButton(
                        onClick = { viewModel.resetLibrary(); showResetConfirm = false },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }

    item {
        val restructure by viewModel.restructure.collectAsStateWithLifecycle()
        var showPicker by remember { mutableStateOf(false) }
        var showRestructure by remember { mutableStateOf(false) }
        SettingsCard(
            icon = Icons.Default.DriveFileMove,
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
                icon = { Icon(Icons.Default.DriveFileMove, null, tint = MaterialTheme.colorScheme.secondary) },
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
                        TextButton(
                            onClick = { showRestructure = false; viewModel.clearRestructure() },
                            enabled = !restructure.running
                        ) { Text("Done") }
                    } else {
                        TextButton(
                            onClick = { viewModel.runRestructure() },
                            enabled = (restructure.planCount ?: 0) > 0
                        ) { Text("Restructure") }
                    }
                },
                dismissButton = {
                    if (restructure.result == null && !restructure.running) {
                        TextButton(onClick = { showRestructure = false; viewModel.clearRestructure() }) { Text("Cancel") }
                    }
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
                            TextButton(onClick = { viewModel.restoreBook(book.id) }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.playbackSection(
    skipForwardMs: Long,
    skipBackMs: Long,
    defaultSpeed: Float,
    autoRewindSeconds: Int,
    autoRewindThresholdMinutes: Int,
    skipSilenceMinMs: Long,
    skipSilenceThreshold: Int,
    viewModel: SettingsViewModel
) {
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Skip forward", style = MaterialTheme.typography.titleSmall)
                IntervalChips(
                    options = listOf(10_000L, 15_000L, 30_000L, 60_000L),
                    selected = skipForwardMs,
                    onSelect = { viewModel.setSkipForward(it) }
                )
                Text("Skip back", style = MaterialTheme.typography.titleSmall)
                IntervalChips(
                    options = listOf(5_000L, 10_000L, 15_000L, 30_000L),
                    selected = skipBackMs,
                    onSelect = { viewModel.setSkipBack(it) }
                )

                var speedSlider by remember(defaultSpeed) { mutableFloatStateOf(defaultSpeed) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Default speed", style = MaterialTheme.typography.titleSmall)
                    Text("${"%.2f".format(speedSlider)}×",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = speedSlider,
                    onValueChange = { raw -> speedSlider = (raw / 0.05f).roundToInt() * 0.05f },
                    onValueChangeFinished = { viewModel.setDefaultSpeed(speedSlider) },
                    valueRange = 0.5f..3.0f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Auto-rewind", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Rewind when resuming after a long pause",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Trigger threshold slider (0 = off, 1–30 min)
                var thresholdSlider by remember(autoRewindThresholdMinutes) {
                    mutableFloatStateOf(autoRewindThresholdMinutes.toFloat())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Trigger after", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (thresholdSlider == 0f) "Off" else "${thresholdSlider.toInt()} min",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            val v = (thresholdSlider - 1).coerceAtLeast(0f)
                            thresholdSlider = v
                            viewModel.setAutoRewindThresholdMinutes(v.toInt())
                        },
                        modifier = Modifier.size(32.dp)
                    ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                    Slider(
                        value = thresholdSlider,
                        onValueChange = { thresholdSlider = it.toInt().toFloat() },
                        onValueChangeFinished = { viewModel.setAutoRewindThresholdMinutes(thresholdSlider.toInt()) },
                        valueRange = 0f..30f,
                        steps = 29,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val v = (thresholdSlider + 1).coerceAtMost(30f)
                            thresholdSlider = v
                            viewModel.setAutoRewindThresholdMinutes(v.toInt())
                        },
                        modifier = Modifier.size(32.dp)
                    ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                }

                // Rewind amount slider (0–90 sec)
                if (autoRewindThresholdMinutes > 0) {
                    var rewindSlider by remember(autoRewindSeconds) {
                        mutableFloatStateOf(autoRewindSeconds.toFloat())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Rewind amount", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${rewindSlider.toInt()} sec",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = {
                                val v = (rewindSlider - 1).coerceAtLeast(0f)
                                rewindSlider = v
                                viewModel.setAutoRewindSeconds(v.toInt())
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                        Slider(
                            value = rewindSlider,
                            onValueChange = { rewindSlider = it.toInt().toFloat() },
                            onValueChangeFinished = { viewModel.setAutoRewindSeconds(rewindSlider.toInt()) },
                            valueRange = 0f..90f,
                            steps = 89,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                val v = (rewindSlider + 1).coerceAtMost(90f)
                                rewindSlider = v
                                viewModel.setAutoRewindSeconds(v.toInt())
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Skip silence", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Tune how silence is detected. Enable per book from the player.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Minimum silence length before trimming (0.2–3.0 s)
                var minSlider by remember(skipSilenceMinMs) { mutableFloatStateOf(skipSilenceMinMs / 1000f) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Minimum silence", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${"%.1f".format(minSlider)} s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = minSlider,
                    onValueChange = { minSlider = (it / 0.1f).roundToInt() * 0.1f },
                    onValueChangeFinished = { viewModel.setSkipSilenceMinMs((minSlider * 1000).toLong()) },
                    valueRange = 0.2f..3.0f,
                    steps = 27,
                    modifier = Modifier.fillMaxWidth()
                )

                // Sensitivity (PCM threshold level). Higher slider = more aggressive trimming.
                var sensSlider by remember(skipSilenceThreshold) { mutableFloatStateOf(skipSilenceThreshold.toFloat()) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivity", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${sensSlider.toInt()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = sensSlider,
                    onValueChange = { sensSlider = it },
                    onValueChangeFinished = { viewModel.setSkipSilenceThreshold(sensSlider.toInt()) },
                    valueRange = 256f..4096f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Changes apply the next time playback starts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    item {
        CardContainer {
            NavRow(
                icon = Icons.Default.Tune,
                label = "Audio Presets — open via Tune button in player",
                onClick = {}
            )
        }
    }
}

private fun LazyListScope.aiSection(geminiApiKey: String, viewModel: SettingsViewModel) {
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.AutoAwesome, MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Gemini AI synopses", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Generated when a book is first opened",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                var apiKeyInput by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Google AI API Key") },
                    placeholder = { Text("AIza...") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    trailingIcon = {
                        if (apiKeyInput != geminiApiKey) {
                            TextButton(onClick = { viewModel.setGeminiApiKey(apiKeyInput) }) {
                                Text("Save")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Get a free key at aistudio.google.com",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun LazyListScope.updatesSection(
    updateState: UpdateUiState,
    whatsNew: WhatsNewState,
    viewModel: SettingsViewModel
) {
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.SystemUpdateAlt, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("App updates", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Current: ${viewModel.currentVersion} (build ${viewModel.currentVersionCode})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!updateState.downloading) {
                        Button(
                            onClick = { viewModel.checkForUpdate() },
                            enabled = !updateState.checking,
                            shape = Pill,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (updateState.checking) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Check")
                            }
                        }
                    }
                }

                when {
                    updateState.upToDate && !updateState.checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(8.dp))
                            Text("You're up to date", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    updateState.available != null -> {
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("v${updateState.available!!.versionName} available",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                if (updateState.available!!.releaseNotes.isNotEmpty()) {
                                    Text(updateState.available!!.releaseNotes,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                                }
                                if (updateState.downloading) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LinearProgressIndicator(
                                            progress = { updateState.downloadProgress / 100f },
                                            modifier = Modifier.fillMaxWidth())
                                        Text("Downloading… ${updateState.downloadProgress}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    }
                                } else {
                                    Button(onClick = { viewModel.downloadAndInstall() },
                                        modifier = Modifier.fillMaxWidth(), shape = Pill) {
                                        Text("Download & Install")
                                    }
                                }
                            }
                        }
                    }
                    updateState.error != null -> {
                        Text(updateState.error!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.NewReleases, MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (whatsNew.version.isNotEmpty()) "Version ${whatsNew.version}" else "Latest release",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                when {
                    whatsNew.loading -> {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    whatsNew.notes.isNotEmpty() -> {
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(whatsNew.notes, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(12.dp))
                        }
                    }
                    whatsNew.error -> {
                        Text("Could not load release notes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.aboutSection(updateState: UpdateUiState, viewModel: SettingsViewModel) {
    item {
        SettingsCard(
            icon = Icons.Default.MusicNote,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Voyage",
            subtitle = "Version ${viewModel.currentVersion} (build ${viewModel.currentVersionCode})"
        )
    }

    // Update check — moved here from the Updates sub-page
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Default.SystemUpdateAlt, MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Updates", style = MaterialTheme.typography.titleSmall)
                    }
                    if (!updateState.downloading) {
                        Button(
                            onClick = { viewModel.checkForUpdate() },
                            enabled = !updateState.checking,
                            shape = Pill,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            if (updateState.checking) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary)
                            } else { Text("Check") }
                        }
                    }
                }
                when {
                    updateState.upToDate && !updateState.checking -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(8.dp))
                            Text("You're up to date", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    updateState.available != null -> {
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("v${updateState.available!!.versionName} available",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                                if (updateState.downloading) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        LinearProgressIndicator(
                                            progress = { updateState.downloadProgress / 100f },
                                            modifier = Modifier.fillMaxWidth())
                                        Text("Downloading… ${updateState.downloadProgress}%",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                                    }
                                } else {
                                    Button(onClick = { viewModel.downloadAndInstall() },
                                        modifier = Modifier.fillMaxWidth(), shape = Pill) {
                                        Text("Download & Install")
                                    }
                                }
                            }
                        }
                    }
                    updateState.error != null -> {
                        Text(updateState.error!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // Changelog for this channel — parsed into styled version/category cards.
    if (viewModel.changelog.isNotBlank()) {
        item { SectionHeader("What's new in this build") }
        item { ChangelogView(viewModel.changelog) }
    }

    item { SectionHeader("Expected Folder Structure") }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Voyage detects audiobooks by folder structure. Each folder that directly contains audio files becomes one book. Folders containing only sub-folders are treated as series.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FolderStructureExample()
            }
        }
    }
}

// ─── Diagnostics (in-app log) ─────────────────────────────────────────────────

private fun LazyListScope.diagnosticsSection(context: Context) {
    item {
        var logText by remember { mutableStateOf(AppLog.recentText()) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Diagnostics log")
            Text(
                "A rolling record of what the app does — playback, scans, navigation, errors and crashes. When something goes wrong, copy or share this and send it over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(shape = Pill, onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Voyage log", AppLog.recentText()))
                }) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Copy")
                }
                FilledTonalButton(shape = Pill, onClick = { shareLog(context) }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Share")
                }
                FilledTonalButton(shape = Pill, onClick = {
                    AppLog.clear(); logText = ""
                }) {
                    Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Clear")
                }
                IconButton(onClick = { logText = AppLog.recentText() }) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
            }
            CardContainer {
                Text(
                    logText.ifBlank { "(empty)" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

private fun shareLog(context: Context) {
    val source = AppLog.logFile() ?: return
    val dir = source.parentFile ?: return
    val shareFile = File(dir, "voyage-log.txt")
    try { shareFile.writeText(AppLog.recentText()) } catch (_: Throwable) { return }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", shareFile)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Voyage diagnostics log")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

// ─── Reusable settings building blocks ────────────────────────────────────────

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CardContainer(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    subtitleMono: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val base = Modifier.fillMaxWidth()
    val clickModifier = if (onClick != null) base.pressScale().clickable(onClick = onClick) else base
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = clickModifier
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(icon, iconTint)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (subtitleMono) FontFamily.Monospace else null,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

@Composable
private fun FolderStructureExample() {
    val lines = listOf(
        "📁 Audiobooks/" to 0,
        "📁 Standalone Book/" to 1,
        "🎵 chapter01.mp3" to 2,
        "🎵 chapter02.mp3" to 2,
        "📁 The Witcher/" to 1,
        "📁 1 - Blood of Elves/" to 2,
        "🎵 01.mp3" to 3,
        "📁 2 - Time of Contempt/" to 2,
        "🎵 01.mp3" to 3
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            lines.forEach { (label, depth) ->
                Text(
                    text = "    ".repeat(depth) + label,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Supported: mp3, m4a, m4b, ogg, flac, aac, opus, wav",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntervalChips(
    options: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { ms ->
            FilterChip(
                selected = selected == ms,
                onClick = { onSelect(ms) },
                label = { Text("${ms / 1000}s") },
                shape = Pill
            )
        }
    }
}

private fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun allFilesAccessIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }
