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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Rule
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
            viewModel.flushNow(state.currentSpineIndex, state.restoreFraction)
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
                        leadingIcon = { Icon(Icons.Default.List, null) },
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
                            leadingIcon = { Icon(Icons.Default.Rule, null) },
                            onClick = { showOverflow = false; showAlign = true }
                        )
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
                ) { Icon(Icons.Default.NavigateBefore, "Previous chapter") }

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
                ) { Icon(Icons.Default.NavigateNext, "Next chapter") }
            }
        }
    }

    if (state.chapterMapApproximate && state.hasAudio) {
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.tertiaryContainer
        ) {
            Text(
                "Approximate chapter alignment — tap ⋮ > Align chapters to fix",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
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
