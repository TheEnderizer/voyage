package com.betteraudio.ui.widget

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.widget.model.WidgetDesignCodec
import com.betteraudio.widget.render.WidgetPainter

/** Design library: replaces the old inline Settings widget list. Reachable from Settings and from
 *  the launcher's "Create new widget" configure flow. */
@Composable
fun WidgetGalleryScreen(
    onBack: () -> Unit,
    onEditDesign: (Long) -> Unit,
    viewModel: WidgetGalleryViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    var renameTarget by remember { mutableStateOf<WidgetDesign?>(null) }
    var deleteTarget by remember { mutableStateOf<WidgetGalleryRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widget designs") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditDesign(-1L) }) {
                Icon(Icons.Default.Add, contentDescription = "Create widget")
            }
        }
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No widget designs yet — tap + to create one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxSize()
            ) {
                items(rows, key = { it.design.id }) { row ->
                    GalleryCard(
                        row = row,
                        onEdit = { onEditDesign(row.design.id) },
                        onRename = { renameTarget = row.design },
                        onDuplicate = { viewModel.duplicateDesign(row.design) },
                        onDelete = { deleteTarget = row },
                    )
                }
            }
        }
    }

    renameTarget?.let { design ->
        RenameDialog(
            initial = design.name,
            onConfirm = { name -> viewModel.renameDesign(design.id, name); renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }
    deleteTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete widget?") },
            text = {
                Text(
                    if (row.placedCount > 0)
                        "\"${row.design.name}\" is in use by ${row.placedCount} placed widget(s) — they'll show a \"pick a design\" prompt."
                    else "\"${row.design.name}\" will be deleted."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteDesign(row.design.id); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename widget") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun GalleryCard(
    row: WidgetGalleryRow,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val design = row.design
    val thumbnail = remember(design.documentJson, design.aspectRatio, accent) {
        val w = 480
        val h = (w / design.aspectRatio).toInt().coerceAtLeast(1)
        WidgetPainter.paint(
            context, WidgetDesignCodec.decode(design.documentJson), design.aspectRatio,
            SAMPLE_WIDGET_SNAPSHOT, w, h, WidgetPainter.PaintOptions(accentFallback = accent)
        )
    }

    Card(onClick = onEdit) {
        Column {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = design.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(design.aspectRatio).clip(RoundedCornerShape(4.dp))
            )
            androidx.compose.foundation.layout.Row(
                Modifier.padding(10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(design.name.ifBlank { "Untitled widget" }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (row.placedCount > 0) {
                        Text(
                            "Placed ×${row.placedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showMenu = false; onEdit() })
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { showMenu = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { showMenu = false; onDuplicate() })
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { showMenu = false; onDelete() })
                    }
                }
            }
        }
    }
}
