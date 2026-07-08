package com.betteraudio.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.repository.AudiobookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Title/cover/progress for a book that isn't (yet) loaded into [com.betteraudio.playback.PlayerController]
 *  — the mini bar's normal data source. Used only for the cold-start restore case: the sheet has a
 *  [PlayerTarget] (from [PlayerSheetController.restore]) but nothing has actually started playing,
 *  so `PlayerController.playbackState` is still empty. */
data class MiniPlayerRestoreInfo(val title: String, val coverArtPath: String?, val progress: Float)

@HiltViewModel
class MiniPlayerRestoreViewModel @Inject constructor(
    private val repository: AudiobookRepository
) : ViewModel() {
    private val bookIdFlow = MutableStateFlow(-1L)

    fun setBookId(id: Long) {
        if (bookIdFlow.value != id) bookIdFlow.value = id
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val info: StateFlow<MiniPlayerRestoreInfo?> = bookIdFlow
        .flatMapLatest { id ->
            if (id == -1L) flowOf(null)
            else repository.getBookWithProgress(id).map { bwp ->
                bwp?.let { MiniPlayerRestoreInfo(it.book.displayTitle, it.book.coverArtPath, it.progressFraction) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
