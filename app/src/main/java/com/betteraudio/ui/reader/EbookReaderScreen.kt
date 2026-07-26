package com.betteraudio.ui.reader

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookReaderScreen(
    onBack: () -> Unit,
    onListenFromHere: (Long) -> Unit,
    viewModel: EbookReaderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    // Keep the screen on while reading — matches the expectation of a dedicated reading mode.
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.flushCurrent()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> ReaderErrorContent(state.error!!, onBack)
            else -> with(this) { ReaderContent(state, viewModel, onBack, onListenFromHere, scope) }
        }
    }
}

@Composable
private fun ReaderErrorContent(error: ReaderError, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(error.message, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderContent(
    state: ReaderUiState,
    viewModel: EbookReaderViewModel,
    onBack: () -> Unit,
    onListenFromHere: (Long) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var showToc by remember { mutableStateOf(false) }
    var showFontSize by remember { mutableStateOf(false) }
    var showAlign by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }

    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.primary

    // The WebView is created once; `update` pushes new state into it (spine/theme/font changes)
    // without recreating the view (which would lose in-flight page rendering).
    var lastLoadedIndex by remember { mutableIntStateOf(-1) }
    var lastRestoreToken by remember { mutableIntStateOf(-1) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ReaderWebView(
                context = ctx,
                readEntry = viewModel::readEntry,
                mimeTypeFor = viewModel::mimeTypeFor,
                onFractionChanged = { f -> viewModel.onScrollFraction(state.currentSpineIndex, f) },
                onTap = { viewModel.toggleChrome() },
                onPageReady = {}
            )
        },
        update = { webView ->
            webView.bgHex = bg.toHex()
            webView.fgHex = fg.toHex()
            webView.accentHex = accent.toHex()
            webView.fontSizePct = state.fontSizePct

            val href = state.spine.getOrNull(state.currentSpineIndex)?.href
            if (href != null) {
                val spineChanged = state.currentSpineIndex != lastLoadedIndex
                val tokenChanged = state.restoreToken != lastRestoreToken
                if (spineChanged) {
                    webView.loadSpine(href, state.restoreFraction)
                    lastLoadedIndex = state.currentSpineIndex
                    lastRestoreToken = state.restoreToken
                } else if (tokenChanged) {
                    webView.reloadPreservingPosition(state.restoreFraction)
                    lastRestoreToken = state.restoreToken
                }
            }
        }
    )

    // ── Top chrome ──────────────────────────────────────────────────────────
    AnimatedVisibility(
        visible = state.chromeVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        state.book?.displayTitle ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    state.currentSpineTitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = { showOverflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                    DropdownMenuItem(
                        text = { Text("Chapters") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                        onClick = { showOverflow = false; showToc = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Font size") },
                        leadingIcon = { Icon(Icons.Default.TextFields, null) },
                        onClick = { showOverflow = false; showFontSize = true }
                    )
                    if (state.hasAudio) {
                        DropdownMenuItem(
                            text = { Text("Align chapters") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Rule, null) },
                            onClick = { showOverflow = false; showAlign = true }
                        )
                        val aligning = state.alignProgress?.running == true
                        DropdownMenuItem(
                            text = { Text(if (state.anchorCount > 0) "Re-align sync" else "Improve sync (on device)") },
                            leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                            enabled = !aligning,
                            onClick = {
                                showOverflow = false
                                if (state.modelState is com.betteraudio.data.transcribe.ModelState.Ready) viewModel.improveSync()
                                else showSyncDialog = true
                            }
                        )
                        // Prominent when a bundled mapping.json (see MappingFileIO) was found next
                        // to the audio — lets the user pull in a mapping obtained elsewhere (or
                        // restore one a rescan missed) without re-running on-device alignment.
                        if (state.mappingFileAvailable) {
                            DropdownMenuItem(
                                text = { Text("Import Mapping Data") },
                                leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                enabled = !aligning,
                                onClick = { showOverflow = false; viewModel.importMappingFile() }
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        )
    }

    // ── Bottom chrome ───────────────────────────────────────────────────────
    AnimatedVisibility(
        visible = state.chromeVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.prevChapter() },
                    enabled = state.currentSpineIndex > 0
                ) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous chapter") }

                if (state.hasAudio) {
                    FilledTonalButton(onClick = {
                        scope.launch { viewModel.listenFromHere()?.let(onListenFromHere) }
                    }) {
                        Icon(Icons.Default.Headphones, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Listen from here")
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                IconButton(
                    onClick = { viewModel.nextChapter() },
                    enabled = state.currentSpineIndex < state.spine.size - 1
                ) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next chapter") }
            }
        }
    }

    // ── Sync status (priority: aligning → model downloading → synced → approximate) ──
    val align = state.alignProgress
    val model = state.modelState
    Box(Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 12.dp, end = 12.dp)) {
        when {
            align?.running == true -> {
                val total = align.chaptersTotal.coerceAtLeast(1)
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Improving sync… ${align.chaptersDone}/$total · ${align.anchorsFound} anchors",
                                style = MaterialTheme.typography.labelMedium)
                            LinearProgressIndicator(
                                progress = { align.chaptersDone.toFloat() / total },
                                modifier = Modifier.width(180.dp).padding(top = 2.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.cancelSync() }) { Icon(Icons.Default.Close, "Cancel") }
                    }
                }
            }
            model is com.betteraudio.data.transcribe.ModelState.Downloading ->
                StatusChip("Downloading speech model… ${model.pct}%", MaterialTheme.colorScheme.secondaryContainer)
            model is com.betteraudio.data.transcribe.ModelState.Unzipping ->
                StatusChip("Preparing speech model…", MaterialTheme.colorScheme.secondaryContainer)
            state.hasAudio && state.anchorCount > 0 ->
                StatusChip("Synced · ${state.anchorCount} anchors", MaterialTheme.colorScheme.tertiaryContainer)
            state.chapterMapApproximate && state.hasAudio ->
                StatusChip("Approximate alignment — ⋮ to align chapters or improve sync", MaterialTheme.colorScheme.tertiaryContainer)
        }
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("Improve listen ↔ read sync") },
            text = {
                val modelReady = state.modelState is com.betteraudio.data.transcribe.ModelState.Ready
                Text(
                    "This transcribes short snippets of the audiobook on your device and matches them " +
                        "to the ebook text, pinning exact reference points so switching between reading and " +
                        "listening lands on the right paragraph.\n\n" +
                        if (modelReady) {
                            "Runs in the background — you can keep reading."
                        } else {
                            "It needs a one-time ~45 MB English speech model (works with English audiobooks " +
                                "for now) and runs in the background — you can keep reading. Wi-Fi " +
                                "recommended for the download."
                        }
                )
            },
            confirmButton = {
                TextButton(onClick = { showSyncDialog = false; viewModel.improveSync() }) {
                    Text(if (state.modelState is com.betteraudio.data.transcribe.ModelState.Ready) "Start" else "Download & start")
                }
            },
            dismissButton = { TextButton(onClick = { showSyncDialog = false }) { Text("Cancel") } }
        )
    }

    state.mappingImportMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearMappingImportMessage() },
            title = { Text("Import Mapping Data") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearMappingImportMessage() }) { Text("OK") }
            }
        )
    }

    if (showToc) {
        TocSheet(
            spine = state.spine,
            currentIndex = state.currentSpineIndex,
            onSelect = { viewModel.openSpine(it); showToc = false },
            onDismiss = { showToc = false }
        )
    }
    if (showFontSize) {
        FontSizeDialog(
            current = state.fontSizePct,
            onChange = { viewModel.setFontSize(it) },
            onDismiss = { showFontSize = false }
        )
    }
    if (showAlign) {
        ChapterAlignSheet(
            audioSpans = viewModel.audioSpansForAlign(),
            spine = state.spine,
            onAutoMatch = { viewModel.autoMatchChapters() },
            onSave = { viewModel.saveManualChapterMap(it) },
            onDismiss = { showAlign = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
    spine: List<com.betteraudio.data.ebook.SpineItem>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
            items(spine, key = { it.index }) { item ->
                ListItem(
                    headlineContent = { Text(item.title ?: "Section ${item.index + 1}") },
                    modifier = Modifier.clickable { onSelect(item.index) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (item.index == currentIndex)
                            MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color, tonalElevation = 2.dp) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun FontSizeDialog(current: Int, onChange: (Int) -> Unit, onDismiss: () -> Unit) {
    var pct by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Font size") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { pct = (pct - 10).coerceAtLeast(70) }) {
                    Icon(Icons.Default.Remove, "Smaller")
                }
                Text("$pct%", style = MaterialTheme.typography.headlineSmall)
                FilledTonalIconButton(onClick = { pct = (pct + 10).coerceAtMost(200) }) {
                    Icon(Icons.Default.TextFields, "Larger")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChange(pct); onDismiss() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun Color.toHex(): String {
    val argb = this.toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}
