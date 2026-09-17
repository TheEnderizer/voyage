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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
            HapticTextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Reusable settings building blocks ────────────────────────────────────────
// Used across nearly every (unsplit) section function below, so — unlike the split screens —
// these stay in one place and resolve their own card color per theme, the same way the old
// shared `appCardColor()` helper did.

@Composable
internal fun settingsCardColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) ImmersiveStyle.cardColor() else MaterialStyle.cardColor()

@Composable
internal fun NavRow(icon: ImageVector, label: String, value: String? = null, onClick: () -> Unit) {
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
            // The row's current answer, so the root page states the settings instead of only
            // forwarding to them. Optional: a destination with no single summarising value
            // (Diagnostics) simply omits it rather than inventing one.
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A settings group that shows only its answer until you open it.
 *
 * Settings' navigation problem was never the root list — that is nine rows and half a screen of
 * empty space. It was one level down: "Look and feel" was about seven screens of unbroken scroll,
 * because every option is a full-width card carrying a title AND a two-line description, drawn
 * permanently expanded, for choices made once and then never thought about again. You scrolled past
 * roughly fifteen paragraphs of prose to reach App icon.
 *
 * Collapsed to `title — value`, the same section is one screen. The descriptions are not deleted;
 * they appear when the group opens, which is the only moment they are worth their space.
 *
 * [value] is the current answer, drawn on the right in the accent colour — so a collapsed section is
 * still a readable summary of every choice in it, not just a list of doors.
 *
 * The trailing glyph is deliberately [Icons.Default.ExpandMore] and not the [NavRow] chevron: down
 * means "opens here", the sideways chevron means "opens another screen". Both glyphs already
 * existed in the app, so this adds no new icon.
 *
 * Open/closed state is hoisted rather than remembered internally, so a section can hold one group
 * open at a time and so a future search can land with its group already open.
 */
@Composable
internal fun CollapsingGroup(
    title: String,
    value: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    /**
     * A control drawn in the collapsed header, in place of [value]. For a switch the control IS
     * the answer, so burying it behind a disclosure would cost a tap to reach something that was
     * already one tap away; the paragraph explaining it is what belongs inside. Tapping the row
     * still expands, tapping the control still operates it.
     */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptics = com.betteraudio.ui.haptics.LocalHaptics.current
    CardContainer {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (expanded) haptics.dismiss() else haptics.reveal()
                        onToggle()
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Box(Modifier.padding(end = 10.dp)) { trailing() }
                } else if (!value.isNullOrBlank()) {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 10.dp)
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * [CollapsingGroup] keyed on its own title, which is what a section wants at almost every call
 * site: the titles within a section are already unique, so they make a serviceable id and there is
 * nothing extra to keep in sync.
 */
@Composable
internal fun CollapsingCard(
    title: String,
    value: String?,
    viewModel: SettingsViewModel,
    content: @Composable ColumnScope.() -> Unit
) = CollapsingGroup(
    title = title,
    value = value,
    expanded = viewModel.openGroup == title,
    onToggle = { viewModel.toggleGroup(title) },
    content = content
)

/**
 * The label over a run of related groups ("THEME", "PLAYER", "FEEL"). Purely a scanning aid: the
 * rows below it are ordinary groups and nothing about the band is interactive.
 */
@Composable
internal fun BandHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp)
    )
}

@Composable
internal fun CardContainer(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
internal fun IconBadge(icon: ImageVector, tint: Color) {
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
internal fun SettingsCard(
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
internal fun FolderStructureExample() {
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
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IntervalChips(
    options: List<Long>,
    selected: Long,
    onSelect: (Long) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { ms ->
            HapticFilterChip(
                selected = selected == ms,
                onClick = { onSelect(ms) },
                label = { Text("${ms / 1000}s") },
                shape = Pill
            )
        }
    }
}

// ─── Audio presets (unified bundles + global default) ──────────────────────────
internal fun hasAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

internal fun allFilesAccessIntent(context: Context): Intent =
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

// ─── Search in the app bar ────────────────────────────────────────────────────
// Shared by both theme variants: each keeps its own TopAppBar and colours, but the search
// behaviour is identical and duplicating it would mean fixing every change twice.

/**
 * The top bar's title slot: the section name normally, the search field while searching.
 *
 * Search replaces the title rather than sitting above the list. A permanent field costs a row of
 * height on every visit for something wanted occasionally, and competes with the first band for the
 * eye; the app bar already has the room, and the row is where a search icon is looked for.
 */
@Composable
internal fun SettingsTopBarTitle(viewModel: SettingsViewModel, sectionTitle: String) {
    if (!viewModel.searchActive) {
        Text(sectionTitle, style = MaterialTheme.typography.titleLarge)
        return
    }
    val focus = remember { FocusRequester() }
    // Opening search puts the caret in the field: tapping the icon is already the statement of
    // intent, and making the user then tap the field to type would be a second tap for nothing.
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box {
        if (viewModel.searchQuery.isEmpty()) {
            Text(
                "Search settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        BasicTextField(
            value = viewModel.searchQuery,
            onValueChange = { viewModel.searchQuery = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
        )
    }
}

/**
 * The top bar's search action, to the right of the title.
 *
 * Only offered on the root: the sections are one screen each now, so there is nothing to find
 * inside one, and searching from a section would have to decide whether it means "here" or
 * "everywhere". [SettingsViewModel.navigateTo] closes search for the same reason.
 */
@Composable
internal fun SettingsSearchAction(viewModel: SettingsViewModel, currentSection: SettingsSection) {
    if (currentSection != SettingsSection.Root) return
    if (viewModel.searchActive) {
        HapticIconButton(onClick = { viewModel.closeSearch() }) {
            Icon(Icons.Default.Close, "Close search")
        }
    } else {
        HapticIconButton(onClick = { viewModel.openSearch() }) {
            Icon(Icons.Default.Search, "Search settings")
        }
    }
}
