package com.betteraudio.ui.reader.contents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.ebook.SpineItem
import com.betteraudio.ui.haptics.*
import com.betteraudio.ui.reader.EbookReaderViewModel
import com.betteraudio.ui.reader.ReaderUiState

private enum class ContentsTab { CHAPTERS, MARKS, SEARCH }

/**
 * The reader's Contents — a real screen with its own back arrow, replacing `TocSheet` (a
 * `ModalBottomSheet`, deleted). Three tabs: Chapters (from the spine), Marks (bookmarks and
 * highlights, backed by `reader_marks`) and Search (full-text over the render stream via
 * [EbookReaderViewModel.search]).
 *
 * Marks was left out when this screen was first built, deliberately and with a note saying so:
 * there was no ebook annotation storage to back it, `Bookmark` being audio-only, and an empty
 * placeholder tab is worse than an absent one. That storage now exists, so the tab is real.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsScreen(
    state: ReaderUiState,
    viewModel: EbookReaderViewModel,
    onBack: () -> Unit,
    onSelectSpine: (Int) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(ContentsTab.CHAPTERS) }
    var showOverflow by remember { mutableStateOf(false) }
    var showAlign by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contents") },
                navigationIcon = {
                    HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    // The frozen listen↔read sync surface used to live in the reader's own ⋮
                    // overflow; that overflow is gone (the whole point of this redesign), so its
                    // items move here — still behind the same EBOOK_SYNC_UI flag (off by default).
                    if (state.hasAudio && com.betteraudio.util.FeatureFlags.EBOOK_SYNC_UI) {
                        HapticIconButton(onClick = { showOverflow = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            HapticDropdownMenuItem(
                                text = { Text("Align chapters") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Rule, null) },
                                onClick = { showOverflow = false; showAlign = true }
                            )
                            val aligning = state.alignProgress?.running == true
                            HapticDropdownMenuItem(
                                text = { Text(if (state.anchorCount > 0) "Re-align sync" else "Improve sync (on device)") },
                                leadingIcon = { Icon(Icons.Default.GraphicEq, null) },
                                enabled = !aligning,
                                onClick = {
                                    showOverflow = false
                                    if (state.modelState is com.betteraudio.data.transcribe.ModelState.Ready) viewModel.improveSync()
                                    else showSyncDialog = true
                                }
                            )
                            if (state.mappingFileAvailable) {
                                HapticDropdownMenuItem(
                                    text = { Text("Import Mapping Data") },
                                    leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                                    enabled = !aligning,
                                    onClick = { showOverflow = false; viewModel.importMappingFile() }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab.ordinal) {
                Tab(selected = tab == ContentsTab.CHAPTERS, onClick = { tab = ContentsTab.CHAPTERS },
                    text = { Text("Chapters") })
                Tab(selected = tab == ContentsTab.MARKS, onClick = { tab = ContentsTab.MARKS },
                    text = { Text("Marks") })
                Tab(selected = tab == ContentsTab.SEARCH, onClick = { tab = ContentsTab.SEARCH },
                    text = { Text("Search") })
            }
            when (tab) {
                ContentsTab.CHAPTERS -> ChaptersTab(
                    spine = state.spine,
                    currentIndex = state.currentSpineIndex,
                    onSelect = onSelectSpine
                )
                ContentsTab.MARKS -> MarksTab(
                    marks = state.marks,
                    onOpen = { mark ->
                        viewModel.openMark(mark)
                        onSelectSpine(mark.spineIndex) // closes Contents, like tapping a chapter
                    },
                    onDelete = { viewModel.deleteMark(it) }
                )
                ContentsTab.SEARCH -> SearchTab(viewModel = viewModel, onJump = { result ->
                    viewModel.jumpToSearchResult(result)
                    onSelectSpine(result.spineIndex) // closes Contents the same way tapping a chapter does
                })
            }
        }
    }

    if (showSyncDialog && com.betteraudio.util.FeatureFlags.EBOOK_SYNC_UI) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("Improve listen ↔ read sync") },
            text = {
                val modelReady = state.modelState is com.betteraudio.data.transcribe.ModelState.Ready
                Text(
                    "This transcribes short snippets of the audiobook on your device and matches them " +
                        "to the ebook text, pinning exact reference points so switching between reading and " +
                        "listening lands on the right paragraph.\n\n" +
                        if (modelReady) "Runs in the background — you can keep reading."
                        else "It needs a one-time ~45 MB English speech model (works with English audiobooks " +
                            "for now) and runs in the background — you can keep reading. Wi-Fi recommended for the download."
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { showSyncDialog = false; viewModel.improveSync() }) {
                    Text(if (state.modelState is com.betteraudio.data.transcribe.ModelState.Ready) "Start" else "Download & start")
                }
            },
            dismissButton = { HapticTextButton(onClick = { showSyncDialog = false }) { Text("Cancel") } }
        )
    }

    state.mappingImportMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearMappingImportMessage() },
            title = { Text("Import Mapping Data") },
            text = { Text(message) },
            confirmButton = { HapticTextButton(onClick = { viewModel.clearMappingImportMessage() }) { Text("OK") } }
        )
    }

    if (showAlign && com.betteraudio.util.FeatureFlags.EBOOK_SYNC_UI) {
        com.betteraudio.ui.reader.ChapterAlignSheet(
            audioSpans = viewModel.audioSpansForAlign(),
            spine = state.spine,
            onAutoMatch = { viewModel.autoMatchChapters() },
            onSave = { viewModel.saveManualChapterMap(it) },
            onDismiss = { showAlign = false }
        )
    }
}

/**
 * Bookmarks and highlights, in reading order rather than the order they were made — this is a
 * second table of contents for the book, and a passage marked last week belongs where it sits in
 * the story, not at the top of a feed.
 */
@Composable
private fun MarksTab(
    marks: List<com.betteraudio.data.db.entities.ReaderMark>,
    onOpen: (com.betteraudio.data.db.entities.ReaderMark) -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (marks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing marked yet.\n\nBookmark the page you are on with the ribbon in the reader's " +
                    "top bar, or tap the pen next to it and then tap any paragraph to highlight it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp)
            )
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(marks, key = { it.id }) { mark ->
            val isHighlight = mark.kind == com.betteraudio.data.db.entities.ReaderMarkKind.HIGHLIGHT
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(mark) }
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // A highlight is identified by its own colour; a bookmark by the ribbon. Nothing
                // else in the row needs to say which kind it is.
                Box(
                    Modifier
                        .padding(top = 3.dp, end = 12.dp)
                        .size(width = 4.dp, height = 34.dp)
                        .background(
                            if (isHighlight) androidx.compose.ui.graphics.Color(mark.colorArgb)
                            else MaterialTheme.colorScheme.primary
                        )
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        mark.chapterTitle.ifBlank { "Chapter ${mark.spineIndex + 1}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        mark.preview.ifBlank { "(no text)" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                    if (mark.note.isNotBlank()) {
                        Text(
                            mark.note,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HapticIconButton(onClick = { onDelete(mark.id) }) {
                    Icon(Icons.Default.Close, "Remove")
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ChaptersTab(spine: List<SpineItem>, currentIndex: Int, onSelect: (Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(spine, key = { it.index }) { item ->
            val isCurrent = item.index == currentIndex
            val isRead = item.index < currentIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(item.index) }
                    .background(if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "%02d".format(item.index + 1),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.title ?: "Section ${item.index + 1}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                when {
                    isRead -> Icon(Icons.Default.Check, "Read", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    isCurrent -> Text("Current", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SearchTab(viewModel: EbookReaderViewModel, onJump: (EbookReaderViewModel.SearchResult) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; viewModel.search(it) },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search this book") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    HapticIconButton(onClick = { query = ""; viewModel.search("") }) {
                        Icon(Icons.Default.Close, "Clear")
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) })
        )
        when {
            searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Search for words or phrases in this book", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No matches for “$query”", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(results) { result ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onJump(result) }.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Text(
                            result.spineTitle ?: "Section ${result.spineIndex + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildAnnotatedString(result),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun buildAnnotatedString(result: EbookReaderViewModel.SearchResult) =
    androidx.compose.ui.text.buildAnnotatedString {
        append(result.snippet)
        addStyle(
            androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold),
            result.matchStart.coerceIn(0, result.snippet.length),
            result.matchEnd.coerceIn(0, result.snippet.length)
        )
    }
