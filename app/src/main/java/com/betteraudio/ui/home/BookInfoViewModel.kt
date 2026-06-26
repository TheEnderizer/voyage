package com.betteraudio.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.data.repository.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BookInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: AudiobookRepository
) : ViewModel() {

    val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    val bookWithProgress: StateFlow<BookWithProgress?> =
        repository.getBookWithProgress(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
