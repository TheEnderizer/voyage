package com.betteraudio.ui.bookinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.synopsis.SynopsisResult
import com.betteraudio.data.synopsis.SynopsisService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the standalone Book Info page (see [BookInfoScreen]) — a lighter sibling of
 *  [com.betteraudio.ui.player.PlayerViewModel] that only needs metadata + progress + synopsis,
 *  not any live playback wiring. Mirrors [com.betteraudio.ui.series.SeriesDetailViewModel]'s
 *  synopsis auto-generation. */
@HiltViewModel
class BookInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val seriesRepository: com.betteraudio.data.repository.SeriesRepository,
    private val synopsisService: SynopsisService,
    private val settings: SettingsStore,
    private val libraryRestructurer: com.betteraudio.data.files.LibraryRestructurer,
    private val coverSearchService: com.betteraudio.data.covers.CoverSearchService,
    private val bookDataStore: com.betteraudio.data.diskstore.BookDataStore,
    private val widgetUpdater: com.betteraudio.widget.WidgetUpdater
) : ViewModel() {

    val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    // ── Online cover search ─────────────────────────────────────────────────
    // Same behaviour as HomeViewModel's, scoped to this screen's one book: the picked image is
    // written into the book's own data/ folder tagged "user", which is what makes the choice
    // outrank a folder cover.png on the next scan and survive a reinstall (see
    // AudioFileScanner's cover-priority chain).

    private val _coverSearchOpen = MutableStateFlow(false)
    val coverSearchOpen: StateFlow<Boolean> = _coverSearchOpen.asStateFlow()

    fun openCoverSearch() { _coverSearchOpen.value = true }
    fun closeCoverSearch() { _coverSearchOpen.value = false }

    suspend fun searchCovers(query: String): List<String> = coverSearchService.search(query)

    fun setCoverFromUrl(imageUrl: String) {
        if (bookId == -1L) return
        viewModelScope.launch {
            val book = repository.getBookOnce(bookId)
            val bytes = coverSearchService.downloadBytes(imageUrl)
            val path = if (book != null && bytes != null)
                bookDataStore.writeCoverBytes(book.folderPath, "user", "jpg", bytes) else null
            if (path != null) {
                repository.updateCoverArt(bookId, path)
                widgetUpdater.refreshCoverIfCurrent(bookId, path)
            }
            closeCoverSearch()
        }
    }

    val bookWithProgress: StateFlow<BookWithProgress?> =
        repository.getBookWithProgress(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _synopsisGenerating = MutableStateFlow(false)
    val synopsisGenerating: StateFlow<Boolean> = _synopsisGenerating.asStateFlow()

    init {
        // Auto-generate once the book is loaded, has no synopsis yet, and a key exists — same
        // gating as SeriesDetailViewModel's series synopsis.
        combine(bookWithProgress, settings.geminiApiKey) { bwp, key -> bwp to key }
            .filter { (bwp, key) ->
                bwp != null &&
                    bwp.book.synopsis.isNullOrBlank() &&
                    bwp.book.description.isNullOrBlank() &&
                    key.isNotBlank()
            }
            .onEach { (bwp, _) ->
                if (_synopsisGenerating.value) return@onEach
                runSynopsisGeneration(bwp!!)
            }
            .launchIn(viewModelScope)
    }

    private suspend fun runSynopsisGeneration(bwp: BookWithProgress) {
        _synopsisGenerating.value = true
        when (val result = synopsisService.generateSynopsis(bwp.book.displayTitle, bwp.book.displayAuthor)) {
            is SynopsisResult.Success -> repository.updateSynopsis(bwp.book.id, result.text)
            is SynopsisResult.Error -> { /* silent — the panel simply shows no synopsis */ }
        }
        _synopsisGenerating.value = false
    }

    fun updateMetadata(titleOverride: String?, authorOverride: String?) = viewModelScope.launch {
        repository.updateBookMetadata(bookId, titleOverride, authorOverride)
        // Keep the on-disk folder in step with the (possibly new) effective author — a no-op in
        // AUTO import mode or when the book has no real single folder.
        libraryRestructurer.restructureBooks(listOf(bookId))
    }

    fun updateSeriesInfo(seriesName: String?, seriesOrder: Float?) = viewModelScope.launch {
        seriesRepository.setBookSeriesByName(bookId, seriesName, seriesOrder)
        // Series name is part of the folder scheme, same as the author above.
        libraryRestructurer.restructureBooks(listOf(bookId))
    }

    fun updateStatus(status: BookStatus) = viewModelScope.launch {
        repository.updateBookStatus(bookId, status)
    }
}
