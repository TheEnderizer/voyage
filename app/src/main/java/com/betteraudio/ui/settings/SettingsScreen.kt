package com.betteraudio.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdateAlt
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.components.FolderBrowser
import com.betteraudio.ui.theme.Pill
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
    val rescanRunning   by viewModel.rescanRunning.collectAsStateWithLifecycle()
    val geminiApiKey    by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val updateState     by viewModel.updateState.collectAsStateWithLifecycle()
    val whatsNew        by viewModel.whatsNew.collectAsStateWithLifecycle()
    val currentSection  by viewModel.currentSection.collectAsStateWithLifecycle()

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
                        storageSettingsLauncher, { showBrowser = true }, viewModel
                    )
                    SettingsSection.Playback -> playbackSection(
                        skipForwardMs, skipBackMs, defaultSpeed, viewModel
                    )
                    SettingsSection.AI -> aiSection(geminiApiKey, viewModel)
                    SettingsSection.Updates -> updatesSection(updateState, whatsNew, viewModel)
                    SettingsSection.About -> aboutSection(viewModel)
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
        Triple(Icons.Default.SystemUpdateAlt, "Updates", SettingsSection.Updates),
        Triple(Icons.Default.Info, "About", SettingsSection.About),
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
}

private fun LazyListScope.playbackSection(
    skipForwardMs: Long,
    skipBackMs: Long,
    defaultSpeed: Float,
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

private fun LazyListScope.aboutSection(viewModel: SettingsViewModel) {
    item {
        SettingsCard(
            icon = Icons.Default.MusicNote,
            iconTint = MaterialTheme.colorScheme.primary,
            title = "Voyage",
            subtitle = "Version ${viewModel.currentVersion} (build ${viewModel.currentVersionCode})"
        )
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

// ─── Reusable settings building blocks ────────────────────────────────────────

@Composable
private fun NavRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
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
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = if (onClick != null) base.clickable(onClick = onClick) else base
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
