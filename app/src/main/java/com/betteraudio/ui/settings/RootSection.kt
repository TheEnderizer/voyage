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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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

// ─── Section content blocks ───────────────────────────────────────────────────

/**
 * A row on the Settings root.
 *
 * [value] is the destination's current answer, drawn on the right. The root used to be nine
 * identical doors, so the page could only forward a question — "what is my skip length?" cost a
 * trip in and a trip back. Stating the answer here turns the same nine rows into a summary of the
 * app's configuration, at no extra height.
 *
 * Icons are exactly the ones this list has always used, and the order is unchanged — the bands
 * below only draw a label over runs of the existing sequence.
 */
private data class RootRow(
    val icon: ImageVector,
    val label: String,
    val dest: SettingsSection,
    val value: String? = null
)

internal fun LazyListScope.rootSection(viewModel: SettingsViewModel) {
    item { Spacer(Modifier.height(4.dp)) }

    // The field itself lives in the app bar (SettingsTopBarTitle) — here we only react to it.
    // While a query is live the bands are replaced entirely rather than filtered in place: the
    // point of searching is to stop thinking in sections, and leaving the section furniture on
    // screen invites reading past the answer.
    if (viewModel.searchQuery.trim().length >= 2) {
        item { SettingsSearchResults(viewModel) }
        return
    }


    item {
        val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
        val skipForward by viewModel.skipForwardMs.collectAsStateWithLifecycle()
        val skipBack by viewModel.skipBackMs.collectAsStateWithLifecycle()
        val presets by viewModel.presets.collectAsStateWithLifecycle()
        val bookCount by viewModel.bookCount.collectAsStateWithLifecycle()
        val geminiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()

        val bands = listOf(
            "Appearance" to listOf(
                RootRow(
                    Icons.Default.Palette, "Look and feel", SettingsSection.Theme,
                    com.betteraudio.ui.components.THEME_OPTIONS.firstOrNull { it.theme == appTheme }?.title
                ),
                RootRow(Icons.Default.Widgets, "Widget", SettingsSection.Widget),
            ),
            "Playback & library" to listOf(
                RootRow(
                    Icons.Default.Speed, "Playback", SettingsSection.Playback,
                    "${skipForward / 1000}s / ${skipBack / 1000}s"
                ),
                RootRow(
                    Icons.Default.Tune, "Audio presets", SettingsSection.Presets,
                    presets.size.takeIf { it > 0 }?.let { "$it saved" }
                ),
                RootRow(
                    Icons.Default.Folder, "Library", SettingsSection.Library,
                    bookCount.takeIf { it > 0 }?.let { "$it books" }
                ),
            ),
            "App" to listOf(
                RootRow(
                    Icons.Default.AutoAwesome, "AI Synopsis", SettingsSection.AI,
                    if (geminiKey.isBlank()) "No key" else "Key set"
                ),
                RootRow(Icons.Default.Download, "Backup & restore", SettingsSection.Backup),
                RootRow(
                    Icons.Default.Info, "About", SettingsSection.About,
                    viewModel.currentVersion
                ),
                // Diagnostics has no single value worth summarising, so it shows none rather
                // than something invented to fill the column.
                RootRow(Icons.Default.BugReport, "Diagnostics", SettingsSection.Diagnostics),
            ),
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            bands.forEach { (band, rows) ->
                BandHeader(band)
                rows.forEach { row ->
                    NavRow(
                        icon = row.icon,
                        label = row.label,
                        value = row.value,
                        onClick = { viewModel.navigateTo(row.dest) }
                    )
                }
            }
        }
    }
}



/** One search hit: what it is called, where it lives, and a chevron because tapping leaves here. */
@Composable
private fun SearchResultRow(entry: SettingsIndexEntry, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().pressScale().clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForwardIos, null,
                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * The result list for a live query. One list item rather than one per result: the theme and colour
 * source it filters on are collected here, and collecting them per row would subscribe once per
 * result for no gain.
 */
@Composable
private fun SettingsSearchResults(viewModel: SettingsViewModel) {
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val colorSource by viewModel.themeColorSource.collectAsStateWithLifecycle()
    val results = searchSettings(viewModel.searchQuery, appTheme, colorSource)

    if (results.isEmpty()) {
        Text(
            "Nothing matches \u201C${viewModel.searchQuery.trim()}\u201D.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        results.forEach { entry ->
            SearchResultRow(entry) { viewModel.openSearchResult(entry.section, entry.group) }
        }
    }
}
