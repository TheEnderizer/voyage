package com.betteraudio.ui.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.playback.SeriesPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val seriesRepository: SeriesRepository,
    private val repository: AudiobookRepository,
    private val seriesPlayer: SeriesPlayer
) : ViewModel() {

    val seriesId: Long = checkNotNull(savedStateHandle["seriesId"])

    val series: StateFlow<Series?> = seriesRepository.getSeries(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val books: StateFlow<List<Book>> = seriesRepository.getBooksInSeries(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Books not already in this series — candidates for the "add books" picker. */
    val candidateBooks: StateFlow<List<Book>> =
        combine(repository.getAllBooks(), books) { all, members ->
            val memberIds = members.map { it.id }.toSet()
            all.filter { !it.isIgnored && it.id !in memberIds }
                .sortedBy { it.displayTitle.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun playSeries() = viewModelScope.launch { seriesPlayer.playSeries(seriesId) }
    fun playFromBook(bookId: Long) = viewModelScope.launch { seriesPlayer.playSeries(seriesId, startBookId = bookId) }

    fun addBook(bookId: Long) = viewModelScope.launch { seriesRepository.addBookToSeries(bookId, seriesId) }
    fun removeBook(bookId: Long) = viewModelScope.launch { seriesRepository.removeBookFromSeries(bookId) }
    fun rename(name: String) = viewModelScope.launch { if (name.isNotBlank()) seriesRepository.renameSeries(seriesId, name) }

    /** Move a member up or down and renumber the whole series so the order sticks. */
    fun moveBook(bookId: Long, up: Boolean) {
        val ordered = books.value.toMutableList()
        val i = ordered.indexOfFirst { it.id == bookId }
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j !in ordered.indices) return
        ordered.add(j, ordered.removeAt(i))
        viewModelScope.launch {
            ordered.forEachIndexed { idx, b -> seriesRepository.setBookOrder(b.id, (idx + 1).toFloat()) }
        }
    }
}
