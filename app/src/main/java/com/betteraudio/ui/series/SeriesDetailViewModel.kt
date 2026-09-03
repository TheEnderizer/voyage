package com.betteraudio.ui.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.model.HomeGridBook
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.synopsis.SynopsisResult
import com.betteraudio.data.synopsis.SynopsisService
import com.betteraudio.playback.SeriesPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Aggregate listening progress across the whole series. */
data class SeriesProgress(val fraction: Float = 0f, val totalMs: Long = 0L)

@HiltViewModel
class SeriesDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val seriesRepository: SeriesRepository,
    private val repository: AudiobookRepository,
    private val seriesPlayer: SeriesPlayer,
    private val settings: SettingsStore,
    private val synopsisService: SynopsisService,
    private val libraryRestructurer: com.betteraudio.data.files.LibraryRestructurer
) : ViewModel() {

    val seriesId: Long = checkNotNull(savedStateHandle["seriesId"])

    val series: StateFlow<Series?> = seriesRepository.getSeries(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val books: StateFlow<List<Book>> = seriesRepository.getBooksInSeries(seriesId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ordered member books with their progress — drives the aggregate bar. Uses the lightweight
     *  home-grid projection (see HomeGridBook) since only totalDurationMs/progressFraction are
     *  needed here, never the member books' full audio file lists. */
    val booksWithProgress: StateFlow<List<HomeGridBook>> =
        combine(books, repository.getHomeGridBooks()) { ordered, all ->
            val byId = all.associateBy { it.id }
            ordered.mapNotNull { byId[it.id] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<SeriesProgress> =
        booksWithProgress
            .map { gridBooks ->
                val total = gridBooks.sumOf { it.totalDurationMs }
                val played = gridBooks.sumOf { (it.progressFraction * it.totalDurationMs).toLong() }
                SeriesProgress(
                    fraction = if (total > 0) (played.toFloat() / total).coerceIn(0f, 1f) else 0f,
                    totalMs = total
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesProgress())

    /** Books not already in this series — candidates for the "add books" picker. */
    val candidateBooks: StateFlow<List<Book>> =
        combine(repository.getAllBooks(), books) { all, members ->
            val memberIds = members.map { it.id }.toSet()
            all.filter { !it.isIgnored && it.id !in memberIds }
                .sortedBy { it.displayTitle.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Emits the book id that started playing, so the screen can open the player on it.
    private val _openPlayer = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val openPlayer: SharedFlow<Long> = _openPlayer.asSharedFlow()

    // ── AI synopsis (stored in Series.description, generated like a book's) ────
    private val _synopsisGenerating = MutableStateFlow(false)
    val synopsisGenerating: StateFlow<Boolean> = _synopsisGenerating.asStateFlow()

    // Lazily bake the series backdrop effect the first time this series is opened (or after its
    // cover changes) — mirrors PlayerViewModel's book cover-fx bake so the series screen also
    // draws one cached bitmap instead of live-blurring.
    private var lastFxCover: String? = null
    init {
        series
            .onEach { s ->
                val cover = s?.coverArtPath
                if (cover != null && s.coverFxPath == null && cover != lastFxCover) {
                    lastFxCover = cover
                    seriesRepository.ensureSeriesCoverFx(seriesId)
                }
            }
            .launchIn(viewModelScope)
    }

    init {
        // Auto-generate once the series is loaded, has no synopsis yet, and a key exists.
        combine(series, books, settings.geminiApiKey) { s, members, key -> Triple(s, members, key) }
            .filter { (s, _, key) -> s != null && s.description.isNullOrBlank() && key.isNotBlank() }
            .onEach { (s, members, _) ->
                if (_synopsisGenerating.value) return@onEach
                runSynopsisGeneration(s!!, members)
            }
            .launchIn(viewModelScope)
    }

    private suspend fun runSynopsisGeneration(s: Series, members: List<Book>) {
        _synopsisGenerating.value = true
        val author = s.author?.takeIf { it.isNotBlank() }
            ?: members.firstOrNull { it.displayAuthor.isNotBlank() }?.displayAuthor
        when (val result = synopsisService.generateSeriesSynopsis(s.name, author)) {
            is SynopsisResult.Success ->
                seriesRepository.updateSeries(s.copy(description = result.text))
            is SynopsisResult.Error -> { /* silent — the panel simply shows no synopsis */ }
        }
        _synopsisGenerating.value = false
    }

    /**
     * Which member the Companion sheet should open on. A reveal cursor is per-book
     * (docs/companion-packs.md §6), so a series has no cursor of its own — the honest answer is
     * "the book you are actually in", i.e. exactly the member Play series would resume, resolved
     * by [com.betteraudio.playback.SeriesResume] without starting playback. -1 while the series is
     * empty or still loading; the caller must not open the sheet on that.
     */
    private val _companionBookId = MutableStateFlow(-1L)
    val companionBookId: StateFlow<Long> = _companionBookId.asStateFlow()

    fun openCompanion() = viewModelScope.launch {
        _companionBookId.value = seriesPlayer.resumeBookId(seriesId)
    }

    fun closeCompanion() { _companionBookId.value = -1L }

    fun playSeries() = viewModelScope.launch {
        val id = seriesPlayer.playSeries(seriesId)
        if (id != -1L) _openPlayer.emit(id)
    }
    fun playFromBook(bookId: Long) = viewModelScope.launch {
        val id = seriesPlayer.playSeries(seriesId, startBookId = bookId)
        if (id != -1L) _openPlayer.emit(id)
    }

    fun addBook(bookId: Long) = viewModelScope.launch { seriesRepository.addBookToSeries(bookId, seriesId) }
    fun removeBook(bookId: Long) = viewModelScope.launch { seriesRepository.removeBookFromSeries(bookId) }
    fun rename(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) {
            seriesRepository.renameSeries(seriesId, name)
            // The series name is part of the folder scheme (AUTHOR_SERIES_BOOK /
            // AUTHOR_DASH_SERIES_BOOK), so a rename needs every member's folder restructured too.
            val memberIds = seriesRepository.getBooksInSeriesOnce(seriesId).map { it.id }
            libraryRestructurer.restructureBooks(memberIds)
        }
    }
    fun saveOptions(updated: Series) =
        viewModelScope.launch {
            seriesRepository.updateSeries(updated)
            // Author/narrator are metadata: applying them to the series writes them onto every
            // member book so they actually show in each book's options, the grid and the player.
            // (Per-book playback settings still stay per-book via the audio cascade.)
            val members = seriesRepository.getBooksInSeriesOnce(updated.id)
            updated.author?.takeIf { it.isNotBlank() }?.let { author ->
                members.forEach { repository.updateBookMetadata(it.id, it.titleOverride, author) }
            }
            updated.narrator?.takeIf { it.isNotBlank() }?.let { narrator ->
                members.forEach { repository.updateBookNarrator(it.id, narrator) }
            }
            // Keep every member's on-disk folder in step with the (possibly new) effective author.
            libraryRestructurer.restructureBooks(members.map { it.id })
        }

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
