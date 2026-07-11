package com.betteraudio.ui.widget

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.dao.CustomWidgetDesignDao
import com.betteraudio.data.db.entities.CustomWidgetDesign
import com.betteraudio.widget.custom.WidgetBackground
import com.betteraudio.widget.custom.WidgetElement
import com.betteraudio.widget.custom.WidgetElementCodec
import com.betteraudio.widget.custom.WidgetElementType
import com.betteraudio.widget.custom.WidgetGrid
import com.betteraudio.widget.custom.WidgetSizeBucket
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@HiltViewModel
class WidgetEditorViewModel @Inject constructor(
    private val designDao: CustomWidgetDesignDao,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(WidgetEditorState())
    val state: StateFlow<WidgetEditorState> = _state.asStateFlow()

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
        val default = WidgetElement(
            type = type,
            x = 0.3f, y = 0.4f, w = 0.4f, h = if (type.isText) 0.15f else 0.25f,
            fontSizeSp = if (type.isText) 14f else null,
            durationMs = if (type == WidgetElementType.SLEEP_TIMER) 15 * 60_000L else null
        )
        val snapped = if (default.type.isInteractive) snapElement(default) else default
        val list = _state.value.elements + snapped
        _state.value = _state.value.copy(elements = list, selectedIndex = list.lastIndex)
    }

    fun select(index: Int) { _state.value = _state.value.copy(selectedIndex = index) }

    fun updateElement(index: Int, transform: (WidgetElement) -> WidgetElement) {
        val list = _state.value.elements.toMutableList()
        if (index !in list.indices) return
        var updated = transform(list[index])
        if (updated.type.isInteractive) updated = snapElement(updated)
        list[index] = updated
        _state.value = _state.value.copy(elements = list)
    }

    fun deleteSelected() {
        val idx = _state.value.selectedIndex
        if (idx !in _state.value.elements.indices) return
        val list = _state.value.elements.toMutableList().apply { removeAt(idx) }
        _state.value = _state.value.copy(elements = list, selectedIndex = -1)
    }

    fun setElementImage(index: Int, uri: Uri) = viewModelScope.launch {
        val path = copyToFiles(uri, "widget_el") ?: return@launch
        updateElement(index) { it.copy(imagePath = path) }
    }

    private fun snapElement(el: WidgetElement): WidgetElement {
        val snapped = WidgetGrid.snap(el.x, el.y, el.w, el.h)
        return el.copy(x = snapped[0], y = snapped[1], w = snapped[2], h = snapped[3])
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
        _state.value = s.copy(saved = true)
    }
}
