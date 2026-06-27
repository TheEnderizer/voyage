package com.betteraudio.ui.join

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.BookGroupRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class MetadataSource { FIRST_BOOK, MANUAL }

@HiltViewModel
class JoinOptionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val groupRepository: BookGroupRepository
) : ViewModel() {

    private val bookIdsArg: String = savedStateHandle["bookIds"] ?: ""
    private val editGroupId: Long = savedStateHandle["groupId"] ?: -1L

    val isEditing: Boolean get() = editGroupId != -1L

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    private val _coverArtPath = MutableStateFlow<String?>(null)
    val coverArtPath: StateFlow<String?> = _coverArtPath.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _metadataSource = MutableStateFlow(MetadataSource.FIRST_BOOK)
    val metadataSource: StateFlow<MetadataSource> = _metadataSource.asStateFlow()

    init {
        viewModelScope.launch {
            if (editGroupId != -1L) {
                _metadataSource.value = MetadataSource.MANUAL
                val group = groupRepository.getGroupById(editGroupId)
                if (group != null) {
                    _name.value = group.name
                    _coverArtPath.value = group.coverArtPath
                    _speed.value = group.playbackSpeed
                }
                _books.value = groupRepository.getBooksForGroupOnce(editGroupId)
            } else {
                val ids = bookIdsArg.split(",").mapNotNull { it.trim().toLongOrNull() }
                val loaded = repository.getBooksByIds(ids)
                val idOrder = ids.withIndex().associate { (i, id) -> id to i }
                _books.value = loaded.sortedBy { idOrder[it.id] ?: Int.MAX_VALUE }
                syncFromFirstBook()
            }
        }
    }

    private fun syncFromFirstBook() {
        val first = _books.value.firstOrNull() ?: return
        _name.value = first.title
        _coverArtPath.value = first.coverArtPath
    }

    fun setMetadataSource(src: MetadataSource) {
        _metadataSource.value = src
        if (src == MetadataSource.FIRST_BOOK) syncFromFirstBook()
    }

    fun setName(n: String) {
        if (n != _name.value) _metadataSource.value = MetadataSource.MANUAL
        _name.value = n
    }
    fun setSpeed(s: Float) { _speed.value = s }
    fun setCoverArt(path: String?) { _coverArtPath.value = path }

    fun reorder(newOrder: List<Book>) {
        _books.value = newOrder
        if (_metadataSource.value == MetadataSource.FIRST_BOOK) syncFromFirstBook()
    }

    fun sortAlphabetically() {
        _books.value = _books.value.sortedBy { it.title.lowercase() }
    }

    fun updateCoverArt(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val dir = File(context.filesDir, "group_covers").also { it.mkdirs() }
                val dest = File(dir, "group_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                _metadataSource.value = MetadataSource.MANUAL
                _coverArtPath.value = dest.absolutePath
            } catch (_: Exception) {}
        }
    }

    fun save(onDone: (groupId: Long) -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            val orderedIds = _books.value.map { it.id }
            val groupId = if (editGroupId != -1L) {
                val group = BookGroup(
                    id = editGroupId,
                    name = _name.value,
                    coverArtPath = _coverArtPath.value,
                    playbackSpeed = _speed.value
                )
                groupRepository.updateGroup(group, orderedIds)
                editGroupId
            } else {
                groupRepository.createGroup(
                    name = _name.value,
                    coverArtPath = _coverArtPath.value,
                    playbackSpeed = _speed.value,
                    orderedBookIds = orderedIds,
                    manual = true
                )
            }
            _saving.value = false
            onDone(groupId)
        }
    }
}
