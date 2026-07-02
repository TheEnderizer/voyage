package com.betteraudio.ui.series

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.ui.theme.pressScale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: (bookId: Long) -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val series by viewModel.series.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val candidates by viewModel.candidateBooks.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }

    // Open the player on whichever book started playing (resume book, or a tapped book).
    LaunchedEffect(Unit) { viewModel.openPlayer.collect { onOpenPlayer(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(series?.name ?: "Series", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${books.size} book${if (books.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { showOptions = true }, enabled = series != null) {
                        Icon(Icons.Default.Tune, "Series options")
                    }
                    IconButton(onClick = { showRename = true }) { Icon(Icons.Default.Edit, "Rename series") }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Add books") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Button(
                    onClick = { viewModel.playSeries() },
                    enabled = books.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Play series")
                }
            }

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

    if (showOptions) {
        series?.let { s ->
            SeriesOptionsSheet(
                series = s,
                onSave = { viewModel.saveOptions(it) },
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
