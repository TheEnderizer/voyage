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

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. Every section content builder below (`rootSection`, `themeSection`,
 *  `librarySection`, etc.) stays shared/unsplit: none of them branch on theme, they're called
 *  identically by both variant top composables. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenWidgetGallery: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> com.betteraudio.ui.immersive.settings.SettingsScreen(
            onBack, onOpenWidgetGallery, viewModel
        )
        AppTheme.MATERIAL_YOU -> com.betteraudio.ui.material.settings.SettingsScreen(
            onBack, onOpenWidgetGallery, viewModel
        )
    }
}

// ─── Section content blocks ───────────────────────────────────────────────────

internal fun LazyListScope.rootSection(viewModel: SettingsViewModel) {
    val rows = listOf(
        Triple(Icons.Default.Palette, "Theme", SettingsSection.Theme),
        Triple(Icons.Default.Folder, "Library", SettingsSection.Library),
        Triple(Icons.Default.Speed, "Playback", SettingsSection.Playback),
        Triple(Icons.Default.Tune, "Audio presets", SettingsSection.Presets),
        Triple(Icons.Default.Widgets, "Widget", SettingsSection.Widget),
        Triple(Icons.Default.AutoAwesome, "AI Synopsis", SettingsSection.AI),
        Triple(Icons.Default.Download, "Backup & restore", SettingsSection.Backup),
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

// ─── Theme ────────────────────────────────────────────────────────────────────

// Curated accent swatches for the custom-color picker (kept small and self-contained rather than
// porting ArchiveTune's full ~68-preset Theme Creator screen).
internal val CUSTOM_COLOR_PRESETS = listOf(
    0xFFFFA552, 0xFFED5564, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5,
    0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4, 0xFF009688, 0xFF4CAF50, 0xFF8BC34A,
    0xFFCDDC39, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
).map { androidx.compose.ui.graphics.Color(it.toInt()) }

internal fun LazyListScope.themeSection(
    appTheme: com.betteraudio.ui.theme.AppTheme,
    colorSource: com.betteraudio.ui.theme.ThemeColorSource,
    customThemeColor: String,
    darkMode: com.betteraudio.ui.theme.DarkMode,
    pureBlack: Boolean,
    dynamicPills: Boolean,
    viewModel: SettingsViewModel
) {
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("App theme", style = MaterialTheme.typography.titleSmall)
                com.betteraudio.ui.components.THEME_OPTIONS.forEach { opt ->
                    com.betteraudio.ui.components.ThemeOptionRow(
                        opt = opt,
                        selected = appTheme == opt.theme,
                        onSelect = { viewModel.setAppTheme(opt.theme) }
                    )
                }
            }
        }
    }

    // Colour source only applies to the Material You theme (Immersive always follows the cover).
    item {
        AnimatedVisibility(visible = appTheme == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU) {
            var showColorPicker by remember { mutableStateOf(false) }
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Color source", style = MaterialTheme.typography.titleSmall)
                    val wallpaperAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    if (wallpaperAvailable) {
                        ThemeRadioRow(
                            title = "System wallpaper",
                            detail = "Dynamic colors from your wallpaper (Material You).",
                            selected = colorSource == com.betteraudio.ui.theme.ThemeColorSource.WALLPAPER,
                            onSelect = { viewModel.setThemeColorSource(com.betteraudio.ui.theme.ThemeColorSource.WALLPAPER) }
                        )
                    } else {
                        Text(
                            "Wallpaper colors need Android 12+.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ThemeRadioRow(
                        title = "Book cover",
                        detail = "Colors from the playing book's cover art.",
                        selected = colorSource == com.betteraudio.ui.theme.ThemeColorSource.COVER,
                        onSelect = { viewModel.setThemeColorSource(com.betteraudio.ui.theme.ThemeColorSource.COVER) }
                    )
                    ThemeRadioRow(
                        title = "Custom color",
                        detail = "Pick a fixed accent color for the whole app.",
                        selected = colorSource == com.betteraudio.ui.theme.ThemeColorSource.CUSTOM,
                        onSelect = {
                            viewModel.setThemeColorSource(com.betteraudio.ui.theme.ThemeColorSource.CUSTOM)
                            showColorPicker = true
                        }
                    )
                    if (colorSource == com.betteraudio.ui.theme.ThemeColorSource.CUSTOM) {
                        TextButton(onClick = { showColorPicker = true }) { Text("Change custom color") }
                    }
                }
            }
            if (showColorPicker) {
                CustomThemeColorDialog(
                    current = customThemeColor,
                    onSelect = { hex -> viewModel.setCustomThemeColor(hex) },
                    onDismiss = { showColorPicker = false }
                )
            }
        }
    }

    // Dark mode + pure black apply to both looks (Immersive's own backdrop is the blurred cover,
    // so pure black there would have no visible effect — hidden unless Material You is active).
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Dark mode", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        com.betteraudio.ui.theme.DarkMode.AUTO to "Auto",
                        com.betteraudio.ui.theme.DarkMode.ON to "On",
                        com.betteraudio.ui.theme.DarkMode.OFF to "Off",
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = darkMode == mode,
                            onClick = { viewModel.setDarkMode(mode) },
                            label = { Text(label) }
                        )
                    }
                }
                AnimatedVisibility(visible = appTheme == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Pure black", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "AMOLED-black surfaces when dark.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = pureBlack, onCheckedChange = { viewModel.setPureBlack(it) })
                    }
                }
            }
        }
    }

    // Immersive only — Material You has no "glass" pill for this to affect.
    item {
        AnimatedVisibility(visible = appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE) {
            CardContainer {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dynamic pills", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "The mini player and nav pill's glass effect samples whichever book " +
                                "cover is currently scrolled underneath them, instead of the " +
                                "now-playing cover.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = dynamicPills, onCheckedChange = { viewModel.setDynamicPills(it) })
                }
            }
        }
    }
}

@Composable
private fun CustomThemeColorDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    var hexInput by remember(current) {
        mutableStateOf(current.takeIf { it.startsWith("#") } ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRowSwatches(current = current, onPick = { color ->
                    val hex = String.format("#%08X", color.toArgb())
                    hexInput = hex
                    onSelect(hex)
                    onDismiss()
                })
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { hexInput = it },
                    label = { Text("Hex (#AARRGGBB or #RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = hexInput.trim().let { if (it.startsWith("#")) it else "#$it" }
                if (runCatching { android.graphics.Color.parseColor(normalized) }.isSuccess) {
                    onSelect(normalized)
                    onDismiss()
                }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FlowRowSwatches(current: String, onPick: (androidx.compose.ui.graphics.Color) -> Unit) {
    val rows = CUSTOM_COLOR_PRESETS.chunked(8)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { color ->
                    val selected = current == String.format("#%08X", color.toArgb())
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(color)
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = androidx.compose.foundation.shape.CircleShape
                            )
                            .clickable { onPick(color) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeRadioRow(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

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
        SettingsCard(
            icon = Icons.Default.MenuBook,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Ebook folder",
            subtitle = ebookFolder.ifBlank { "Not set — standalone ebooks live here" },
            subtitleMono = ebookFolder.isNotBlank(),
            onClick = onBrowseEbooks
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

internal fun LazyListScope.playbackSection(
    skipForwardMs: Long,
    skipBackMs: Long,
    autoRewindSeconds: Int,
    autoRewindThresholdMinutes: Int,
    skipSilenceMinMs: Long,
    skipSilenceThreshold: Int,
    skipSilencePaddingMs: Long,
    sleepFadeSeconds: Int,
    sleepShakeEnabled: Boolean,
    sleepShakeResetMinutes: Int,
    sleepScheduleEnabled: Boolean,
    sleepScheduleStartMinutes: Int,
    sleepScheduleEndMinutes: Int,
    sleepScheduleDefaultMinutes: Int,
    headsetMultiPressEnabled: Boolean,
    headsetDoublePressAction: String,
    headsetTriplePressAction: String,
    btAutoResumeEnabled: Boolean,
    btAutoResumeWindowMinutes: Int,
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

                Text("Default speed, boost & EQ", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The default audio for every book now comes from your global default preset. " +
                        "A book keeps its own settings if you change them individually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = { viewModel.navigateTo(SettingsSection.Presets) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage audio presets")
                }
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
            com.betteraudio.ui.player.SkipSilenceControls(
                minMs = skipSilenceMinMs,
                threshold = skipSilenceThreshold,
                paddingMs = skipSilencePaddingMs,
                onSetMinMs = { viewModel.setSkipSilenceMinMs(it) },
                onSetThreshold = { viewModel.setSkipSilenceThreshold(it) },
                onSetPaddingMs = { viewModel.setSkipSilencePaddingMs(it) },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Sleep timer", style = MaterialTheme.typography.titleSmall)
                com.betteraudio.ui.player.SleepTimerTuningControls(
                    fadeSeconds = sleepFadeSeconds,
                    shakeEnabled = sleepShakeEnabled,
                    shakeResetMinutes = sleepShakeResetMinutes,
                    scheduleEnabled = sleepScheduleEnabled,
                    scheduleStartMinutes = sleepScheduleStartMinutes,
                    scheduleEndMinutes = sleepScheduleEndMinutes,
                    scheduleDefaultMinutes = sleepScheduleDefaultMinutes,
                    onSetFadeSeconds = { viewModel.setSleepFadeSeconds(it) },
                    onSetShakeEnabled = { viewModel.setSleepShakeEnabled(it) },
                    onSetShakeResetMinutes = { viewModel.setSleepShakeResetMinutes(it) },
                    onSetScheduleEnabled = { viewModel.setSleepScheduleEnabled(it) },
                    onSetScheduleStartMinutes = { viewModel.setSleepScheduleStartMinutes(it) },
                    onSetScheduleEndMinutes = { viewModel.setSleepScheduleEndMinutes(it) },
                    onSetScheduleDefaultMinutes = { viewModel.setSleepScheduleDefaultMinutes(it) }
                )
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Headset button mapping", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Double/triple-click the headset button for extra actions. Adds a short delay to every single press to tell them apart — behavior varies by headset.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = headsetMultiPressEnabled, onCheckedChange = { viewModel.setHeadsetMultiPressEnabled(it) })
                }
                if (headsetMultiPressEnabled) {
                    HeadsetActionPicker(
                        label = "Double press",
                        selected = headsetDoublePressAction,
                        onSelect = { viewModel.setHeadsetDoublePressAction(it) }
                    )
                    HeadsetActionPicker(
                        label = "Triple press",
                        selected = headsetTriplePressAction,
                        onSelect = { viewModel.setHeadsetTriplePressAction(it) }
                    )
                }
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Resume on headphones", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Automatically resume a paused book when headphones or a Bluetooth speaker connect, if it was paused recently. Only while the app is still running in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = btAutoResumeEnabled, onCheckedChange = { viewModel.setBtAutoResumeEnabled(it) })
                }
                if (btAutoResumeEnabled) {
                    var windowSlider by remember(btAutoResumeWindowMinutes) { mutableFloatStateOf(btAutoResumeWindowMinutes.toFloat()) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Within", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${windowSlider.toInt()} min of pausing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = windowSlider,
                        onValueChange = { windowSlider = it.toInt().toFloat() },
                        onValueChangeFinished = { viewModel.setBtAutoResumeWindowMinutes(windowSlider.toInt().coerceAtLeast(1)) },
                        valueRange = 1f..120f,
                        steps = 118,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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

private val HEADSET_ACTIONS = listOf(
    "play_pause" to "Play / Pause",
    "skip_forward" to "Skip forward",
    "skip_back" to "Skip back",
    "next_chapter" to "Next chapter",
    "prev_chapter" to "Previous chapter",
    "bookmark" to "Add bookmark",
    "none" to "Nothing",
)

@Composable
private fun HeadsetActionPicker(label: String, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = HEADSET_ACTIONS.firstOrNull { it.first == selected }?.second ?: "Nothing"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                HEADSET_ACTIONS.forEach { (value, actionLabel) ->
                    DropdownMenuItem(
                        text = { Text(actionLabel) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

internal fun formatClockMinutes(totalMinutes: Int): String {
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return "%02d:%02d".format(h, m)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ClockMinutesPickerDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = true
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

internal fun LazyListScope.aiSection(geminiApiKey: String, viewModel: SettingsViewModel) {
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
    item {
        val modelState by viewModel.voskModelState.collectAsStateWithLifecycle()
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.GraphicEq, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Listen ↔ read sync model", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "On-device speech model for paragraph-accurate ebook sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                when (val s = modelState) {
                    is com.betteraudio.data.transcribe.ModelState.Ready -> {
                        Text("Downloaded · ${"%.0f".format(s.sizeBytes / 1_000_000.0)} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { viewModel.deleteVoskModel() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Delete model")
                        }
                    }
                    is com.betteraudio.data.transcribe.ModelState.Downloading -> {
                        Text("Downloading… ${s.pct}%", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    com.betteraudio.data.transcribe.ModelState.Unzipping ->
                        Text("Preparing…", style = MaterialTheme.typography.bodySmall)
                    is com.betteraudio.data.transcribe.ModelState.Error -> {
                        Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        FilledTonalButton(onClick = { viewModel.downloadVoskModel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Retry download (~45 MB)")
                        }
                    }
                    com.betteraudio.data.transcribe.ModelState.NotDownloaded ->
                        FilledTonalButton(onClick = { viewModel.downloadVoskModel() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Download model (~45 MB, English)")
                        }
                }
            }
        }
    }
}

internal fun LazyListScope.updatesSection(
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

internal fun LazyListScope.aboutSection(updateState: UpdateUiState, viewModel: SettingsViewModel) {
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
                        Switch(checked = includeApiKey, onCheckedChange = { viewModel.setBackupIncludeApiKey(it) })
                    }
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
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
                        FilledTonalButton(
                            shape = Pill,
                            enabled = !backupState.importing,
                            onClick = { importLauncher.launch(arrayOf("application/json")) }
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (backupState.importing) "Importing…" else "Import")
                        }
                        FilledTonalButton(shape = Pill, onClick = {
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
                        Switch(
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
                    OutlinedButton(shape = Pill, onClick = { folderLauncher.launch(null) }) {
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
                        TextButton(onClick = { viewModel.runAutoBackupNow() }) { Text("Back up now") }
                    }
                }
            }
        }

        backupState.error?.let { error ->
            AlertDialog(
                onDismissRequest = { viewModel.clearBackupResult() },
                title = { Text("Backup error") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { viewModel.clearBackupResult() }) { Text("OK") } }
            )
        }
        shareError?.let { error ->
            AlertDialog(
                onDismissRequest = { shareError = null },
                title = { Text("Share failed") },
                text = { Text(error) },
                confirmButton = { TextButton(onClick = { shareError = null }) { Text("OK") } }
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
                confirmButton = { TextButton(onClick = { viewModel.clearBackupResult() }) { Text("OK") } },
                dismissButton = if (result.booksSkippedStale > 0 && lastImportUri != null) {
                    {
                        TextButton(onClick = {
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

// ─── Diagnostics (in-app log) ─────────────────────────────────────────────────

internal fun LazyListScope.diagnosticsSection(context: Context, viewModel: SettingsViewModel) {
    item {
        val enableFileLogging by viewModel.enableFileLogging.collectAsStateWithLifecycle()
        CardContainer {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Save log to file", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Off by default. Turn on before reproducing a bug so the log below has something in it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enableFileLogging, onCheckedChange = { viewModel.setEnableFileLogging(it) })
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
        var logText by remember { mutableStateOf(AppLog.recentText()) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader("Diagnostics log")
            Text(
                "A rolling record of what the app does — playback, scans, navigation, errors and crashes. When something goes wrong, copy or share this and send it over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
// Used across nearly every (unsplit) section function below, so — unlike the split screens —
// these stay in one place and resolve their own card color per theme, the same way the old
// shared `appCardColor()` helper did.

@Composable
private fun settingsCardColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) ImmersiveStyle.cardColor() else MaterialStyle.cardColor()

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
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
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
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

// ─── Audio presets (unified bundles + global default) ──────────────────────────

internal fun LazyListScope.presetsSection(
    presets: List<com.betteraudio.data.db.entities.AudioPreset>,
    viewModel: SettingsViewModel
) {
    item {
        var creating by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<com.betteraudio.data.db.entities.AudioPreset?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Global default", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The starred preset sets the default speed, volume boost & EQ for every book " +
                            "in your library (including new ones). A book you tune individually keeps its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (presets.isEmpty()) {
                Text(
                    "No presets yet. Create one below, or save the current speed/boost/EQ from the " +
                        "player's audio panel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onToggleDefault = {
                            if (preset.isDefault) viewModel.clearDefaultPreset()
                            else viewModel.setDefaultPreset(preset.id)
                        },
                        onEdit = { editing = preset },
                        onDelete = { viewModel.deletePreset(preset.id) }
                    )
                }
            }

            FilledTonalButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New preset")
            }
        }

        if (creating) {
            PresetEditorDialog(
                initial = null,
                onDismiss = { creating = false },
                onSave = { viewModel.savePreset(it); creating = false }
            )
        }
        editing?.let { preset ->
            PresetEditorDialog(
                initial = preset,
                onDismiss = { editing = null },
                onSave = { viewModel.savePreset(it); editing = null }
            )
        }
    }
}

@Composable
private fun PresetRow(
    preset: com.betteraudio.data.db.entities.AudioPreset,
    onToggleDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preset.summary(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleDefault) {
                Icon(
                    if (preset.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                    if (preset.isDefault) "Default preset" else "Set as default",
                    tint = if (preset.isDefault) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit", Modifier.size(20.dp)) }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, "Delete", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete preset?") },
            text = { Text("\"${preset.name}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private val PRESET_EQ_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

@Composable
private fun PresetEditorDialog(
    initial: com.betteraudio.data.db.entities.AudioPreset?,
    onDismiss: () -> Unit,
    onSave: (com.betteraudio.data.db.entities.AudioPreset) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var speed by remember { mutableFloatStateOf(initial?.speedMult ?: 1.0f) }
    var boost by remember { mutableIntStateOf(initial?.boostDb ?: 0) }
    val eq = remember {
        val arr = IntArray(5) { 0 }
        initial?.eqBandsJson?.let { json ->
            runCatching {
                val a = org.json.JSONArray(json)
                for (i in 0 until minOf(a.length(), 5)) arr[i] = a.getInt(i)
            }
        }
        androidx.compose.runtime.mutableStateListOf(*arr.toTypedArray())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New preset" else "Edit preset") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Speed
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed", style = MaterialTheme.typography.labelLarge)
                    Text("${"%.2f".format(speed)}×", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = speed,
                    onValueChange = { speed = (it / 0.05f).roundToInt() * 0.05f },
                    valueRange = 0.5f..3.0f, steps = 49
                )
                // Boost
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Volume boost", style = MaterialTheme.typography.labelLarge)
                    Text("+$boost dB", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = boost.toFloat(),
                    onValueChange = { boost = it.roundToInt() },
                    valueRange = 0f..24f, steps = 23
                )
                // EQ
                Text("Equalizer", style = MaterialTheme.typography.labelLarge)
                PRESET_EQ_LABELS.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        Slider(
                            value = eq[i].toFloat(),
                            onValueChange = { eq[i] = it.roundToInt() },
                            valueRange = -1500f..1500f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${if (eq[i] >= 0) "+" else ""}${"%.1f".format(eq[i] / 100f)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                TextButton(onClick = { for (i in 0 until 5) eq[i] = 0 }) { Text("Flat EQ") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val hasEq = eq.any { it != 0 }
                    val json = if (hasEq) org.json.JSONArray(eq.toList()).toString() else null
                    onSave(
                        (initial ?: com.betteraudio.data.db.entities.AudioPreset(name = "")).copy(
                            name = name.trim(),
                            type = com.betteraudio.data.db.entities.AudioPreset.TYPE_BUNDLE,
                            speedMult = speed,
                            boostDb = boost,
                            eqBandsJson = json
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Widget ─────────────────────────────────────────────────────────────────────

internal fun LazyListScope.widgetSection(
    currentCoverPath: String,
    hideWhenIdle: Boolean,
    onOpenWidgetGallery: () -> Unit,
    viewModel: SettingsViewModel
) {
    item {
        NavRow(Icons.Default.Widgets, "Widget designs", onOpenWidgetGallery)
    }

    item {
        CardContainer {
            Row(
                Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Hide widgets when nothing is playing", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Custom widgets show no elements while idle, instead of a cold play button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = hideWhenIdle, onCheckedChange = viewModel::setWidgetHideWhenIdle)
            }
        }
    }

    item {
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia()
        ) { uri -> if (uri != null) viewModel.setWidgetDefaultCover(uri) }

        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Default cover", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Shown on the home-screen widget when nothing is playing. Defaults to the app " +
                        "placeholder if unset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    Modifier.size(96.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentCoverPath.isNotBlank()) {
                        coil.compose.AsyncImage(
                            model = java.io.File(currentCoverPath),
                            contentDescription = "Widget default cover",
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(Icons.Default.Image, null, Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = {
                        picker.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }) { Text(if (currentCoverPath.isBlank()) "Choose image" else "Change") }
                    if (currentCoverPath.isNotBlank()) {
                        TextButton(onClick = { viewModel.clearWidgetDefaultCover() }) { Text("Remove") }
                    }
                }
            }
        }
    }
}


internal fun hasAllFilesAccess(): Boolean =
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
