package com.betteraudio.ui.widget

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.dao.CustomWidgetDesignDao
import com.betteraudio.data.db.entities.CustomWidgetDesign
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.widget.custom.WidgetBackground
import com.betteraudio.widget.custom.WidgetElement
import com.betteraudio.widget.custom.WidgetElementCodec
import com.betteraudio.widget.custom.WidgetElementType
import com.betteraudio.widget.custom.WidgetGrid
import com.betteraudio.widget.custom.WidgetSizeBucket
import com.betteraudio.widget.custom.aspect
import com.betteraudio.widget.custom.squareWidthHeight
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class WidgetEditorState(
    val designId: Long = -1L,
    val name: String = "My widget",
    val sizeChosen: Boolean = false,
    val sizeBucket: WidgetSizeBucket = WidgetSizeBucket.WIDE,
    val background: WidgetBackground = WidgetBackground.BOOK_COVER,
    val backgroundValue: String = "",
    val elements: List<WidgetElement> = emptyList(),
    val selectedIndex: Int = -1,
    val loaded: Boolean = false,
    val saved: Boolean = false
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class WidgetEditorViewModel @Inject constructor(
    private val designDao: CustomWidgetDesignDao,
    settings: SettingsStore,
    repository: AudiobookRepository,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetEditorState())
    val state: StateFlow<WidgetEditorState> = _state.asStateFlow()

    /** The last-played book's cover, so the BOOK_COVER/SERIES_COVER background preview shows a
     *  real cover instead of a generic placeholder forever — same idea as the real widget's own
     *  idle-state fallback. Null when nothing has ever played. */
    val lastPlayedCoverPath: StateFlow<String?> = settings.lastPlayedBookId
        .flatMapLatest { id -> if (id == -1L) kotlinx.coroutines.flow.flowOf(null) else repository.getBookById(id) }
        .map { it?.coverArtPath }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        val designId = savedStateHandle.get<Long>("designId") ?: -1L
        if (designId == -1L) {
            _state.value = _state.value.copy(loaded = true)
        } else {
            viewModelScope.launch {
                val design = designDao.getById(designId)
                if (design != null) {
                    _state.value = WidgetEditorState(
                        designId = design.id,
                        name = design.name,
                        sizeChosen = true,
                        sizeBucket = runCatching { WidgetSizeBucket.valueOf(design.sizeBucket) }.getOrDefault(WidgetSizeBucket.WIDE),
                        background = runCatching { WidgetBackground.valueOf(design.backgroundType) }.getOrDefault(WidgetBackground.BOOK_COVER),
                        backgroundValue = design.backgroundValue,
                        elements = WidgetElementCodec.decode(design.elementsJson),
                        loaded = true
                    )
                } else {
                    _state.value = _state.value.copy(loaded = true)
                }
            }
        }
    }

    fun setName(name: String) { _state.value = _state.value.copy(name = name) }

    fun chooseSize(bucket: WidgetSizeBucket) {
        _state.value = _state.value.copy(sizeBucket = bucket, sizeChosen = true)
    }

    fun setBackground(bg: WidgetBackground) {
        _state.value = _state.value.copy(background = bg, backgroundValue = if (bg == WidgetBackground.CUSTOM_COLOR || bg == WidgetBackground.CUSTOM_IMAGE) _state.value.backgroundValue else "")
    }

    fun setBackgroundColor(hex: String) {
        _state.value = _state.value.copy(background = WidgetBackground.CUSTOM_COLOR, backgroundValue = hex)
    }

    fun setBackgroundImage(uri: Uri) = viewModelScope.launch {
        val path = copyToFiles(uri, "widget_bg") ?: return@launch
        _state.value = _state.value.copy(background = WidgetBackground.CUSTOM_IMAGE, backgroundValue = path)
    }

    fun addElement(type: WidgetElementType) {
        val default = if (type.isInteractive) {
            // Icon elements are square in actual pixels, not just in normalized w/h — see
            // [squareWidthHeight]. Without this, an icon's bounding box (used for both the visible
            // border AND the drag/tap target) is a wider-or-taller-than-needed rectangle on any
            // non-square bucket (WIDE/TALL), leaving dead padding around the icon that keeps it
            // from ever reaching a corner and makes the touch target bigger than the glyph.
            val (w, h) = squareWidthHeight(_state.value.sizeBucket.aspect, ICON_SIZE_FRAC)
            WidgetElement(
                type = type, x = 0.3f, y = 0.4f, w = w, h = h,
                durationMs = if (type == WidgetElementType.SLEEP_TIMER) 15 * 60_000L else null
            )
        } else {
            WidgetElement(
                type = type,
                x = 0.3f, y = 0.4f, w = 0.4f, h = if (type.isText) 0.15f else 0.25f,
                fontSizeSp = if (type.isText) 14f else null
            )
        }
        val list = _state.value.elements + default
        _state.value = _state.value.copy(elements = list, selectedIndex = list.lastIndex)
    }

    fun select(index: Int) { _state.value = _state.value.copy(selectedIndex = index) }

    /** Applies [transform] as-is — no automatic grid re-snap. [resizeSelected] (the only x/y/w/h
     *  caller) manages its own granular sizing; re-snapping every call here used to make the +/-
     *  size buttons jump in huge (~1/6-of-canvas) steps, since [WidgetGrid.snap] expands a rect to
     *  cover every cell it touches. */
    fun updateElement(index: Int, transform: (WidgetElement) -> WidgetElement) {
        val list = _state.value.elements.toMutableList()
        if (index !in list.indices) return
        list[index] = transform(list[index])
        _state.value = _state.value.copy(elements = list)
    }

    /** Moves the element by a raw fractional delta during a drag — no grid snapping (that
     *  happens once, in [snapPosition], when the drag ends). Calling the expanding [snapElement]
     *  on every drag delta (as [updateElement] does, for the resize controls) is what used to
     *  make dragging visibly grow the element instead of moving it: a tiny delta shifts the rect
     *  onto an extra grid cell, [WidgetGrid.snap] expands the rect to cover it, repeat every
     *  frame. */
    fun moveElement(index: Int, dxFrac: Float, dyFrac: Float) {
        val list = _state.value.elements.toMutableList()
        if (index !in list.indices) return
        val current = list[index]
        list[index] = current.copy(
            x = (current.x + dxFrac).coerceIn(0f, 1f - current.w),
            y = (current.y + dyFrac).coerceIn(0f, 1f - current.h)
        )
        _state.value = _state.value.copy(elements = list)
    }

    /** Settles the element at [index] onto the grid once a drag ends, preserving its current size
     *  (see [WidgetGrid.snapPosition]). No-op for non-interactive (text/cover/image) elements,
     *  which aren't grid-constrained. */
    fun snapPosition(index: Int) {
        val list = _state.value.elements.toMutableList()
        if (index !in list.indices) return
        val el = list[index]
        if (!el.type.isInteractive) return
        val snapped = WidgetGrid.snapPosition(el.x, el.y, el.w, el.h)
        list[index] = el.copy(x = snapped[0], y = snapped[1], w = snapped[2], h = snapped[3])
        _state.value = _state.value.copy(elements = list)
    }

    fun deleteSelected() {
        val idx = _state.value.selectedIndex
        if (idx !in _state.value.elements.indices) return
        val list = _state.value.elements.toMutableList().apply { removeAt(idx) }
        _state.value = _state.value.copy(elements = list, selectedIndex = -1)
    }

    /** Grows/shrinks the selected element around its own center, in small continuous steps (no
     *  grid snapping — [WidgetGrid.colRange]/[rowRange] correctly map an arbitrary rect to the
     *  tap-target cells it overlaps regardless of exact alignment, so interactive elements don't
     *  need their SIZE grid-snapped, only their drag-end POSITION, per [snapPosition]). A uniform
     *  small step gives ~25 distinct sizes across the full range, not the ~6 the old grid-cell-sized
     *  step allowed. */
    private val RESIZE_STEP = 0.035f
    fun resizeSelected(grow: Boolean) {
        val idx = _state.value.selectedIndex
        val aspect = _state.value.sizeBucket.aspect
        updateElement(idx) { el ->
            val delta = if (grow) RESIZE_STEP else -RESIZE_STEP
            val cx = el.x + el.w / 2f
            val cy = el.y + el.h / 2f
            val (newW, newH) = if (el.type.isInteractive) {
                // Keep the icon square in pixels through every step — grow/shrink the reference
                // (height) axis and re-derive width from the bucket's aspect each time, instead of
                // adding the same delta to both independently (which only stays square if it
                // started square AND the axes have equal pixel scale, true only for SMALL/LARGE).
                val h = (el.h + delta).coerceIn(0.05f, 1f)
                squareWidthHeight(aspect, h)
            } else {
                (el.w + delta).coerceIn(0.06f, 1f) to (el.h + delta).coerceIn(0.06f, 1f)
            }
            el.copy(
                x = (cx - newW / 2f).coerceIn(0f, 1f - newW),
                y = (cy - newH / 2f).coerceIn(0f, 1f - newH),
                w = newW,
                h = newH
            )
        }
    }

    /** Fraction of the canvas HEIGHT a freshly-added icon element's square side starts at. */
    private val ICON_SIZE_FRAC = 0.22f

    fun setElementImage(index: Int, uri: Uri) = viewModelScope.launch {
        val path = copyToFiles(uri, "widget_el") ?: return@launch
        updateElement(index) { it.copy(imagePath = path) }
    }

    private suspend fun copyToFiles(uri: Uri, prefix: String): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(appContext.filesDir, "widget_images").apply { mkdirs() }
            val dest = File(dir, "${prefix}_${UUID.randomUUID()}.jpg")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest.absolutePath
        } catch (_: Exception) { null }
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        designDao.upsert(
            CustomWidgetDesign(
                id = if (s.designId == -1L) 0 else s.designId,
                name = s.name.ifBlank { "My widget" },
                sizeBucket = s.sizeBucket.name,
                backgroundType = s.background.name,
                backgroundValue = s.backgroundValue,
                elementsJson = WidgetElementCodec.encode(s.elements),
                updatedAt = System.currentTimeMillis()
            )
        )
        // Placed instances of this design otherwise keep showing the pre-edit layout/background
        // until their next natural update (resize, play/pause, etc.) — refresh immediately so
        // editing a custom widget's design is visible right away.
        com.betteraudio.widget.WidgetRender.refresh(appContext)
        _state.value = s.copy(saved = true)
    }
}
