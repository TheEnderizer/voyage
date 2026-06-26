package com.betteraudio.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.data.repository.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository
) : ViewModel() {

    val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    val bookWithProgress: StateFlow<BookWithProgress?> =
        repository.getBookWithProgress(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Tracks the cover we've already kicked a bake for, so a failed/empty bake doesn't
    // retrigger on every flow emission. A genuine cover change updates the path → re-bakes.
    private var lastFxCover: String? = null

    init {
        repository.getBookById(bookId)
            .onEach { b ->
                val cover = b?.coverArtPath
                if (cover != null && b.coverFxPath == null && cover != lastFxCover) {
                    lastFxCover = cover
                    repository.ensureCoverFx(bookId)
                }
            }
            .launchIn(viewModelScope)
    }

    /** Re-bake the cover effect from the original image (3-dot "refresh"). */
    fun refreshCoverEffect() {
        lastFxCover = null
        viewModelScope.launch { repository.regenerateCoverFx(bookId) }
    }
}
