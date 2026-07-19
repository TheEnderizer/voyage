package com.betteraudio.ui.widget

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.widget.WidgetStateStore
import com.betteraudio.widget.WidgetUpdater
import com.betteraudio.widget.model.BackgroundSpec
import com.betteraudio.widget.model.CANVAS_UNITS
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.IconStyle
import com.betteraudio.widget.model.ImageStyle
import com.betteraudio.widget.model.ShapeStyle
import com.betteraudio.widget.model.TextStyle
import com.betteraudio.widget.model.WidgetDesignCodec
import com.betteraudio.widget.model.WidgetDesignDoc
import com.betteraudio.widget.model.WidgetSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

data class WidgetEditorState(
    val loading: Boolean = true,
    val designId: Long = -1L,
    val name: String = "",
    val aspectRatio: Float = 2f,
    val doc: WidgetDesignDoc = WidgetDesignDoc(),
    val selectedElementId: String? = null,
    val previewMode: PreviewMode = PreviewMode.SAMPLE,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dirty: Boolean = false,
    val saved: Boolean = false,
    val isDragging: Boolean = false,
) {
    val selectedElement: ElementSpec? get() = doc.elements.find { it.id == selectedElementId }
    val canvasHeightUnits: Float get() = CANVAS_UNITS / aspectRatio
}

/** Common aspect-ratio presets offered in the editor's aspect picker. */
val ASPECT_PRESETS = listOf(
    1f to "1:1", 2f to "2:1", 0.5f to "1:2", 1.5f to "3:2", 4f to "4:1", 2f / 1f to "4:2"
).distinctBy { it.first }

@HiltViewModel
class WidgetEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val designDao: WidgetDesignDao,
    private val widgetUpdater: WidgetUpdater,
    stateStore: WidgetStateStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val requestedDesignId: Long = savedStateHandle.get<Long>("designId") ?: -1L

    private val _state = MutableStateFlow(WidgetEditorState())
    val state: StateFlow<WidgetEditorState> = _state.asStateFlow()

    val liveSnapshot: StateFlow<WidgetSnapshot> =
        stateStore.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), stateStore.current)

    private val undoStack = ArrayDeque<WidgetDesignDoc>()
    private val redoStack = ArrayDeque<WidgetDesignDoc>()
    private var pendingUndoSnapshot: WidgetDesignDoc? = null

    init {
        viewModelScope.launch {
            val design = if (requestedDesignId == -1L) {
                val now = System.currentTimeMillis()
                val starter = starterWidgetDesignDoc()
                val id = designDao.upsert(
                    WidgetDesign(
                        name = "New widget",
                        aspectRatio = 2f,
                        documentJson = WidgetDesignCodec.encode(starter),
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                designDao.getById(id)
            } else {
                designDao.getById(requestedDesignId)
            }
            if (design != null) {
                _state.value = WidgetEditorState(
                    loading = false,
                    designId = design.id,
                    name = design.name,
                    aspectRatio = design.aspectRatio,
                    doc = WidgetDesignCodec.decode(design.documentJson),
                )
            } else {
                _state.update { it.copy(loading = false) }
            }
        }
    }

    // ── Undo/redo ───────────────────────────────────────────────────────────

    private fun mutateDoc(immediate: Boolean = true, transform: (WidgetDesignDoc) -> WidgetDesignDoc) {
        val before = _state.value.doc
        if (immediate) {
            pushUndo(before)
        } else if (pendingUndoSnapshot == null) {
            pendingUndoSnapshot = before
        }
        _state.update { it.copy(doc = transform(before), dirty = true, isDragging = !immediate) }
    }

    /** Ends a continuous gesture (drag/slider) started with a non-immediate [mutateDoc] call,
     *  committing one undo entry for the whole gesture instead of one per delta. */
    fun endContinuousEdit() {
        val snap = pendingUndoSnapshot
        pendingUndoSnapshot = null
        _state.update { it.copy(isDragging = false) }
        if (snap != null && snap != _state.value.doc) pushUndo(snap)
    }

    private fun pushUndo(doc: WidgetDesignDoc) {
        undoStack.addLast(doc)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        refreshUndoRedoFlags()
    }

    private fun refreshUndoRedoFlags() {
        _state.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    fun undo() {
        val doc = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.doc)
        _state.update { it.copy(doc = doc, dirty = true) }
        refreshUndoRedoFlags()
    }

    fun redo() {
        val doc = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.doc)
        _state.update { it.copy(doc = doc, dirty = true) }
        refreshUndoRedoFlags()
    }

    // ── Selection ───────────────────────────────────────────────────────────

    fun selectElement(id: String?) {
        _state.update { it.copy(selectedElementId = id) }
    }

    // ── Element lifecycle ───────────────────────────────────────────────────

    fun addElement(type: ElementType) {
        val el = defaultElementFor(type, _state.value.aspectRatio)
        mutateDoc { it.copy(elements = it.elements + el) }
        selectElement(el.id)
    }

    fun deleteSelected() {
        val id = _state.value.selectedElementId ?: return
        mutateDoc { it.copy(elements = it.elements.filterNot { e -> e.id == id }) }
        selectElement(null)
    }

    fun duplicateSelected() {
        val el = _state.value.selectedElement ?: return
        val s = _state.value
        val copy = el.copy(
            id = UUID.randomUUID().toString(),
            x = (el.x + 30f).coerceAtMost(CANVAS_UNITS - el.w),
            y = (el.y + 30f).coerceAtMost(s.canvasHeightUnits - el.h),
        )
        mutateDoc { it.copy(elements = it.elements + copy) }
        selectElement(copy.id)
    }

    fun bringForward() {
        val id = _state.value.selectedElementId ?: return
        mutateDoc { doc ->
            val i = doc.elements.indexOfFirst { it.id == id }
            if (i < 0 || i == doc.elements.lastIndex) return@mutateDoc doc
            doc.copy(elements = doc.elements.toMutableList().apply { add(i + 1, removeAt(i)) })
        }
    }

    fun sendBackward() {
        val id = _state.value.selectedElementId ?: return
        mutateDoc { doc ->
            val i = doc.elements.indexOfFirst { it.id == id }
            if (i <= 0) return@mutateDoc doc
            doc.copy(elements = doc.elements.toMutableList().apply { add(i - 1, removeAt(i)) })
        }
    }

    fun reorderElements(newIdOrder: List<String>) {
        mutateDoc { doc ->
            val byId = doc.elements.associateBy { it.id }
            doc.copy(elements = newIdOrder.mapNotNull { byId[it] })
        }
    }

    // ── Transform (canvas gestures — non-immediate, commit with endContinuousEdit) ────────────

    fun moveSelected(dxUnits: Float, dyUnits: Float) {
        val id = _state.value.selectedElementId ?: return
        val s = _state.value
        mutateDoc(immediate = false) { doc ->
            doc.copy(elements = doc.elements.map { e ->
                if (e.id != id) e else e.copy(
                    x = (e.x + dxUnits).coerceIn(-e.w + 8f, CANVAS_UNITS - 8f),
                    y = (e.y + dyUnits).coerceIn(-e.h + 8f, s.canvasHeightUnits - 8f),
                )
            })
        }
    }

    fun resizeSelected(corner: ResizeCorner, dxUnits: Float, dyUnits: Float) {
        val id = _state.value.selectedElementId ?: return
        mutateDoc(immediate = false) { doc ->
            doc.copy(elements = doc.elements.map { e -> if (e.id == id) applyResize(e, corner, dxUnits, dyUnits) else e })
        }
    }

    fun rotateSelected(newAngleDeg: Float) {
        val id = _state.value.selectedElementId ?: return
        mutateDoc(immediate = false) { doc ->
            doc.copy(elements = doc.elements.map { e -> if (e.id == id) e.copy(rotationDeg = newAngleDeg) else e })
        }
    }

    private fun applyResize(e: ElementSpec, corner: ResizeCorner, dx: Float, dy: Float): ElementSpec {
        val minSize = 20f
        if (e.type.isControl) {
            // Square resize (icons/glyphs), anchored at the corner opposite the one being dragged.
            val growDx = when (corner) {
                ResizeCorner.TOP_RIGHT, ResizeCorner.BOTTOM_RIGHT -> dx
                ResizeCorner.TOP_LEFT, ResizeCorner.BOTTOM_LEFT -> -dx
            }
            val newSize = (e.w + growDx).coerceAtLeast(minSize)
            var x = e.x
            var y = e.y
            when (corner) {
                ResizeCorner.BOTTOM_RIGHT -> {}
                ResizeCorner.BOTTOM_LEFT -> x = e.x + (e.w - newSize)
                ResizeCorner.TOP_RIGHT -> y = e.y + (e.h - newSize)
                ResizeCorner.TOP_LEFT -> { x = e.x + (e.w - newSize); y = e.y + (e.h - newSize) }
            }
            return e.copy(x = x, y = y, w = newSize, h = newSize)
        }
        if (e.rotationDeg != 0f) {
            // Rotated elements resize symmetrically about their center — resizing about a fixed
            // corner under rotation needs extra geometry that isn't worth the complexity here.
            val newW = (e.w + dx * 2).coerceAtLeast(minSize)
            val newH = (e.h + dy * 2).coerceAtLeast(minSize)
            val cx = e.x + e.w / 2f
            val cy = e.y + e.h / 2f
            return e.copy(x = cx - newW / 2f, y = cy - newH / 2f, w = newW, h = newH)
        }
        var x = e.x
        var y = e.y
        var w = e.w
        var h = e.h
        when (corner) {
            ResizeCorner.BOTTOM_RIGHT -> { w = (e.w + dx).coerceAtLeast(minSize); h = (e.h + dy).coerceAtLeast(minSize) }
            ResizeCorner.TOP_LEFT -> {
                val newW = (e.w - dx).coerceAtLeast(minSize); val newH = (e.h - dy).coerceAtLeast(minSize)
                x = e.x + (e.w - newW); y = e.y + (e.h - newH); w = newW; h = newH
            }
            ResizeCorner.TOP_RIGHT -> {
                val newH = (e.h - dy).coerceAtLeast(minSize)
                y = e.y + (e.h - newH); h = newH; w = (e.w + dx).coerceAtLeast(minSize)
            }
            ResizeCorner.BOTTOM_LEFT -> {
                val newW = (e.w - dx).coerceAtLeast(minSize)
                x = e.x + (e.w - newW); w = newW; h = (e.h + dy).coerceAtLeast(minSize)
            }
        }
        return e.copy(x = x, y = y, w = w, h = h)
    }

    // ── Style panels ────────────────────────────────────────────────────────

    fun updateSelected(immediate: Boolean = true, transform: (ElementSpec) -> ElementSpec) {
        val id = _state.value.selectedElementId ?: return
        mutateDoc(immediate) { doc -> doc.copy(elements = doc.elements.map { if (it.id == id) transform(it) else it }) }
    }

    fun updateIcon(immediate: Boolean = true, transform: (IconStyle) -> IconStyle) =
        updateSelected(immediate) { it.copy(icon = transform(it.icon ?: IconStyle())) }

    fun updateText(immediate: Boolean = true, transform: (TextStyle) -> TextStyle) =
        updateSelected(immediate) { it.copy(text = transform(it.text ?: TextStyle())) }

    fun updateImage(immediate: Boolean = true, transform: (ImageStyle) -> ImageStyle) =
        updateSelected(immediate) { it.copy(image = transform(it.image ?: ImageStyle())) }

    fun updateShape(immediate: Boolean = true, transform: (ShapeStyle) -> ShapeStyle) =
        updateSelected(immediate) { it.copy(shape = transform(it.shape ?: ShapeStyle())) }

    fun updateBackground(immediate: Boolean = true, transform: (BackgroundSpec) -> BackgroundSpec) {
        mutateDoc(immediate) { it.copy(background = transform(it.background)) }
    }

    // ── Meta ────────────────────────────────────────────────────────────────

    fun setName(name: String) {
        _state.update { it.copy(name = name, dirty = true) }
    }

    fun setAspectRatio(ratio: Float) {
        val s = _state.value
        val oldH = CANVAS_UNITS / s.aspectRatio
        val newH = CANVAS_UNITS / ratio
        val scaleY = newH / oldH
        pushUndo(s.doc)
        _state.update {
            it.copy(
                aspectRatio = ratio,
                doc = it.doc.copy(elements = it.doc.elements.map { e -> e.copy(y = e.y * scaleY, h = e.h * scaleY) }),
                dirty = true,
            )
        }
    }

    fun setPreviewMode(mode: PreviewMode) {
        _state.update { it.copy(previewMode = mode) }
    }

    fun save(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            val s = _state.value
            if (s.designId == -1L) return@launch
            val now = System.currentTimeMillis()
            val existing = designDao.getById(s.designId)
            designDao.upsert(
                WidgetDesign(
                    id = s.designId,
                    name = s.name.ifBlank { "Untitled widget" },
                    aspectRatio = s.aspectRatio,
                    documentJson = WidgetDesignCodec.encode(s.doc),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            widgetUpdater.requestRender()
            sweepOrphanWidgetImages(appContext, designDao)
            _state.update { it.copy(dirty = false, saved = true) }
            onSaved()
        }
    }

}
