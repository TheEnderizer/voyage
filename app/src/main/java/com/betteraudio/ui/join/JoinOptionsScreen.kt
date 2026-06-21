package com.betteraudio.ui.join

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JoinOptionsScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: JoinOptionsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val coverArtPath by viewModel.coverArtPath.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()

    val coverPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.updateCoverArt(context, it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isEditing) "Edit Joined Book" else "Join Books") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.sortAlphabetically() }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, "Sort alphabetically")
                    }
                }
            )
        },
        bottomBar = {
            Button(
                onClick = { viewModel.save { onSaved() } },
                enabled = !saving && name.isNotBlank() && books.size >= 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (viewModel.isEditing) "Save Changes" else "Join Books")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cover + Name row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Cover image picker
                Card(
                    onClick = { coverPickerLauncher.launch("image/*") },
                    modifier = Modifier.size(100.dp),
                    elevation = CardDefaults.cardElevation(4.dp)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (coverArtPath != null) {
                            AsyncImage(
                                model = File(coverArtPath!!),
                                contentDescription = "Cover art",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Image, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Set cover",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // Name field
                OutlinedTextField(
                    value = name,
                    onValueChange = viewModel::setName,
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Playback speed
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Playback Speed", style = MaterialTheme.typography.labelMedium)
                    Text("${"%.2f".format(speed)}×",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = speed,
                    onValueChange = { raw -> viewModel.setSpeed((raw / 0.05f).roundToInt() * 0.05f) },
                    valueRange = 0.5f..3.0f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            Text(
                "Book Order",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Long-press the drag handle (≡) to reorder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Draggable book list
            DraggableBookList(
                books = books,
                onReorder = viewModel::reorder
            )
        }
    }
}

@Composable
private fun DraggableBookList(
    books: List<Book>,
    onReorder: (List<Book>) -> Unit
) {
    val density = LocalDensity.current
    val itemHeightDp = 72.dp
    val itemHeightPx = remember(density) { with(density) { itemHeightDp.toPx() } }

    var draggingIdx by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val currentBooks by rememberUpdatedState(books)

    Column {
        books.forEachIndexed { idx, book ->
            key(book.id) {
                val isDragging = draggingIdx == idx
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeightDp)
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) dragOffsetY else 0f
                            shadowElevation = if (isDragging) 12f else 0f
                        }
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(end = 8.dp)
                ) {
                    // Drag handle
                    Icon(
                        Icons.Default.DragHandle,
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 16.dp)
                            .pointerInput(book.id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIdx = currentBooks.indexOfFirst { it.id == book.id }
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val shift = (dragOffsetY / itemHeightPx).roundToInt()
                                        val newIdx = (draggingIdx + shift)
                                            .coerceIn(0, currentBooks.lastIndex)
                                        if (newIdx != draggingIdx) {
                                            val newList = currentBooks.toMutableList()
                                            newList.add(newIdx, newList.removeAt(draggingIdx))
                                            onReorder(newList)
                                            dragOffsetY -= (newIdx - draggingIdx) * itemHeightPx
                                            draggingIdx = newIdx
                                        }
                                    },
                                    onDragEnd = { draggingIdx = -1; dragOffsetY = 0f },
                                    onDragCancel = { draggingIdx = -1; dragOffsetY = 0f }
                                )
                            }
                    )

                    // Cover thumbnail
                    AsyncImage(
                        model = book.coverArtPath?.let { File(it) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp)
                    )

                    Spacer(Modifier.width(12.dp))

                    // Book info
                    Column(Modifier.weight(1f)) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (book.author.isNotBlank()) {
                            Text(
                                book.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }

                    // Position badge
                    Text(
                        "#${idx + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}
