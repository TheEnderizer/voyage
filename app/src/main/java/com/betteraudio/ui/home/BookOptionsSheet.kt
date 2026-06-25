package com.betteraudio.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.model.BookWithProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookOptionsSheet(
    bwp: BookWithProgress,
    onDismiss: () -> Unit,
    onUpdateMetadata: (titleOverride: String?, authorOverride: String?) -> Unit,
    onUpdateSeries: (seriesName: String?, seriesOrder: Float?) -> Unit,
    onUpdateStatus: (BookStatus) -> Unit,
    onSearchOnlineCover: () -> Unit,
    onIgnore: () -> Unit,
    onDeletePermanently: (deleteFiles: Boolean) -> Unit
) {
    val book = bwp.book
    var titleInput by remember { mutableStateOf(book.titleOverride ?: book.title) }
    var authorInput by remember { mutableStateOf(book.authorOverride ?: book.author) }
    var seriesName by remember { mutableStateOf(book.seriesName ?: "") }
    var seriesOrder by remember { mutableStateOf(book.seriesOrder?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "") }
    var status by remember { mutableStateOf(book.status) }
    var showIgnoreConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteFiles by remember { mutableStateOf(false) }

    ModalBottomSheet(
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
            Text("Book Options", style = MaterialTheme.typography.titleLarge)

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
    }

    // Ignore confirmation
    if (showIgnoreConfirm) {
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
    if (showDeleteConfirm) {
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
