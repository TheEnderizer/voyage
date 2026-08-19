package com.betteraudio.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.ui.components.FolderBrowser
import kotlin.math.roundToInt

/** Playback controls to display when the sheet is opened from a player context. */
data class PlaybackOptions(
    val currentSpeed: Float,
    val currentBoostDb: Int,
    val onSpeedChange: (Float) -> Unit,
    val onBoostChange: (Int) -> Unit,
    val onChangeCoverFromGallery: () -> Unit
)

/**
 * Series-level cascade defaults + metadata. Every member book inherits these unless the book has
 * its own value ("Use app default" clears a field back to inherit-nothing / null).
 */
data class SeriesOptions(
    val series: Series,
    val onSave: (Series) -> Unit
)

private val EQ_BAND_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
private const val EQ_MIN_MB = -1500
private const val EQ_MAX_MB = 1500

private fun decodeEqBands(json: String?): IntArray? {
    if (json.isNullOrBlank()) return null
    return try {
        val arr = org.json.JSONArray(json)
        IntArray(5) { i -> if (i < arr.length()) arr.getInt(i) else 0 }
    } catch (_: Exception) { null }
}

private fun encodeEqBands(bands: IntArray): String =
    org.json.JSONArray(bands.toList()).toString()

/**
 * The single shared options page for both books and series. Pass [bwp] for book options (opened
 * from Home or the player overflow); pass [seriesOptions] for series options (opened from the
 * series detail overflow). Exactly one of the two should be non-null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOptionsSheet(
    bwp: BookWithProgress? = null,
    onDismiss: () -> Unit,
    onUpdateMetadata: (titleOverride: String?, authorOverride: String?) -> Unit = { _, _ -> },
    onUpdateSeries: (seriesName: String?, seriesOrder: Float?) -> Unit = { _, _ -> },
    onUpdateStatus: (BookStatus) -> Unit = {},
    onSearchOnlineCover: () -> Unit = {},
    onRefreshCoverEffect: () -> Unit = {},
    onIgnore: () -> Unit = {},
    onDeletePermanently: (deleteFiles: Boolean) -> Unit = {},
    playback: PlaybackOptions? = null,
    onConnectEpub: (path: String) -> Unit = {},
    onDisconnectEpub: () -> Unit = {},
    onOpenReader: () -> Unit = {},
    onPinShortcut: () -> Unit = {},
    seriesOptions: SeriesOptions? = null
) {
    val book = bwp?.book
    var titleInput by remember { mutableStateOf(book?.let { it.titleOverride ?: it.title } ?: "") }
    var authorInput by remember { mutableStateOf(book?.let { it.authorOverride ?: it.author } ?: "") }
    var seriesName by remember { mutableStateOf(book?.seriesName ?: "") }
    var seriesOrder by remember {
        mutableStateOf(book?.seriesOrder?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "")
    }
    var status by remember { mutableStateOf(book?.status ?: BookStatus.NOT_STARTED) }
    var showIgnoreConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteFiles by remember { mutableStateOf(false) }
    var speed by remember(playback?.currentSpeed) { mutableFloatStateOf(playback?.currentSpeed ?: 1f) }
    var boost by remember(playback?.currentBoostDb) { mutableIntStateOf(playback?.currentBoostDb ?: 0) }

    // Series cascade defaults (only relevant when seriesOptions != null)
    val series = seriesOptions?.series
    var sSpeed by remember(series?.id) { mutableStateOf(series?.playbackSpeed) }           // null = app default
    var sBoost by remember(series?.id) { mutableStateOf(series?.boostDb) }                 // null = none
    var sSkipSilence by remember(series?.id) { mutableStateOf(series?.skipSilenceEnabled == true) }
    var sEq by remember(series?.id) { mutableStateOf(decodeEqBands(series?.eqBandsJson)) }  // null = app default
    var sAuthor by remember(series?.id) { mutableStateOf(series?.author ?: "") }
    var sNarrator by remember(series?.id) { mutableStateOf(series?.narrator ?: "") }

    ModalBottomSheet(
        containerColor = com.betteraudio.ui.components.appSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                if (book != null) "Book Options" else "Series Options",
                style = MaterialTheme.typography.titleLarge
            )

            if (book != null) {
                // ── Metadata ──────────────────────────────────────────────
                OptionsSection("Details") {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authorInput,
                        onValueChange = { authorInput = it },
                        label = { Text("Author") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            onUpdateMetadata(
                                titleInput.trim().takeIf { it != book.title }?.ifBlank { null },
                                authorInput.trim().takeIf { it != book.author }?.ifBlank { null }
                            )
                            onDismiss()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Save") }
                }

                // ── Playback (only when opened from player context) ────────
                if (playback != null) {
                    OptionsSection("Playback Speed  ${"%.2f".format(speed)}×") {
                        Slider(
                            value = speed,
                            onValueChange = { speed = (it / 0.05f).roundToInt() * 0.05f },
                            onValueChangeFinished = { playback.onSpeedChange(speed) },
                            valueRange = 0.5f..3.0f,
                            steps = 49,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0.5×", style = MaterialTheme.typography.labelSmall)
                            Text("3.0×", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    OptionsSection("Volume Boost  ${boost} dB") {
                        Slider(
                            value = boost.toFloat(),
                            onValueChange = { boost = it.toInt() },
                            onValueChangeFinished = { playback.onBoostChange(boost) },
                            valueRange = 0f..24f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("0 dB", style = MaterialTheme.typography.labelSmall)
                            Text("+24 dB", style = MaterialTheme.typography.labelSmall)
                        }
                        if (boost > 12) {
                            Text(
                                "High boost may cause distortion",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // ── Status ─────────────────────────────────────────────────
                OptionsSection("Status") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BookStatus.entries.forEach { s ->
                            FilterChip(
                                selected = status == s,
                                onClick = { status = s; onUpdateStatus(s) },
                                label = {
                                    Text(s.name.replace('_', ' ').lowercase()
                                        .replaceFirstChar { it.uppercase() })
                                }
                            )
                        }
                    }
                }

                // ── Cover art ──────────────────────────────────────────────
                OptionsSection("Cover") {
                    OutlinedButton(
                        onClick = { onSearchOnlineCover() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Search online for cover")
                    }
                    Spacer(Modifier.height(4.dp))
                    if (playback != null) {
                        OutlinedButton(
                            onClick = { playback.onChangeCoverFromGallery(); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Photo, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Choose from gallery")
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedButton(
                        onClick = { onRefreshCoverEffect(); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh cover effect")
                    }
                }

                // ── Ebook (EPUB) ─────────────────────────────────────────────
                if (com.betteraudio.util.FeatureFlags.EBOOKS_UI) OptionsSection("Ebook") {
                    if (book.ebookPath != null) {
                        Text(
                            java.io.File(book.ebookPath).name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { onOpenReader(); onDismiss() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open reader")
                            }
                            OutlinedButton(onClick = onDisconnectEpub, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Disconnect")
                            }
                        }
                    } else {
                        var showEpubPicker by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { showEpubPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect EPUB…")
                        }
                        if (showEpubPicker) {
                            val startPath = remember(book.folderPath) {
                                val real = java.io.File(book.folderPath.substringBefore("::"))
                                (if (real.isDirectory) real else real.parentFile)?.absolutePath
                                    ?: "/storage/emulated/0"
                            }
                            FolderBrowser(
                                startPath = startPath,
                                onSelect = {},
                                onCancel = { showEpubPicker = false },
                                fileExtensions = setOf("epub"),
                                onSelectFile = { path -> onConnectEpub(path); showEpubPicker = false },
                                title = "Choose EPUB"
                            )
                        }
                    }
                }

                // ── Series ─────────────────────────────────────────────────
                OptionsSection("Series") {
                    OutlinedTextField(
                        value = seriesName,
                        onValueChange = { seriesName = it },
                        label = { Text("Series name") },
                        placeholder = { Text("e.g. The Witcher") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = seriesOrder,
                        onValueChange = { seriesOrder = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Position in series") },
                        placeholder = { Text("e.g. 1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        if (book.seriesName != null) {
                            OutlinedButton(onClick = { onUpdateSeries(null, null); onDismiss() }) {
                                Text("Remove")
                            }
                        }
                        Button(onClick = {
                            onUpdateSeries(
                                seriesName.trim().ifBlank { null },
                                seriesOrder.trim().toFloatOrNull()
                            )
                            onDismiss()
                        }) { Text("Save series") }
                    }
                }

                // ── File parts (if multi-file) ─────────────────────────────
                if (bwp.audioFiles.size > 1) {
                    OptionsSection("Parts (${bwp.audioFiles.size})") {
                        val sorted = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
                        sorted.forEachIndexed { i, f ->
                            Text(
                                "${i + 1}. ${f.fileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // ── Ignore / Delete ───────────────────────────────────────
                OptionsSection("Library") {
                    OutlinedButton(
                        onClick = onPinShortcut,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pin to home screen")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showIgnoreConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VisibilityOff, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Hide from library")
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Delete permanently")
                    }
                }
            }

            // ── Series cascade defaults (series mode only) ───────────────
            if (seriesOptions != null && series != null) {
                OptionsSection("Series defaults") {
                    Text(
                        "These apply to every book in the series unless a book has its own setting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OptionsSection("Playback speed  ${sSpeed?.let { "%.2f×".format(it) } ?: "App default"}") {
                    Slider(
                        value = sSpeed ?: 1.0f,
                        onValueChange = { sSpeed = (Math.round(it * 20f) / 20f) },
                        valueRange = 0.5f..3.0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (sSpeed != null) {
                        TextButton(onClick = { sSpeed = null }) { Text("Use app default") }
                    }
                }

                OptionsSection("Volume boost  ${sBoost?.let { "$it dB" } ?: "None"}") {
                    Slider(
                        value = (sBoost ?: 0).toFloat(),
                        onValueChange = { sBoost = it.toInt() },
                        valueRange = 0f..12f,
                        steps = 11,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (sBoost != null) {
                        TextButton(onClick = { sBoost = null }) { Text("Use app default") }
                    }
                }

                OptionsSection("Equalizer  ${if (sEq != null) "Custom" else "App default"}") {
                    if (sEq != null) {
                        val bands = sEq!!
                        EQ_BAND_LABELS.forEachIndexed { index, label ->
                            val levelMb = bands.getOrElse(index) { 0 }
                            val levelDb = levelMb / 100f
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                                Slider(
                                    value = levelMb.toFloat(),
                                    onValueChange = { v ->
                                        val next = bands.copyOf()
                                        next[index] = v.roundToInt()
                                        sEq = next
                                    },
                                    valueRange = EQ_MIN_MB.toFloat()..EQ_MAX_MB.toFloat(),
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${if (levelDb >= 0) "+" else ""}${String.format("%.1f", levelDb)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.width(48.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                                )
                            }
                        }
                        TextButton(onClick = { sEq = null }) { Text("Use app default") }
                    } else {
                        OutlinedButton(onClick = { sEq = IntArray(5) { 0 } }) {
                            Text("Set custom EQ for this series")
                        }
                    }
                }

                OptionsSection("Skip silence") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Skip silence", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Auto-skip silent gaps for this series' books",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = sSkipSilence, onCheckedChange = { sSkipSilence = it })
                    }
                }

                OptionsSection("Series metadata") {
                    OutlinedTextField(
                        value = sAuthor, onValueChange = { sAuthor = it },
                        label = { Text("Series author") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sNarrator, onValueChange = { sNarrator = it },
                        label = { Text("Series narrator") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(
                        onClick = {
                            seriesOptions.onSave(
                                series.copy(
                                    playbackSpeed = sSpeed,
                                    boostDb = sBoost,
                                    eqBandsJson = sEq?.let(::encodeEqBands),
                                    skipSilenceEnabled = if (sSkipSilence) true else null,
                                    author = sAuthor.trim().ifBlank { null },
                                    narrator = sNarrator.trim().ifBlank { null }
                                )
                            )
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                }
            }
        }
    }

    // Ignore confirmation
    if (showIgnoreConfirm && book != null) {
        AlertDialog(
            onDismissRequest = { showIgnoreConfirm = false },
            title = { Text("Hide \"${book.displayTitle}\"?") },
            text = { Text("The book will be hidden from your library. You can restore it from Settings → Library → Ignored books.") },
            confirmButton = {
                TextButton(onClick = { showIgnoreConfirm = false; onIgnore(); onDismiss() }) {
                    Text("Hide")
                }
            },
            dismissButton = {
                TextButton(onClick = { showIgnoreConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Permanent delete confirmation
    if (showDeleteConfirm && book != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false; deleteFiles = false },
            title = { Text("Delete \"${book.displayTitle}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This will permanently remove the book from Voyage. This action cannot be undone.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also delete audio files from storage", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false; onDeletePermanently(deleteFiles); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false; deleteFiles = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun OptionsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
