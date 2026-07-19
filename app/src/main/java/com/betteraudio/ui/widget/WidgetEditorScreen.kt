package com.betteraudio.ui.widget

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.widget.panels.BackgroundPanel
import com.betteraudio.ui.widget.panels.IconPanel
import com.betteraudio.ui.widget.panels.ImagePanel
import com.betteraudio.ui.widget.panels.ShapePanel
import com.betteraudio.ui.widget.panels.TextPanel

/**
 * The widget maker v2 editor: a free-placement canvas (no snapping — drag/pinch/rotate anywhere),
 * a context-sensitive style panel, an element picker, and a layers sheet. The canvas renders
 * through the same [com.betteraudio.widget.render.WidgetPainter] the real widget uses, so what's
 * shown here is exactly what appears on the home screen.
 */
@Composable
fun WidgetEditorScreen(onBack: () -> Unit, viewModel: WidgetEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveSnapshot by viewModel.liveSnapshot.collectAsStateWithLifecycle()
    var showElementPicker by remember { mutableStateOf(false) }
    var showLayers by remember { mutableStateOf(false) }
    var showAspectMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    val snapshot = if (state.previewMode == PreviewMode.LIVE) liveSnapshot else SAMPLE_WIDGET_SNAPSHOT
    val requestBack = { if (state.dirty) showUnsavedDialog = true else onBack() }

    BackHandler(onBack = requestBack)

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.name,
                        onValueChange = viewModel::setName,
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Change aspect ratio") },
                                onClick = { showOverflowMenu = false; showAspectMenu = true }
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.previewMode == PreviewMode.LIVE) "Preview: Sample data" else "Preview: Live playback") },
                                onClick = {
                                    showOverflowMenu = false
                                    viewModel.setPreviewMode(if (state.previewMode == PreviewMode.LIVE) PreviewMode.SAMPLE else PreviewMode.LIVE)
                                }
                            )
                        }
                        DropdownMenu(expanded = showAspectMenu, onDismissRequest = { showAspectMenu = false }) {
                            ASPECT_PRESETS.forEach { (ratio, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { viewModel.setAspectRatio(ratio); showAspectMenu = false }
                                )
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.save(onSaved = onBack) }) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                EditorCanvas(
                    viewModel = viewModel,
                    doc = state.doc,
                    aspectRatio = state.aspectRatio,
                    canvasHeightUnits = state.canvasHeightUnits,
                    snapshot = snapshot,
                    selectedElementId = state.selectedElementId,
                    isDragging = state.isDragging,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SelectionToolbar(
                viewModel = viewModel,
                selectedOpacity = state.selectedElement?.opacity,
                onAddElement = { showElementPicker = true },
                onShowLayers = { showLayers = true },
            )

            Box(
                Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                val selected = state.selectedElement
                when {
                    selected == null -> BackgroundPanel(viewModel, state.doc.background)
                    selected.type.isControl -> IconPanel(viewModel, selected)
                    selected.type.isText -> TextPanel(viewModel, selected)
                    selected.type.isImage -> ImagePanel(viewModel, selected)
                    else -> ShapePanel(viewModel, selected)
                }
            }
        }
    }

    if (showElementPicker) {
        ElementPickerSheet(onDismiss = { showElementPicker = false }, onPick = viewModel::addElement)
    }
    if (showLayers) {
        LayersSheet(viewModel, onDismiss = { showLayers = false })
    }
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("Save your changes to this widget before leaving?") },
            confirmButton = {
                TextButton(onClick = { viewModel.save(onSaved = onBack) }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onBack) { Text("Discard") }
                    TextButton(onClick = { showUnsavedDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun SelectionToolbar(
    viewModel: WidgetEditorViewModel,
    selectedOpacity: Float?,
    onAddElement: () -> Unit,
    onShowLayers: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val hasSelection = selectedOpacity != null
        IconButton(onClick = viewModel::sendBackward, enabled = hasSelection) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Send backward")
        }
        IconButton(onClick = viewModel::bringForward, enabled = hasSelection) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Bring forward")
        }
        IconButton(onClick = viewModel::duplicateSelected, enabled = hasSelection) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate")
        }
        IconButton(onClick = viewModel::deleteSelected, enabled = hasSelection) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
        if (hasSelection) {
            Slider(
                value = selectedOpacity ?: 1f,
                onValueChange = { v -> viewModel.updateSelected(immediate = false) { it.copy(opacity = v) } },
                onValueChangeFinished = viewModel::endContinuousEdit,
                valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onShowLayers) {
            Icon(Icons.Default.Layers, contentDescription = "Layers")
        }
        Spacer(Modifier.width(4.dp))
        FloatingActionButton(onClick = onAddElement) {
            Icon(Icons.Default.Add, contentDescription = "Add element")
        }
    }
}
