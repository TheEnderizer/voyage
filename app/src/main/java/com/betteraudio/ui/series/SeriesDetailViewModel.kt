package com.betteraudio.ui.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.repository.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: AudiobookRepository
) : ViewModel() {

    private val seriesName: String = checkNotNull(savedStateHandle["seriesName"])

    val books: StateFlow<List<Book>> = repository
        .getBooksInSeries(seriesName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
