package com.betteraudio.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementSpec

/** Layer stack, topmost (last-drawn, last-tapped) element first. Reorder via up/down rather than
 *  drag-to-reorder — simpler and just as functional for the typical handful of elements a widget
 *  design has. */
@Composable
fun LayersSheet(viewModel: WidgetEditorViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val elementsTopFirst = state.doc.elements.asReversed()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Layers", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.padding(top = 8.dp)) {
                items(elementsTopFirst, key = { it.id }) { el ->
                    LayerRow(
                        element = el,
                        selected = el.id == state.selectedElementId,
                        onSelect = { viewModel.selectElement(el.id) },
                        onMoveUp = { viewModel.selectElement(el.id); viewModel.bringForward() },
                        onMoveDown = { viewModel.selectElement(el.id); viewModel.sendBackward() },
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    element: ElementSpec,
    selected: Boolean,
    onSelect: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Surface(
        onClick = onSelect,
        color = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
        else androidx.compose.material3.MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(vertical = 4.dp, horizontal = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(element.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
            Row {
                IconButton(onClick = onMoveUp) { Icon(Icons.Default.ArrowUpward, contentDescription = "Bring forward") }
                IconButton(onClick = onMoveDown) { Icon(Icons.Default.ArrowDownward, contentDescription = "Send backward") }
            }
        }
    }
}
