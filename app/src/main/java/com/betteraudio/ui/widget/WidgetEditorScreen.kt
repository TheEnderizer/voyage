package com.betteraudio.ui.widget

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.widget.panels.BackgroundLayerPanel
import com.betteraudio.ui.widget.panels.IconPanel
import com.betteraudio.ui.widget.panels.ImagePanel
import com.betteraudio.ui.widget.panels.LabeledSlider
import com.betteraudio.ui.widget.panels.ShapePanel
import com.betteraudio.ui.widget.panels.TextPanel
import com.betteraudio.widget.model.ElementSpec

/** Which panel the persistent dock is currently showing. */
private enum class DockTab { ELEMENTS, LAYERS, OPTIONS }

/**
 * The widget maker v2 editor: a photo-editor-style workspace — a big pannable/zoomable canvas
 * (see [EditorCanvas]) with a persistent docked panel below it (Elements / Layers / Options,
 * switchable by tab, collapsible to reclaim canvas space) instead of modal sheets that cover the
 * canvas. Copy/Delete act directly on the current selection from the dock's own header row. The
 * canvas renders through the same [com.betteraudio.widget.render.WidgetPainter] the real widget
 * uses, so what's shown here is exactly what appears on the home screen.
 */
@Composable
fun WidgetEditorScreen(onBack: () -> Unit, viewModel: WidgetEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val liveSnapshot by viewModel.liveSnapshot.collectAsStateWithLifecycle()
    var dockTab by remember { mutableStateOf(DockTab.ELEMENTS) }
    var dockExpanded by remember { mutableStateOf(true) }
    var showAspectMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val snapshot = if (state.previewMode == PreviewMode.LIVE) liveSnapshot else SAMPLE_WIDGET_SNAPSHOT
    val requestBack = { if (state.dirty) showUnsavedDialog = true else onBack() }

    // Selecting a NEW element surfaces its options immediately, like a photo editor's properties
    // panel — deselecting leaves the dock on whichever tab the user last chose instead of jumping.
    LaunchedEffect(state.selectedElementId) {
        if (state.selectedElementId != null) {
            dockTab = DockTab.OPTIONS
            dockExpanded = true
        }
    }

    BackHandler(onBack = requestBack)

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    // New widget: pick a size first, then the design is created and the editor appears.
    if (state.awaitingSizePick) {
        SizePickerStep(
            onPick = { preset -> viewModel.createWithSize(preset.aspect) },
            onBack = onBack,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                text = { Text("Change size (re-layout)") },
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
                            WIDGET_SIZE_PRESETS.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text("${preset.label}  (${preset.cols}×${preset.rows})") },
                                    onClick = { viewModel.setAspectRatio(preset.aspect); showAspectMenu = false }
                                )
                            }
                        }
                    }
                    TextButton(onClick = {
                        // Stays on the editor (unlike the unsaved-changes dialog's Save, which
                        // exits) so the confirmation is actually visible instead of flashing past
                        // during a navigation — the only signal today that a push to the real
                        // placed widget was even attempted (see CLAUDE.md's widget section for
                        // why that matters on OEMs that can drop/delay updateAppWidget calls).
                        viewModel.save {
                            scope.launch { snackbarHostState.showSnackbar("Widget updated") }
                        }
                    }) { Text("Save") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EditorCanvas(
                    viewModel = viewModel,
                    doc = state.doc,
                    layoutAspect = state.aspectRatio,
                    canvasHeightUnits = state.canvasHeightUnits,
                    snapshot = snapshot,
                    selectedElementId = state.selectedElementId,
                    isDragging = state.isDragging,
                    modifier = Modifier.fillMaxSize()
                )
                if (state.doc.elements.isEmpty()) {
                    EmptyCanvasHint(onAdd = { dockTab = DockTab.ELEMENTS; dockExpanded = true })
                }
            }

            EditorDock(
                tab = dockTab,
                onTabChange = { dockTab = it },
                expanded = dockExpanded,
                onExpandedChange = { dockExpanded = it },
                hasSelection = state.selectedElementId != null,
                onDuplicate = viewModel::duplicateSelected,
                onDelete = viewModel::deleteSelected,
            ) {
                when (dockTab) {
                    DockTab.ELEMENTS -> ElementPickerPanel(onPick = viewModel::addElement)
                    DockTab.LAYERS -> LayersPanel(viewModel, snapshot)
                    DockTab.OPTIONS -> {
                        val selected = state.selectedElement
                        if (selected != null) {
                            ElementOptionsPanel(viewModel, selected)
                        } else {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    "Select an element on the canvas to edit its options",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
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

/** Shown centered over the canvas when the design has no elements yet — a blank canvas has no
 *  fixed "background" concept anymore (see WidgetDesignDoc), so the first step is always adding
 *  one deliberately from the Elements panel. */
@Composable
private fun EmptyCanvasHint(onAdd: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Tap + to add your first element — start with a Background",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Add element")
            }
        }
    }
}

/** The persistent docked panel: a header row of Elements/Layers/Options tabs plus Duplicate/Delete
 *  (acting directly on the current selection) and a collapse toggle, with the selected tab's
 *  content docked below — never a modal overlay, so it never blocks the canvas underneath it; the
 *  canvas simply shares the screen with it (see the weight(1f) canvas Box above). */
@Composable
private fun EditorDock(
    tab: DockTab,
    onTabChange: (DockTab) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    hasSelection: Boolean,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DockTabButton(Icons.Default.Add, "Elements", tab == DockTab.ELEMENTS && expanded) {
                    onTabChange(DockTab.ELEMENTS); onExpandedChange(true)
                }
                DockTabButton(Icons.Default.Layers, "Layers", tab == DockTab.LAYERS && expanded) {
                    onTabChange(DockTab.LAYERS); onExpandedChange(true)
                }
                DockTabButton(Icons.Default.Tune, "Options", tab == DockTab.OPTIONS && expanded) {
                    onTabChange(DockTab.OPTIONS); onExpandedChange(true)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDuplicate, enabled = hasSelection) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate")
                }
                IconButton(onClick = onDelete, enabled = hasSelection) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
                IconButton(onClick = { onExpandedChange(!expanded) }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (expanded) "Collapse panel" else "Expand panel",
                    )
                }
            }
            if (expanded) {
                Box(Modifier.fillMaxWidth().height(280.dp)) { content() }
            }
        }
    }
}

@Composable
private fun DockTabButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            icon, contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** All of the selected element's options, docked inline. Leads with a name/icon header and a
 *  full-width opacity slider, then the same per-type panel the editor always used. */
@Composable
private fun ElementOptionsPanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ElementTypeIcon(element.type, modifier = Modifier.size(22.dp))
            Text(labelFor(element.type), style = MaterialTheme.typography.titleMedium)
        }

        LabeledSlider(
            "Opacity", element.opacity, 0.1f, 1f,
            onValueChangeFinished = viewModel::endContinuousEdit,
        ) { v -> viewModel.updateSelected(immediate = false) { it.copy(opacity = v) } }

        when {
            element.type.isBackgroundLayer -> BackgroundLayerPanel(viewModel, element)
            element.type.isControl -> IconPanel(viewModel, element)
            element.type.isText -> TextPanel(viewModel, element)
            element.type.isImage -> ImagePanel(viewModel, element)
            else -> ShapePanel(viewModel, element)
        }
    }
}
