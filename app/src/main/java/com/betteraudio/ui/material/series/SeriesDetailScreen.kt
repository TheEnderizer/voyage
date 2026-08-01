package com.betteraudio.ui.material.series

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.ui.components.BookInfoPanel
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.home.SeriesOptions
import com.betteraudio.ui.material.motion.LocalVoyageMotion
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.series.SeriesDetailViewModel
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import com.betteraudio.ui.theme.rememberPredictiveBackProgress
import java.io.File
import kotlinx.coroutines.launch

/**
 * Series page — the exact same opaque Material You frame as the book info page (rounded cover
 * card, info block with AI synopsis and a play button). Swiping up slides in the editable book
 * list (add / remove / reorder); swiping the panel down returns to the info page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: (bookId: Long) -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val series by viewModel.series.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val candidates by viewModel.candidateBooks.collectAsStateWithLifecycle()
    val synopsisGenerating by viewModel.synopsisGenerating.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    // Open the player on whichever book started playing (resume book, or a tapped book).
    LaunchedEffect(Unit) { viewModel.openPlayer.collect { onOpenPlayer(it) } }

    val scope = rememberCoroutineScope()
    val motion = LocalVoyageMotion.current
    // 0 = info page, 1 = books panel fully up. panelAnim only drives ANIMATED transitions
    // (settle, predictive-back cancel) — a live drag writes dragProgress directly and
    // synchronously instead, mirroring PlayerSheet's AN-2 fix: N per-delta coroutines writing
    // panelAnim.snapTo() directly could otherwise race an in-flight animateTo.
    val panelAnim = remember { Animatable(0f) }
    val dragProgress = remember { mutableFloatStateOf(0f) }
    // True for the duration of a live drag — gates the mirror below so a still-finishing
    // animateTo (the interrupt case) can't stomp dragProgress while a drag is in control of it.
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(panelAnim) {
        snapshotFlow { panelAnim.value }.collect { if (!isDragging) dragProgress.floatValue = it }
    }
    // derivedStateOf: recompose only when the threshold flips, not every animation/drag frame —
    // the composition-phase `val panelOpen = panelAnim.value > 0.5f` this replaces recomposed
    // this whole screen on every frame of the panel spring and every drag delta.
    val panelOpen by remember { derivedStateOf { dragProgress.floatValue > 0.5f } }
    fun settlePanel(open: Boolean) = scope.launch {
        panelAnim.snapTo(dragProgress.floatValue)
        panelAnim.animateTo(if (open) 1f else 0f, motion.spatialDefault)
    }
    // Predictive back drives the panel closed in lockstep with the gesture (not just a commit-only
    // BackHandler) — mirrors PlayerSheet's seek pattern for the mini↔full player.
    val panelBackProgress = rememberPredictiveBackProgress(enabled = panelOpen) { settlePanel(false) }
    LaunchedEffect(panelBackProgress.value) {
        if (panelOpen) panelAnim.snapTo(1f - panelBackProgress.value)
    }

    // Cover morph from/to the tapped series grid card (same treatment as Book Info's grid → info
    // morph) — 0 = sitting on the card, 1 = fully open. Drives coverMorphFrom below.
    val coverBoundsRegistry = com.betteraudio.ui.player.LocalCoverBoundsRegistry.current
    val coverOpenAnim = remember { Animatable(0f) }
    val coverOpenProgress = remember { derivedStateOf { coverOpenAnim.value } }
    LaunchedEffect(Unit) { coverOpenAnim.animateTo(1f, motion.spatialDefault) }
    LaunchedEffect(viewModel.seriesId) {
        coverBoundsRegistry.setActiveSeriesMorph(viewModel.seriesId, coverOpenProgress)
    }
    DisposableEffect(Unit) { onDispose { coverBoundsRegistry.setActiveSeriesMorph(-1L, null) } }
    val coverSource = remember(viewModel.seriesId) { coverBoundsRegistry.seriesBoundsState(viewModel.seriesId) }
    val coverSourceRadius = coverBoundsRegistry.seriesRadiusFor(viewModel.seriesId)
    // Whole-screen close: only active while the books panel is closed (the panel's own back
    // handler above takes priority while it's showing). Awaits the cover shrinking back onto the
    // grid card before actually popping, so close matches open's "glued to the card" quality.
    val closeBackProgress = rememberPredictiveBackProgress(enabled = !panelOpen) {
        scope.launch {
            coverOpenAnim.animateTo(0f, motion.spatialDefault)
            onBack()
        }
    }
    LaunchedEffect(closeBackProgress.value) {
        if (!panelOpen) coverOpenAnim.snapTo(1f - closeBackProgress.value)
    }
    fun closeWithMorph() = scope.launch {
        coverOpenAnim.animateTo(0f, motion.spatialDefault)
        onBack()
    }

    val onScrim = MaterialTheme.colorScheme.onSurface
    val onScrimMuted = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val panelHeightPx = constraints.maxHeight * 0.84f

        fun dragBy(delta: Float) {
            // Synchronous, no coroutine — see dragProgress's declaration above (AN-2).
            dragProgress.floatValue = (dragProgress.floatValue - delta / panelHeightPx).coerceIn(0f, 1f)
        }
        fun dragStopped(velocity: Float) {
            isDragging = false
            val open = velocity < -900f || (velocity <= 900f && dragProgress.floatValue > 0.4f)
            settlePanel(open)
        }

        val coverPath = series?.coverArtPath
            ?: books.firstOrNull { it.coverArtPath != null }?.coverArtPath

        // ── Info page (swipe up anywhere to reveal the books panel) ────────────
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { dragBy(it) },
                    onDragStarted = { isDragging = true },
                    onDragStopped = { dragStopped(it) }
                )
        ) {
            // ── Top bar ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", tonal = true, onClick = { closeWithMorph() })
                Spacer(Modifier.weight(1f))
                Text(
                    "SERIES",
                    style = MaterialTheme.typography.labelSmall,
                    color = onScrimMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Box {
                    ScrimButton(Icons.Default.MoreVert, "More", tonal = true) { showOverflow = true }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Series options") },
                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                            onClick = { showOverflow = false; showOptions = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename series") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showOverflow = false; showRename = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Add books") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick = { showOverflow = false; showAdd = true }
                        )
                    }
                }
            }

            // Rounded cover card in the leftover space — same tonal treatment as the player's
            // own cover and the book info page, instead of the old full-bleed blurred backdrop.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                AsyncImage(
                    model = coverPath?.let { File(it) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(0.72f)
                        .morphFrom(
                            coverSource, coverOpenProgress,
                            anchorTopLeft = true, byWidth = true,
                            sourceRadius = coverSourceRadius, destRadius = 28.dp
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            }

            // ── Bottom info block — same panel as a book, incl. the AI synopsis ─
            val effectiveAuthor = series?.author?.takeIf { it.isNotBlank() }
                ?: books.firstOrNull { it.displayAuthor.isNotBlank() }?.displayAuthor
            BookInfoPanel(
                title            = series?.name ?: "",
                author           = effectiveAuthor,
                narrator         = series?.narrator,
                seriesLabel      = "Series · ${books.size} book${if (books.size != 1) "s" else ""}",
                status           = null,
                progressFraction = progress.fraction,
                totalMs          = progress.totalMs,
                synopsis         = series?.description?.takeIf { it.isNotBlank() }
                                   ?: if (synopsisGenerating) "Generating synopsis…" else null,
                onResume         = { viewModel.playSeries() }
            )

            // ── Swipe-up hint ──────────────────────────────────────────────────
            Row(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(Pill)
                    .clickable { settlePanel(true) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp), tint = onScrimMuted)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Swipe up for books",
                    style = MaterialTheme.typography.labelMedium,
                    color = onScrimMuted
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── Books panel (slides up over the info page) ─────────────────────────
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .graphicsLayer { translationY = panelHeightPx * (1f - dragProgress.floatValue) }
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header — drag down here to close.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { dragBy(it) },
                            onDragStopped = { dragStopped(it) }
                        )
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp)
                            .size(width = 42.dp, height = 4.dp)
                            .clip(Pill)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Books",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "${books.size} in this series · tap to play",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, "Add books")
                        }
                        IconButton(onClick = { settlePanel(false) }) {
                            Icon(Icons.Default.KeyboardArrowDown, "Close book list")
                        }
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(books, key = { _, b -> b.id }) { index, book ->
                        SeriesBookRow(
                            book = book,
                            index = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < books.size - 1,
                            onClick = { viewModel.playFromBook(book.id) },
                            onMoveUp = { viewModel.moveBook(book.id, up = true) },
                            onMoveDown = { viewModel.moveBook(book.id, up = false) },
                            onRemove = { viewModel.removeBook(book.id) },
                            modifier = Modifier.animateItem()
                        )
                    }

                    if (books.isEmpty()) {
                        item {
                            Text(
                                "This series has no books yet. Tap + to add some.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showOptions) {
        series?.let { s ->
            BookOptionsSheet(
                seriesOptions = SeriesOptions(
                    series = s,
                    onSave = { viewModel.saveOptions(it) }
                ),
                onDismiss = { showOptions = false }
            )
        }
    }

    if (showAdd) {
        AddBooksSheet(
            candidates = candidates,
            onAdd = { viewModel.addBook(it) },
            onDismiss = { showAdd = false }
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(series?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename series") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Series name") }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.rename(name); showRename = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun SeriesBookRow(
    book: Book,
    index: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().pressScale()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        book.seriesOrder?.let { "%.0f".format(it) } ?: index.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            AsyncImage(
                model = book.coverArtPath?.let { File(it) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
            )
            Column(Modifier.weight(1f)) {
                Text(book.displayTitle, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val label = when (book.status) {
                    com.betteraudio.data.db.entities.BookStatus.FINISHED -> "Finished"
                    com.betteraudio.data.db.entities.BookStatus.IN_PROGRESS -> "In progress"
                    com.betteraudio.data.db.entities.BookStatus.NOT_STARTED -> "Not started"
                }
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ArrowUpward, "Move up", Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ArrowDownward, "Move down", Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, "Remove from series", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBooksSheet(
    candidates: List<Book>,
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val added = remember { mutableStateListOf<Long>() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Add books to series", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp))
            if (candidates.isEmpty()) {
                Text("No other books to add.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(candidates, key = { it.id }) { book ->
                    val isAdded = book.id in added
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = book.coverArtPath?.let { File(it) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(book.displayTitle, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (book.displayAuthor.isNotBlank()) {
                                Text(book.displayAuthor, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (isAdded) {
                            Text("Added", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        } else {
                            FilledTonalButton(onClick = { onAdd(book.id); added.add(book.id) }) { Text("Add") }
                        }
                    }
                }
            }
        }
    }
}
