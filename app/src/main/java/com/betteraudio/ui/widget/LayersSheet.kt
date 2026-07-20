package com.betteraudio.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType

/** Layer stack, topmost (last-drawn, last-tapped) element first. Reorder via up/down — this is now
 *  the ONLY place to reorder layers (the canvas toolbar dropped its own up/down buttons once the
 *  layers sheet became the dedicated place for layer management). */
@Composable
fun LayersSheet(viewModel: WidgetEditorViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val elementsTopFirst = state.doc.elements.asReversed()
    val baseLayerId = state.doc.elements.firstOrNull()?.takeIf { it.type == ElementType.BACKGROUND_LAYER }?.id

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Layers", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(elementsTopFirst, key = { it.id }) { el ->
                    LayerRow(
                        element = el,
                        isBaseLayer = el.id == baseLayerId,
                        selected = el.id == state.selectedElementId,
                        canMoveUp = elementsTopFirst.first().id != el.id,
                        canMoveDown = elementsTopFirst.last().id != el.id,
                        onSelect = { viewModel.selectElement(el.id) },
                        onMoveUp = { viewModel.selectElement(el.id); viewModel.bringForward() },
                        onMoveDown = { viewModel.selectElement(el.id); viewModel.sendBackward() },
                        onDelete = {
                            viewModel.selectElement(el.id)
                            viewModel.deleteSelected()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    element: ElementSpec,
    isBaseLayer: Boolean,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(vertical = 6.dp, horizontal = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ElementTypeIcon(element.type, modifier = Modifier.size(20.dp))
                Column {
                    Text(labelFor(element.type))
                    if (isBaseLayer) {
                        Text(
                            "Background · widget shape",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row {
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Bring forward")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Send backward")
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(2.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}
