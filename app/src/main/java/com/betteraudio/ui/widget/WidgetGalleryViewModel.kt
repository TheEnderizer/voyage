package com.betteraudio.ui.widget

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.dao.WidgetBindingDao
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.widget.WidgetUpdater
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WidgetGalleryRow(val design: WidgetDesign, val placedCount: Int)

@HiltViewModel
class WidgetGalleryViewModel @Inject constructor(
    private val designDao: WidgetDesignDao,
    private val bindingDao: WidgetBindingDao,
    private val widgetUpdater: WidgetUpdater,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val rows: StateFlow<List<WidgetGalleryRow>> = combine(
        designDao.observeAll(), bindingDao.observeAllBindings()
    ) { designs, bindings ->
        val counts = bindings.groupingBy { it.designId }.eachCount()
        designs.map { WidgetGalleryRow(it, counts[it.id] ?: 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteDesign(id: Long) = viewModelScope.launch {
        designDao.deleteById(id)
        widgetUpdater.onDesignDeleted(id)
        sweepOrphanWidgetImages(context, designDao)
    }

    fun duplicateDesign(design: WidgetDesign) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        designDao.upsert(design.copy(id = 0, name = "${design.name} copy", createdAt = now, updatedAt = now))
    }

    fun renameDesign(id: Long, name: String) = viewModelScope.launch {
        val design = designDao.getById(id) ?: return@launch
        designDao.upsert(design.copy(name = name.ifBlank { "Untitled widget" }, updatedAt = System.currentTimeMillis()))
    }
}
