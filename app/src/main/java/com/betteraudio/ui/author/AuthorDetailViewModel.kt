package com.betteraudio.ui.author

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.repository.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AuthorDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repository: AudiobookRepository
) : ViewModel() {

    val authorName: String = checkNotNull(savedStateHandle["authorName"])

    val books: StateFlow<List<Book>> = repository
        .getBooksByAuthor(authorName)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
