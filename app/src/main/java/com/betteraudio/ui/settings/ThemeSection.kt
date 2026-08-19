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
    appIcon: com.betteraudio.util.AppIconManager.AppIcon,
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

    // ── App icon (see util/AppIconManager.kt) ───────────────────────────────
    item {
        var pendingIcon by remember { mutableStateOf<com.betteraudio.util.AppIconManager.AppIcon?>(null) }
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("App icon", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    com.betteraudio.util.AppIconManager.AppIcon.entries.forEach { candidate ->
                        AppIconPreview(
                            icon = candidate,
                            selected = candidate == appIcon,
                            onClick = { if (candidate != appIcon) pendingIcon = candidate }
                        )
                    }
                }
                // Always visible, not just inside the confirm dialog — the user should know what
                // tapping a variant commits to before they even tap one.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Filled.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Text(
                        "Changing the icon closes Voyage. Your place in the current book is " +
                            "saved first. On some phones the new icon only appears after " +
                            "restarting the phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        pendingIcon?.let { target ->
            AppIconConfirmDialog(
                target = target,
                isPlaying = viewModel.isPlaying(),
                onConfirm = {
                    pendingIcon = null
                    viewModel.changeAppIcon(target)
                },
                onDismiss = { pendingIcon = null }
            )
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

    // Landscape player layout — Material You only (the Immersive player has a single landscape
    // layout). Portrait is unaffected by this choice either way.
    item {
        AnimatedVisibility(visible = appTheme == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU) {
            val style by viewModel.landscapePlayerStyle.collectAsStateWithLifecycle()
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Landscape player", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "How the full player lays out when you turn the phone sideways. " +
                            "Portrait is the same either way.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    com.betteraudio.ui.material.player.LandscapePlayerStyle.entries.forEach { opt ->
                        ThemeRadioRow(
                            title = opt.label,
                            detail = opt.blurb,
                            selected = style == opt,
                            onSelect = { viewModel.setLandscapePlayerStyle(opt) }
                        )
                    }
                }
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
                            "The mini player and nav pill blur whatever is actually behind them, " +
                                "updating live as the library scrolls underneath. Off, they show " +
                                "a fixed smudge of the now-playing cover instead.",
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
private fun AppIconPreview(
    icon: com.betteraudio.util.AppIconManager.AppIcon,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(64.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    else Modifier
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(Color(icon.previewTopColor), Color(icon.previewBottomColor))
                        )
                    )
            )
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(icon.previewForeground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            icon.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppIconConfirmDialog(
    target: com.betteraudio.util.AppIconManager.AppIcon,
    isPlaying: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change app icon?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Switching to \"${target.label}\" closes Voyage. Your place in the current " +
                        "book is saved first. On some phones the new icon only appears after " +
                        "restarting the phone."
                )
                if (isPlaying) {
                    Text(
                        "Playback will stop.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Change icon") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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

