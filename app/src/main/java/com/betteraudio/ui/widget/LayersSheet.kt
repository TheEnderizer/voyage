package com.betteraudio.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.WidgetSnapshot

/** Layer stack, topmost (last-drawn, last-tapped) element first — the Layers panel, docked inline
 *  in the editor (not a modal sheet). Reorder via up/down — this is the ONLY place to reorder
 *  layers (the canvas toolbar has no up/down buttons of its own). */
@Composable
fun LayersPanel(viewModel: WidgetEditorViewModel, snapshot: WidgetSnapshot, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val elementsTopFirst = state.doc.elements.asReversed()

    LazyColumn(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        items(elementsTopFirst, key = { it.id }) { el ->
            LayerRow(
                element = el,
                thumbnailPath = imageThumbnailPath(el, snapshot),
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

/** The real image file backing an image-type element, if one is currently set — used so the layers
 *  list can show the actual cover/custom image as its "icon" instead of a generic placeholder. */
private fun imageThumbnailPath(element: ElementSpec, snapshot: WidgetSnapshot): String? = when (element.type) {
    ElementType.BOOK_COVER -> snapshot.bookCoverPath
    ElementType.SERIES_COVER -> snapshot.seriesCoverPath ?: snapshot.bookCoverPath
    ElementType.CUSTOM_IMAGE -> element.imagePath
    else -> null
}

@Composable
private fun LayerRow(
    element: ElementSpec,
    thumbnailPath: String?,
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
                if (thumbnailPath != null) {
                    AsyncImage(
                        model = thumbnailPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)),
                    )
                } else {
                    ElementTypeIcon(element.type, modifier = Modifier.size(20.dp))
                }
                Text(labelFor(element.type))
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
