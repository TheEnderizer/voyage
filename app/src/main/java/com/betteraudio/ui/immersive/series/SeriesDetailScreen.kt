package com.betteraudio.ui.immersive.series

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.ui.components.BookInfoPanel
import com.betteraudio.ui.components.ReflectedProgressiveBlurCover
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.home.SeriesOptions
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.series.SeriesDetailViewModel
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import java.io.File
import kotlinx.coroutines.launch

/**
 * Series page — the exact same full-bleed frame as the book info page (cover backdrop,
 * progressive scrim, info block with AI synopsis and a play button). Swiping up slides in the
 * editable book list (add / remove / reorder); swiping the panel down returns to the info page.
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
    // 0 = info page, 1 = books panel fully up.
    val panelAnim = remember { Animatable(0f) }
    val panelOpen = panelAnim.value > 0.5f
    fun settlePanel(open: Boolean) = scope.launch {
        panelAnim.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.85f, stiffness = 380f))
    }
    BackHandler(enabled = panelOpen) { settlePanel(false) }

    val onScrim = ImmersiveStyle.scrimText()
    val onScrimMuted = ImmersiveStyle.scrimText(muted = true)

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val panelHeightPx = constraints.maxHeight * 0.84f

        fun dragBy(delta: Float) = scope.launch {
            panelAnim.snapTo((panelAnim.value - delta / panelHeightPx).coerceIn(0f, 1f))
        }
        fun dragStopped(velocity: Float) {
            val open = velocity < -900f || (velocity <= 900f && panelAnim.value > 0.4f)
            settlePanel(open)
        }

        // ── Cover + reflection background (identical to the book info page) ────
        val coverPath = series?.coverArtPath
            ?: books.firstOrNull { it.coverArtPath != null }?.coverArtPath
        Box(Modifier.fillMaxSize().clipToBounds()) {
            ReflectedProgressiveBlurCover(
                coverPath = coverPath,
                bakedPath = series?.coverFxPath,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f    to Color.Black.copy(alpha = 0.15f),
                    0.38f to Color.Black.copy(alpha = 0.04f),
                    0.54f to Color.Black.copy(alpha = 0.52f),
                    0.75f to Color.Black.copy(alpha = 0.86f),
                    1f    to Color.Black.copy(alpha = 0.97f)
                )
            )
        )

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
                    onDragStopped = { dragStopped(it) }
                )
        ) {
            // ── Top bar ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", onClick = onBack)
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
                    ScrimButton(Icons.Default.MoreVert, "More") { showOverflow = true }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }, containerColor = ImmersiveStyle.menuColor()) {
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

            Spacer(Modifier.weight(1f))

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
            // Near-opaque frosted: lets the cover backdrop glow through faintly while keeping
            // the list readable — a fully solid sheet looks pasted-on in the Immersive theme.
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .graphicsLayer { translationY = panelHeightPx * (1f - panelAnim.value) }
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
            containerColor = ImmersiveStyle.dialogColor(),
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
        // Frosted over the (already slightly translucent) panel, matching the app-wide card look.
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = com.betteraudio.ui.components.appSheetColor()) {
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
