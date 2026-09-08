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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
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
import com.betteraudio.ui.components.ColorPickerPanel
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
                        HapticTextButton(onClick = { showColorPicker = true }) { Text("Change custom color") }
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

    // The manual accent pin. Only meaningful where an automatic cover pick is what's on screen:
    // Immersive always themes from the cover, and Material You does so on its "Book cover" source.
    // On wallpaper or a custom colour there is no cover-derived accent to disagree with.
    item {
        val coverAccentVisible = appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE ||
            (appTheme == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU &&
                colorSource == com.betteraudio.ui.theme.ThemeColorSource.COVER)
        AnimatedVisibility(visible = coverAccentVisible) {
            CoverAccentCard(viewModel)
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
                        HapticFilterChip(
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
                        HapticSwitch(checked = pureBlack, onCheckedChange = { viewModel.setPureBlack(it) })
                    }
                }
            }
        }
    }

    // Haptics apply to both looks, so this card never hides.
    item {
        val hapticStrength by viewModel.hapticStrength.collectAsStateWithLifecycle()
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Haptics", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Voyage answers a touch with a short vibration chosen to match what happened " +
                        "\u2014 a tick for a tap, a different one each way for a switch, detents " +
                        "while you drag, and something fuller when a change actually sticks.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.betteraudio.ui.haptics.HapticStrength.entries.forEach { opt ->
                    ThemeRadioRow(
                        title = opt.label,
                        detail = opt.blurb,
                        selected = hapticStrength == opt,
                        onSelect = { viewModel.setHapticStrength(opt) }
                    )
                }
                Text(
                    "Your phone's own haptics setting still wins: with vibration off in Android, " +
                        "Voyage stays quiet whatever is chosen here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Both looks: which seek bar the player draws. Every design is offered in both — the four
    // painted ones were Immersive-only for no reason either theme could point at, and the Material
    // slider Material You always drew is now simply the first option rather than the only one.
    // Each look keeps its own pick (and so its own shipped default), which is why the card writes
    // to whichever preference matches the theme that is active. Every option previews itself with
    // the player's own drawing code (ScrubberArt.drawScrubber), so the picture cannot go stale.
    item {
        val immersiveSeekBar = appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE
        val scrubberStyle by (
            if (immersiveSeekBar) viewModel.scrubberStyle else viewModel.scrubberStyleMaterial
            ).collectAsStateWithLifecycle()
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Seek bar", style = MaterialTheme.typography.titleSmall)
                Text(
                    "How the player draws the progress bar you drag. All five drag and seek the " +
                        "same way \u2014 they differ only in how loudly they state " +
                        "themselves. Each theme remembers its own choice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                com.betteraudio.ui.components.ScrubberStyle.entries.forEach { opt ->
                    ScrubberOptionRow(
                        style = opt,
                        selected = scrubberStyle == opt,
                        onSelect = {
                            if (immersiveSeekBar) viewModel.setScrubberStyle(opt)
                            else viewModel.setScrubberStyleMaterial(opt)
                        }
                    )
                }
            }
        }
    }

    // Immersive only: the mini player cover, and therefore where its progress goes.
    item {
        AnimatedVisibility(visible = appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE) {
            val miniCoverStyle by viewModel.miniCoverStyle.collectAsStateWithLifecycle()
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Mini player cover", style = MaterialTheme.typography.titleSmall)
                    com.betteraudio.ui.player.MiniCoverStyle.entries.forEach { opt ->
                        ThemeRadioRow(
                            title = opt.label,
                            detail = opt.blurb,
                            selected = miniCoverStyle == opt,
                            onSelect = { viewModel.setMiniCoverStyle(opt) }
                        )
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
                    HapticSwitch(checked = dynamicPills, onCheckedChange = { viewModel.setDynamicPills(it) })
                }
            }
        }
    }

    // Immersive only — this darkens the app-wide blurred-cover backdrop, which Material You
    // does not draw at all.
    item {
        AnimatedVisibility(visible = appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE) {
            val backdropDim by viewModel.backdropDim.collectAsStateWithLifecycle()
            // Dragging writes on every frame; DataStore is a suspending write, so the slider
            // tracks the thumb locally and only the committed value goes to disk (on release).
            var dragDim by remember { mutableStateOf<Float?>(null) }
            val shown = dragDim ?: backdropDim
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Backdrop darkening", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${(shown * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "How dark the blurred cover behind the app gets toward the bottom of "  +
                            "the screen. It fades in gradually, the same way the blur does — "  +
                            "all the way off leaves the artwork undimmed, all the way up takes "  +
                            "the lower background to solid black.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HapticSlider(
                        value = shown,
                        onValueChange = { dragDim = it },
                        onValueChangeFinished = {
                            dragDim?.let { viewModel.setBackdropDim(it) }
                            dragDim = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    // ── App icon (see util/AppIconManager.kt) ───────────────────────────────
    item {
        var pendingIcon by remember { mutableStateOf<com.betteraudio.util.AppIconManager.AppIcon?>(null) }
        val haptics = com.betteraudio.ui.haptics.LocalHaptics.current
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("App icon", style = MaterialTheme.typography.titleSmall)
                // Which style's colours are showing. Follows the active icon, so opening the
                // page always lands on the family you are actually using.
                var openStyle by remember(appIcon) {
                    mutableStateOf(appIcon.style)
                }
                // Three marks across the width — not scrollable, and never more than three, so
                // the whole first choice is visible at once.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    com.betteraudio.util.AppIconManager.IconStyle.entries.forEach { style ->
                        IconStyleTile(
                            style = style,
                            // The style's tile previews the icon you actually have when it owns
                            // it, and its first colourway otherwise — so the tile is always a
                            // real picture of what choosing it gives you.
                            preview = if (appIcon.style == style) appIcon else style.defaultIcon(),
                            open = openStyle == style,
                            inUse = appIcon.style == style,
                            onClick = { openStyle = style },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // The chosen style's colourways, wrapping onto as many rows as they need.
                // AnimatedContent's default SizeTransform is what makes the card grow and shrink
                // as you move between styles with different colour counts.
                AnimatedContent(
                    targetState = openStyle,
                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(90)) },
                    label = "appIconColors"
                ) { style ->
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        style.icons().forEach { candidate ->
                            AppIconPreview(
                                icon = candidate,
                                selected = candidate == appIcon,
                                onClick = { if (candidate != appIcon) pendingIcon = candidate }
                            )
                        }
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
                    haptics.commit()
                    viewModel.changeAppIcon(target)
                },
                onDismiss = { pendingIcon = null }
            )
        }
    }

}

/**
 * Pick the app's accent by eye, not by arithmetic.
 *
 * The dialog used to be presets plus a hex field, which meant every colour between two presets was
 * only reachable by typing its code — so in practice the presets *were* the choice. It now leads
 * with a real picker ([ColorPickerPanel]); the presets stay as shortcuts to known-good seeds, and
 * the hex field stays for pasting a value from somewhere else. All three write the same state, so
 * whichever you touch last is what Apply commits.
 *
 * Nothing is written until Apply. The whole app recolours off this preference, so a live-applying
 * picker would strobe the entire UI under a dragging finger.
 */
@Composable
private fun CustomThemeColorDialog(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    // Seeded from the stored value when it is a plain colour; a preset id or an encoded palette has
    // no single colour to seed from, so those start the picker at the scheme's own primary.
    val fallback = MaterialTheme.colorScheme.primary
    var picked by remember(current) {
        mutableStateOf(
            current.takeIf { it.startsWith("#") }
                ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
                ?: fallback
        )
    }
    var hexInput by remember(current) {
        mutableStateOf(current.takeIf { it.startsWith("#") } ?: "")
    }
    fun commit(color: Color) {
        picked = color
        hexInput = String.format("#%08X", color.toArgb())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom color") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ColorPickerPanel(color = picked, onColorChange = { commit(it) })
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRowSwatches(
                    current = String.format("#%08X", picked.toArgb()),
                    onPick = { commit(it) }
                )
                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { text ->
                        hexInput = text
                        // Typing a valid colour moves the picker with it, so the field and the
                        // field above never disagree about what is selected.
                        val normalized = text.trim().let { if (it.startsWith("#")) it else "#$it" }
                        runCatching { android.graphics.Color.parseColor(normalized) }
                            .getOrNull()?.let { picked = Color(it) }
                    },
                    label = { Text("Hex (#AARRGGBB or #RRGGBB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            HapticTextButton(onClick = {
                onSelect(String.format("#%08X", picked.toArgb()))
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The preset seeds, as tappable circles. [current] is compared as "#AARRGGBB" so the ring
 *  follows whatever the picker is showing, not only an already-applied preference. */
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


/**
 * Lets you overrule the automatic accent with one of the colours Voyage actually sampled out of
 * the cover it is themed from right now. Scoped to that one cover: another book's art has its own
 * sixteen colours, and a choice made against this one would mean nothing there.
 */
/** One scrubber design: what it is called, what it is for, and what it actually looks like. The
 *  preview sits on its own dark strip because the real thing is always drawn white-on-scrim over
 *  the blurred cover \u2014 on a pale settings card its track and bead would read wrongly. */
@Composable
private fun ScrubberOptionRow(
    style: com.betteraudio.ui.components.ScrubberStyle,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(onClick = onSelect)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HapticRadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(style.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    style.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.42f))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            com.betteraudio.ui.components.ScrubberPreview(
                style = style,
                accent = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CoverAccentCard(viewModel: SettingsViewModel) {
    val haptics = com.betteraudio.ui.haptics.LocalHaptics.current
    val coverPath = com.betteraudio.ui.theme.LocalThemeCoverPath.current
    val accents by viewModel.coverAccents.collectAsStateWithLifecycle()
    val swatches = com.betteraudio.ui.theme.rememberCoverSwatches(coverPath)
    val globalAccent by viewModel.globalAccent.collectAsStateWithLifecycle()
    val pinned = coverPath?.let { accents[it] }
    // What the card is showing as chosen. While the all-books override is on it is that colour,
    // whatever this cover's own pin says — the override is what the app is actually painting with,
    // and a card that highlighted a per-cover pin the theme is ignoring would be lying.
    val effective = globalAccent ?: pinned
    var showPicker by remember { mutableStateOf(false) }

    // Whether the chosen colour came off this cover. A colour mixed in the picker will not be in
    // the swatch list, so the custom tile is what carries the tick for it.
    val fromCover = effective != null && swatches.any { it.toArgb() == effective }

    CardContainer {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cover accent", style = MaterialTheme.typography.titleSmall)
            Text(
                when {
                    globalAccent != null ->
                        "One colour for the whole library. Every book uses the colour below until " +
                            "you untick it \u2014 then each cover goes back to its own pick."
                    coverPath == null ->
                        "Start a book and the colours Voyage found in its cover will show up here."
                    swatches.isEmpty() ->
                        "Reading the colours out of the current cover\u2026"
                    pinned == null ->
                        "Voyage is choosing the accent from the current cover on its own. Pick one " +
                            "of its colours \u2014 or mix your own \u2014 to use that instead, " +
                            "useful when the automatic choice keeps landing somewhere you did not " +
                            "want."
                    else ->
                        "Pinned. This cover keeps the colour you picked; every other cover is still " +
                            "chosen automatically."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The row still shows the cover's own colours while the override is on, so the
            // override can be re-pointed at one of them without being turned off first.
            if (coverPath != null && swatches.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AccentSwatch(
                        color = null,
                        selected = effective == null,
                        onClick = {
                            haptics.select()
                            // Automatic means automatic everywhere: leaving the all-books override
                            // set would keep painting one colour and make the tick a lie.
                            viewModel.setGlobalAccent(null)
                            viewModel.setCoverAccent(coverPath, null)
                        }
                    )
                    swatches.forEach { swatch ->
                        val argb = swatch.toArgb()
                        AccentSwatch(
                            color = swatch,
                            selected = effective == argb,
                            onClick = {
                                // Tapping the chosen colour again releases it, so the row never
                                // needs a separate "clear" affordance beyond Auto.
                                val clearing = effective == argb
                                if (clearing) haptics.select() else haptics.commit()
                                if (globalAccent != null) {
                                    viewModel.setGlobalAccent(if (clearing) null else argb)
                                } else {
                                    viewModel.setCoverAccent(coverPath, if (clearing) null else argb)
                                }
                            }
                        )
                    }
                    // Anything at all, not just what this cover happens to contain. A cover with
                    // sixteen muted browns offers no way to say "green" without this.
                    AccentSwatch(
                        color = effective?.takeIf { !fromCover }?.let { Color(it) },
                        selected = effective != null && !fromCover,
                        custom = true,
                        onClick = { haptics.select(); showPicker = true }
                    )
                }
            }
            // Only meaningful once there is a colour to spread. With nothing chosen there is
            // nothing to apply everywhere, so the row would toggle into a no-op.
            if (effective != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HapticCheckbox(
                        checked = globalAccent != null,
                        onCheckedChange = { on ->
                            // Turning it on promotes whatever is chosen right now; turning it off
                            // drops back to this cover's own pin, which was never cleared.
                            viewModel.setGlobalAccent(if (on) effective else null)
                        }
                    )
                    Column(Modifier.weight(1f)) {
                        Text("Use on every book", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "On, the whole library keeps this colour. Off, it belongs to this " +
                                "book's cover alone and every other book is themed from its own.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        val fallback = MaterialTheme.colorScheme.primary
        var draft by remember { mutableStateOf(effective?.let { Color(it) } ?: fallback) }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Accent colour") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ColorPickerPanel(color = draft, onColorChange = { draft = it })
                    Text(
                        "Applies to this book's cover, or to every book when \u201cUse on every " +
                            "book\u201d is ticked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                HapticTextButton(onClick = {
                    val argb = draft.toArgb()
                    haptics.commit()
                    if (globalAccent != null) viewModel.setGlobalAccent(argb)
                    else coverPath?.let { viewModel.setCoverAccent(it, argb) }
                    showPicker = false
                }) { Text("Apply") }
            },
            dismissButton = {
                HapticTextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }
        )
    }
}

/** One tile in the accent row. A null [color] is the Automatic tile, unless [custom] is set — then
 *  it is the "mix your own" tile that has not been used yet. */
@Composable
private fun AccentSwatch(
    color: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    custom: Boolean = false
) {
    val shape = RoundedCornerShape(14.dp)
    // The tick has to sit on the swatch itself, so it takes its colour from that swatch rather
    // than from the scheme — which is mid-transition to this very colour while you tap.
    val markColor = when {
        color == null -> MaterialTheme.colorScheme.onSurfaceVariant
        color.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            color == null -> Icon(
                if (custom) Icons.Default.Palette else Icons.Default.AutoAwesome,
                contentDescription = if (custom) "Custom colour" else "Automatic",
                modifier = Modifier.size(20.dp),
                tint = markColor
            )
            selected -> Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                modifier = Modifier.size(22.dp),
                tint = markColor
            )
        }
    }
}

@Composable
private fun IconArt(
    icon: com.betteraudio.util.AppIconManager.AppIcon,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.28f))) {
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
            // An adaptive foreground is a 108dp canvas of which the launcher shows only the
            // middle 72dp. Drawing the whole thing previewed every mark a third smaller than it
            // actually lands on the home screen; the Box above already clips.
            modifier = Modifier.fillMaxSize().scale(108f / 72f)
        )
    }
}

/** One of the three marks. [open] is whose colours are on screen; [inUse] is whose icon is
 *  actually installed — they are usually the same but come apart the moment you browse another
 *  style without committing to it. */
@Composable
private fun IconStyleTile(
    style: com.betteraudio.util.AppIconManager.IconStyle,
    preview: com.betteraudio.util.AppIconManager.AppIcon,
    open: Boolean,
    inUse: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (open) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconArt(preview, 48.dp)
            Text(
                style.label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (inUse) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        IconArt(
            icon = icon,
            size = 56.dp,
            modifier = Modifier
                .clickable(onClick = onClick)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    else Modifier
                )
        )
        Text(
            icon.colorLabel,
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
        confirmButton = { HapticTextButton(onClick = onConfirm) { Text("Change icon") } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
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
            HapticRadioButton(selected = selected, onClick = onSelect)
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

