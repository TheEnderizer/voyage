package com.betteraudio.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.covers.CoverSearchService
import com.betteraudio.data.db.entities.AuthorMeta
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.data.model.HomeGridBook
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.scanner.AudioFileScanner
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.playback.PlaybackState
import com.betteraudio.playback.PlayerController
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val TAG = "HomeViewModel"

enum class ScanStatus { Idle, Running, Done, Error }

data class ScanResult(
    val status: ScanStatus = ScanStatus.Idle,
    val booksFound: Int = 0,
    val errorMessage: String? = null
)

enum class SortOption(val label: String) {
    TITLE("Title"),
    AUTHOR("Author"),
    DATE_ADDED("Date Added"),
    DURATION("Duration"),
    LAST_PLAYED("Last Played"),
    PROGRESS("Progress"),
    SERIES("Series")
}

enum class SortDirection { ASC, DESC }

data class SortFilter(
    val option: SortOption = SortOption.TITLE,
    val direction: SortDirection = SortDirection.ASC
)

/** Status sections shown as tabs above the library grid. */
enum class LibraryTab(val label: String) {
    ALL("All"),
    LISTENING("Listening"),
    NOT_STARTED("Not started"),
    FINISHED("Finished")
}

/** Items shown in the home library grid */
/** How the home library is grouped (within the Audio section). */
enum class HomeViewMode { BOOKS, SERIES, AUTHORS }

/** Top-level home section — Audio (audiobooks) vs Ebooks (anything with a connected/standalone
 *  EPUB). Persisted; the Audio/Ebooks switch sits above the Books/Series/Authors pill. */
enum class HomeSection { AUDIO, EBOOKS }

/** A selected library item — books, series and authors can be multi-selected together. */
sealed interface SelKey {
    data class BookK(val id: Long) : SelKey
    data class SeriesK(val id: Long) : SelKey
    data class AuthorK(val name: String) : SelKey
}

/** Target of a per-view (series/author) cover search. */
sealed class CoverCollectionTarget {
    abstract val seed: String
    data class Series(val seriesId: Long, val name: String) : CoverCollectionTarget() {
        override val seed get() = name
    }
    data class Author(val name: String) : CoverCollectionTarget() {
        override val seed get() = name
    }
}

sealed class HomeGridItem {
    abstract val lastPlayedMs: Long

    /** A single book */
    data class SingleBook(
        val book: HomeGridBook,
        override val lastPlayedMs: Long
    ) : HomeGridItem()

    /** A series tile (Series view) with its member books. */
    data class SeriesItem(
        val series: Series,
        val books: List<HomeGridBook>,
        val coverPath: String?,
        override val lastPlayedMs: Long
    ) : HomeGridItem()

    /** An author tile (Authors view) with that author's books. */
    data class AuthorItem(
        val name: String,
        val coverPath: String?,
        val books: List<HomeGridBook>,
        override val lastPlayedMs: Long
    ) : HomeGridItem()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val seriesPlayer: com.betteraudio.playback.SeriesPlayer,
    private val scanner: AudioFileScanner,
    private val ebookScanner: com.betteraudio.data.scanner.EbookScanner,
    private val paragraphCache: com.betteraudio.data.ebook.ParagraphCache,
    private val settings: SettingsStore,
    private val coverSearchService: CoverSearchService,
    private val libraryRestructurer: com.betteraudio.data.files.LibraryRestructurer,
    private val libraryBootstrapper: com.betteraudio.data.diskstore.LibraryBootstrapper,
    private val bookDataStore: com.betteraudio.data.diskstore.BookDataStore,
    private val libraryDataStore: com.betteraudio.data.diskstore.LibraryDataStore,
    private val widgetUpdater: com.betteraudio.widget.WidgetUpdater,
    val playerController: PlayerController,
    @ApplicationScope private val appScope: CoroutineScope
) : ViewModel() {

    // ── Book options sheet ─────────────────────────────────────────────────

    private val _bookOptionsTarget = MutableStateFlow<Long?>(null)
    val bookOptionsTarget: StateFlow<Long?> = _bookOptionsTarget.asStateFlow()

    fun openBookOptions(bookId: Long) {
        _bookOptionsTarget.value = bookId
    }
    fun closeBookOptions() {
        _bookOptionsTarget.value = null
    }

    fun updateBookMetadata(bookId: Long, titleOverride: String?, authorOverride: String?) {
        viewModelScope.launch {
            repository.updateBookMetadata(bookId, titleOverride, authorOverride)
            // Keep the on-disk folder in step with the (possibly new) effective author — a no-op
            // in AUTO import mode or when the book has no real single folder.
            libraryRestructurer.restructureBooks(listOf(bookId))
        }
    }

    fun updateBookSeries(bookId: Long, seriesName: String?, seriesOrder: Float?) {
        viewModelScope.launch { repository.updateSeriesInfo(bookId, seriesName, seriesOrder) }
    }

    fun updateBookStatus(bookId: Long, status: BookStatus) {
        viewModelScope.launch { repository.updateBookStatus(bookId, status) }
    }

    fun ignoreBook(bookId: Long) {
        viewModelScope.launch { repository.setBookIgnored(bookId, true) }
    }

    fun deleteBook(bookId: Long, deleteFiles: Boolean) {
        viewModelScope.launch { repository.deleteBook(bookId, deleteFiles) }
    }

    // ── Ebook (EPUB) connect/disconnect ─────────────────────────────────────

    private val _ebookError = MutableStateFlow<String?>(null)
    val ebookError: StateFlow<String?> = _ebookError.asStateFlow()
    fun dismissEbookError() { _ebookError.value = null }

    fun connectEpub(bookId: Long, epubPath: String) {
        viewModelScope.launch {
            paragraphCache.invalidate(bookId)
            val ok = runCatching { ebookScanner.attachEpubToBook(bookId, File(epubPath)) }.getOrDefault(false)
            if (!ok) _ebookError.value = "Couldn't connect that EPUB — it may be DRM-protected or corrupted."
        }
    }

    fun disconnectEpub(bookId: Long) {
        viewModelScope.launch {
            repository.setEbook(bookId, null, 0)   // also clears anchors + chapter map
            paragraphCache.invalidate(bookId)
        }
    }

    // ── Online cover search ────────────────────────────────────────────────

    private val _coverSearchTargetId = MutableStateFlow<Long?>(null)
    val coverSearchTargetId: StateFlow<Long?> = _coverSearchTargetId.asStateFlow()

    fun openCoverSearch(bookId: Long) {
        _bookOptionsTarget.value = null
        _coverSearchTargetId.value = bookId
    }
    fun closeCoverSearch() { _coverSearchTargetId.value = null }

    suspend fun searchCovers(query: String): List<String> = coverSearchService.search(query)

    fun setBookCoverFromUrl(bookId: Long, imageUrl: String) {
        viewModelScope.launch {
            val book = repository.getBookOnce(bookId)
            val bytes = coverSearchService.downloadBytes(imageUrl)
            val path = if (book != null && bytes != null) {
                bookDataStore.writeCoverBytes(book.folderPath, "user", "jpg", bytes)
            } else null
            if (path != null) {
                repository.updateCoverArt(bookId, path)
                widgetUpdater.refreshCoverIfCurrent(bookId, path)
            }
            closeCoverSearch()
        }
    }

    // Per-view cover for a series or author tile — changes only that collection's cover,
    // never the member books' covers.
    private val _coverSearchCollection = MutableStateFlow<CoverCollectionTarget?>(null)
    val coverSearchCollection: StateFlow<CoverCollectionTarget?> = _coverSearchCollection.asStateFlow()

    fun openSeriesCoverSearch(seriesId: Long, name: String) {
        _coverSearchCollection.value = CoverCollectionTarget.Series(seriesId, name)
    }
    fun openAuthorCoverSearch(name: String) {
        _coverSearchCollection.value = CoverCollectionTarget.Author(name)
    }
    fun closeCollectionCoverSearch() { _coverSearchCollection.value = null }

    fun setCollectionCoverFromUrl(imageUrl: String) {
        val target = _coverSearchCollection.value ?: return
        viewModelScope.launch {
            val bytes = coverSearchService.downloadBytes(imageUrl)
            if (bytes != null) when (target) {
                is CoverCollectionTarget.Series -> {
                    val series = seriesRepository.getSeriesOnce(target.seriesId)
                    val path = series?.let { libraryDataStore.writeSeriesCoverBytes(it.name, "jpg", bytes) }
                    if (path != null) {
                        seriesRepository.setSeriesCover(target.seriesId, path)
                        seriesRepository.ensureSeriesCoverFx(target.seriesId)
                    }
                }
                is CoverCollectionTarget.Author -> {
                    val path = libraryDataStore.writeAuthorCoverBytes(target.name, "jpg", bytes)
                    if (path != null) repository.setAuthorCover(target.name, path)
                }
            }
            closeCollectionCoverSearch()
        }
    }

    /** Re-bake the reflection/progressive-blur background from the book's current cover. */
    fun refreshCoverEffect(bookId: Long) {
        viewModelScope.launch { repository.regenerateCoverFx(bookId) }
    }

    // ── Selection mode (books, series and authors) ────────────────────────────

    private val _selection = MutableStateFlow<Set<SelKey>>(emptySet())
    val selection: StateFlow<Set<SelKey>> = _selection.asStateFlow()

    fun toggleSelection(key: SelKey) {
        _selection.update { if (key in it) it - key else it + key }
    }

    fun clearSelection() { _selection.value = emptySet() }

    /** Delete every selected item. A selected book deletes directly; a selected series or author
     *  deletes ALL of its member books (and the series row / author cover-meta). */
    fun deleteSelection(deleteFiles: Boolean) {
        val keys = _selection.value
        viewModelScope.launch {
            for (k in keys) when (k) {
                is SelKey.BookK -> repository.deleteBook(k.id, deleteFiles)
                is SelKey.SeriesK -> {
                    seriesRepository.getBooksInSeriesOnce(k.id)
                        .forEach { repository.deleteBook(it.id, deleteFiles) }
                    seriesRepository.deleteSeries(k.id)
                }
                is SelKey.AuthorK -> {
                    repository.getBooksByEffectiveAuthorOnce(k.name)
                        .forEach { repository.deleteBook(it.id, deleteFiles) }
                    repository.deleteAuthorMeta(k.name)
                }
            }
            clearSelection()
        }
    }

    /** Add the selected single books into [seriesId], then leave selection mode. */
    fun addSelectedBooksToSeries(seriesId: Long) {
        val bookIds = _selection.value.filterIsInstance<SelKey.BookK>().map { it.id }
        viewModelScope.launch {
            bookIds.forEach { seriesRepository.addBookToSeries(it, seriesId) }
            clearSelection()
        }
    }

    // ── Grid items ───────────────────────────────────────────────────────────

    private val _sortFilter = MutableStateFlow(SortFilter())
    val sortFilter: StateFlow<SortFilter> = _sortFilter.asStateFlow()
    fun setSortFilter(sf: SortFilter) {
        _sortFilter.value = sf
        viewModelScope.launch { settings.setSort(sf.option.name, sf.direction.name) }
    }

    // ── Home view mode (Books / Series / Authors) ─────────────────────────────
    val homeViewMode: StateFlow<HomeViewMode> =
        settings.homeViewMode
            .map { runCatching { HomeViewMode.valueOf(it) }.getOrDefault(HomeViewMode.BOOKS) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeViewMode.BOOKS)

    fun setHomeViewMode(mode: HomeViewMode) {
        viewModelScope.launch { settings.setHomeViewMode(mode.name) }
    }

    // ── Top-level home section (Audio / Ebooks) ───────────────────────────────
    val homeSection: StateFlow<HomeSection> =
        settings.homeSection
            .map { runCatching { HomeSection.valueOf(it) }.getOrDefault(HomeSection.AUDIO) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeSection.AUDIO)

    fun setHomeSection(section: HomeSection) {
        viewModelScope.launch { settings.setHomeSection(section.name) }
    }

    init {
        // Migration for users who lived in the old "Ebooks" view-mode pill: move them to the new
        // top-level Ebooks section and reset the (now 3-way) view mode. Runs once — the stale
        // "EBOOKS" string otherwise just falls back to BOOKS via the valueOf guard above.
        viewModelScope.launch {
            if (settings.homeViewMode.first() == "EBOOKS") {
                settings.setHomeSection(HomeSection.EBOOKS.name)
                settings.setHomeViewMode(HomeViewMode.BOOKS.name)
            }
        }
    }

    /** True when the library has any book at all (audio or ebook) — drives the empty-state gate so
     *  a user with only ebooks (or only audiobooks) isn't shown the full EmptyLibrary screen. */
    val hasAnyBooks: StateFlow<Boolean> =
        repository.hasAnyBooks()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // .flowOn(Dispatchers.Default): combine's transform (buildGridItems, and below, the tab
    // filter/count) ran on Dispatchers.Main.immediate via stateIn(viewModelScope) — sorting and
    // grouping the whole library on the main thread on every emission (including once per
    // playback-position write, before G2-3 slows those down).
    val gridItems: StateFlow<List<HomeGridItem>> =
        combine(
            repository.getHomeGridBooks(),
            seriesRepository.getAllSeries(),
            repository.getAllAuthorMeta(),
            combine(homeViewMode, homeSection) { mode, section -> mode to section },
            _sortFilter
        ) { gridBooks, seriesList, authorMetas, (mode, section), sf ->
            buildGridItems(gridBooks, seriesList, authorMetas, mode, section, sf)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Library status tabs ──────────────────────────────────────────────────

    private val _libraryTab = MutableStateFlow(LibraryTab.ALL)
    val libraryTab: StateFlow<LibraryTab> = _libraryTab.asStateFlow()
    fun setLibraryTab(tab: LibraryTab) { _libraryTab.value = tab }

    /** Grid items filtered to the selected status tab. */
    val visibleGridItems: StateFlow<List<HomeGridItem>> =
        combine(gridItems, _libraryTab) { items, tab ->
            if (tab == LibraryTab.ALL) items else items.filter { statusOf(it) == tab }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-tab counts for the tab labels. */
    val tabCounts: StateFlow<Map<LibraryTab, Int>> =
        gridItems.map { items: List<HomeGridItem> ->
            LibraryTab.entries.associateWith { tab ->
                if (tab == LibraryTab.ALL) items.size else items.count { statusOf(it) == tab }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap<LibraryTab, Int>())

    private fun statusOf(item: HomeGridItem): LibraryTab = when (item) {
        is HomeGridItem.SingleBook -> tabFor(item.book.status)
        is HomeGridItem.SeriesItem -> collectionStatus(item.books.map { it.status })
        is HomeGridItem.AuthorItem -> collectionStatus(item.books.map { it.status })
    }

    private fun collectionStatus(statuses: List<BookStatus>): LibraryTab = when {
        statuses.any { it == BookStatus.IN_PROGRESS } -> LibraryTab.LISTENING
        statuses.isNotEmpty() && statuses.all { it == BookStatus.FINISHED } -> LibraryTab.FINISHED
        statuses.isNotEmpty() && statuses.all { it == BookStatus.NOT_STARTED } -> LibraryTab.NOT_STARTED
        else -> LibraryTab.LISTENING
    }

    private fun tabFor(status: BookStatus): LibraryTab = when (status) {
        BookStatus.IN_PROGRESS -> LibraryTab.LISTENING
        BookStatus.FINISHED -> LibraryTab.FINISHED
        BookStatus.NOT_STARTED -> LibraryTab.NOT_STARTED
    }

    private fun buildGridItems(
        gridBooks: List<HomeGridBook>,
        seriesList: List<Series>,
        authorMetas: List<AuthorMeta>,
        mode: HomeViewMode,
        section: HomeSection,
        sf: SortFilter
    ): List<HomeGridItem> {
        val result = mutableListOf<HomeGridItem>()

        if (section == HomeSection.EBOOKS) {
            // The Ebooks section is a flat list of everything with a connected/standalone EPUB
            // (audiobooks-with-epub AND ebook-only rows); the Books/Series/Authors mode is ignored.
            gridBooks.filter { it.ebookPath != null }
                .forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }
        } else {
            // Audio section: exclude ebook-only rows (they have no audio) — this single filter also
            // keeps them out of visibleGridItems and tabCounts, which both derive from gridItems.
            val audioList = gridBooks.filter { !it.isEbookOnly }
            when (mode) {
                HomeViewMode.BOOKS ->
                    audioList.forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }

                HomeViewMode.SERIES -> {
                    val seriesById = seriesList.associateBy { it.id }
                    val (inSeries, standalone) = audioList.partition { it.seriesId != null && seriesById.containsKey(it.seriesId) }
                    inSeries.groupBy { it.seriesId!! }.forEach { (sid, members) ->
                        val series = seriesById.getValue(sid)
                        val ordered = members.sortedWith(compareBy({ it.seriesOrder ?: Float.MAX_VALUE }, { it.title.lowercase() }))
                        val cover = series.coverArtPath ?: ordered.firstOrNull { it.coverArtPath != null }?.coverArtPath
                        result.add(HomeGridItem.SeriesItem(series, ordered, cover, ordered.maxOfOrNull { it.lastPlayedMs } ?: series.createdAtMs))
                    }
                    standalone.forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }
                }

                HomeViewMode.AUTHORS -> {
                    val metaByName = authorMetas.associateBy { it.name }
                    // Group by the effective author (override-aware) so an author you change is reflected.
                    audioList.groupBy { it.displayAuthor.ifBlank { "Unknown" } }.forEach { (name, members) ->
                        val ordered = members.sortedWith(compareBy({ it.seriesName ?: "" }, { it.seriesOrder ?: Float.MAX_VALUE }, { it.title.lowercase() }))
                        val cover = metaByName[name]?.coverArtPath ?: ordered.firstOrNull { it.coverArtPath != null }?.coverArtPath
                        result.add(HomeGridItem.AuthorItem(name, cover, ordered, ordered.maxOfOrNull { it.lastPlayedMs } ?: 0L))
                    }
                }
            }
        }

        // Sort using the user-selected SortFilter. Decorate-sort-undecorate: numericKey/textKey
        // used to be called from inside compareBy/thenBy, so they ran O(n log n) times each —
        // and numericKey for a series/author tile re-scans its whole member list on every single
        // comparison. Compute each item's key exactly once (O(n)), then sort using the
        // precomputed keys.
        fun members(item: HomeGridItem): List<HomeGridBook> = when (item) {
            is HomeGridItem.SingleBook -> listOf(item.book)
            is HomeGridItem.SeriesItem -> item.books
            is HomeGridItem.AuthorItem -> item.books
        }
        fun numericKey(item: HomeGridItem): Double = when (sf.option) {
            SortOption.DATE_ADDED -> members(item).maxOf { it.addedDateMs }.toDouble()
            SortOption.DURATION -> members(item).sumOf { it.totalDurationMs }.toDouble()
            SortOption.LAST_PLAYED -> item.lastPlayedMs.toDouble()
            // In the Ebooks section, PROGRESS sorts by reading progress, not audio position.
            SortOption.PROGRESS ->
                if (section == HomeSection.EBOOKS) members(item).maxOf { it.readingFraction.toDouble() }
                else members(item).maxOf { it.progressFraction.toDouble() }
            else -> 0.0
        }
        fun textKey(item: HomeGridItem): String = when (item) {
            is HomeGridItem.SingleBook -> when (sf.option) {
                SortOption.AUTHOR -> item.book.displayAuthor.lowercase()
                SortOption.SERIES -> {
                    val s = item.book.seriesName?.lowercase() ?: "￿"
                    val o = item.book.seriesOrder ?: Float.MAX_VALUE
                    "$s${o.toString().padStart(10, '0')}"
                }
                else -> item.book.title.lowercase()
            }
            is HomeGridItem.SeriesItem -> item.series.name.lowercase()
            is HomeGridItem.AuthorItem -> item.name.lowercase()
        }

        val useNumeric = sf.option in setOf(
            SortOption.DATE_ADDED, SortOption.DURATION, SortOption.LAST_PLAYED, SortOption.PROGRESS
        )
        val decorated = result.map { Triple(it, numericKey(it), textKey(it)) }
        val comparator: Comparator<Triple<HomeGridItem, Double, String>> = if (useNumeric) {
            if (sf.direction == SortDirection.DESC)
                compareByDescending<Triple<HomeGridItem, Double, String>> { it.second }.thenBy { it.third }
            else
                compareBy<Triple<HomeGridItem, Double, String>> { it.second }.thenBy { it.third }
        } else {
            if (sf.direction == SortDirection.DESC)
                compareByDescending { it.third }
            else
                compareBy { it.third }
        }
        return decorated.sortedWith(comparator).map { it.first }
    }

    // ── Currently playing book (for hero card) ────────────────────────────────

    val playbackState: StateFlow<PlaybackState> = playerController.playbackState

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentlyPlayingBook: StateFlow<Book?> =
        playerController.playbackState
            .flatMapLatest { state ->
                if (state.bookId == -1L) flowOf(null)
                else repository.getBookById(state.bookId)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Last-played book — shown as resume card when nothing is actively playing
    @OptIn(ExperimentalCoroutinesApi::class)
    val resumeBook: StateFlow<BookWithProgress?> =
        settings.lastPlayedBookId
            .flatMapLatest { id ->
                if (id == -1L) flowOf(null)
                else repository.getBookWithProgress(id)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Fetches the full BookWithProgress (with its real audio file list) on demand — used by the
     *  book options / cover search sheets, which only need it once the user opens them, via the
     *  same cheap single-book flow [resumeBook] and [playResumeBook] already use. */
    fun bookWithProgressFlow(bookId: Long): Flow<BookWithProgress?> = repository.getBookWithProgress(bookId)

    fun playResumeBook(bookId: Long) {
        viewModelScope.launch {
            val bwp = repository.getBookWithProgress(bookId).first() ?: return@launch
            val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            if (files.isEmpty()) return@launch
            val progress = bwp.progress
            // If reading is the freshest activity (lastMode == TEXT) and an epub is connected,
            // resume from the equivalent converted audio position instead of the stale audio spot
            // — the audio-side half of the two-way listen↔read resume link (mirrors PlayerViewModel.play()).
            val bridgedMs = com.betteraudio.sync.TextToAudioResume.resolve(bwp.book, progress, files, repository)
            // Effective audio: book override → series default → global default preset → fallback.
            val series = bwp.book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
            val gPreset = repository.getDefaultAudioPreset()
            val audio = com.betteraudio.playback.AudioCascade.resolve(bwp.book, progress, series, gPreset, settings.currentDefaultSpeed)
            if (bridgedMs != null) {
                playerController.playBook(bwp.book, files, 0, 0L, audio.speed)
                playerController.bookSeekTo(bridgedMs)
            } else {
                val startIndex = files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0)
                val rawPos = if (progress?.isCompleted == true) 0L else (progress?.positionMs ?: 0L)
                // Same auto-rewind as the full player (PlayerViewModel.play()) — resuming from this
                // card shouldn't behave differently just because it skipped opening the full player.
                val rewind = com.betteraudio.playback.AudioCascade.autoRewindMs(settings, progress?.lastPausedAt ?: 0L)
                val startPos = if (rawPos >= rewind) rawPos - rewind else rawPos
                playerController.playBook(bwp.book, files, startIndex, startPos, audio.speed)
            }
            playerController.setVolumeBoost(audio.boostDb)
            playerController.setEqBands(audio.eqBandsJson)
            playerController.setSkipSilence(audio.skipSilence)
            repository.touchLastPlayed(bwp.book.id)
            settings.setLastPlayedBookId(bwp.book.id)
            settings.setThemeBookId(bwp.book.id)
        }
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    // null = DataStore not yet loaded; "" = loaded but not set; non-blank = folder chosen
    val savedFolder: StateFlow<String?> =
        settings.libraryFolder
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // null = DataStore not yet loaded; false = user hasn't picked a structure (first run);
    // true = a structure has been chosen. Drives the first-launch structure prompt.
    val structureChosen: StateFlow<Boolean?> =
        settings.importStructure
            .map { it.isNotBlank() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** UNKNOWN until LibraryBootstrapper resolves it (on/after startScan) — see SetupState's own
     *  doc comment for what each value gates in the onboarding LaunchedEffect. */
    val setupState: StateFlow<com.betteraudio.data.diskstore.SetupState> =
        settings.setupState
            .map { com.betteraudio.data.diskstore.SetupState.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.data.diskstore.SetupState.from(settings.currentSetupState)
            )

    fun chooseImportStructure(structure: com.betteraudio.data.scanner.ImportStructure) {
        viewModelScope.launch { settings.setImportStructure(structure.name) }
    }

    private val _scan = MutableStateFlow(ScanResult())
    val scan: StateFlow<ScanResult> = _scan.asStateFlow()

    init {
        // Restore the persisted sort order so the library opens the way the user left it.
        viewModelScope.launch {
            val opt = runCatching { SortOption.valueOf(settings.sortOption.first()) }.getOrDefault(SortOption.TITLE)
            val dir = runCatching { SortDirection.valueOf(settings.sortDirection.first()) }.getOrDefault(SortDirection.ASC)
            _sortFilter.value = SortFilter(opt, dir)
        }

        // On @ApplicationScope, not viewModelScope: a configuration change or the ViewModel
        // being cleared mid-scan must not cancel it — this is a background library rescan the
        // user isn't watching a progress UI for, so a cancelled scan would silently corrupt
        // nothing but leave the library stale with no indication anything went wrong.
        appScope.launch {
            val folder = settings.libraryFolder.first()
            // Only rescan on launch if we actually have file access. Scanning before the
            // user grants "All files access" imports nothing useful and can surface stale
            // restored state (see allowBackup=false). The home screen drives a manual scan
            // once permission is granted.
            // One-time cleanup of legacy auto-sliced chapters; the next scan rebuilds the
            // affected books' chapters from embedded markers (or one row per file).
            try { repository.purgeSyntheticChapters() } catch (e: Exception) {
                AppLog.e(LogCat.UI, "startup purgeSyntheticChapters failed", e)
            }
            if (folder.isNotBlank() && hasFileAccess()) {
                try { scanner.scanDirectory(folder) } catch (e: Exception) {
                    AppLog.e(LogCat.UI, "startup rescan of $folder failed", e)
                }
            }
        }
    }

    private fun hasFileAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()

    fun startScan(path: String) {
        viewModelScope.launch {
            // Sequential, not two independent launches: bootstrap must set LIBRARY_FOLDER and
            // resolve FRESH vs RESTORED (detecting/applying a .voyage/settings.json) BEFORE the
            // scan runs, since the scan is what makes reconcileLibraryFromDisk's presets/authors/
            // series restore actually able to match books that don't exist in the DB yet.
            //
            // Only for a genuinely new folder or an unresolved setup, though — startScan is also
            // what pull-to-refresh calls on an already-configured, populated library (see
            // HomeScreenContent's PullToRefreshBox), and re-running bootstrap there would re-apply
            // a (possibly stale) settings.json snapshot over whatever the user has changed in-app
            // since, and force-reapply library.json on every pull-to-refresh.
            val setupState = com.betteraudio.data.diskstore.SetupState.from(settings.currentSetupState)
            val setupUnresolved = setupState == com.betteraudio.data.diskstore.SetupState.UNKNOWN ||
                setupState == com.betteraudio.data.diskstore.SetupState.NEEDS_FOLDER
            if (path != settings.currentLibraryFolder || setupUnresolved) {
                libraryBootstrapper.bootstrap(path)
            }
            _scan.value = ScanResult(ScanStatus.Running)
            val f = File(path)
            AppLog.e(LogCat.UI, "ScanStart: path=$path exists=${f.exists()}")
            try {
                val count = scanner.scanDirectory(path)
                _scan.value = ScanResult(ScanStatus.Done, booksFound = count)
            } catch (e: SecurityException) {
                _scan.value = ScanResult(ScanStatus.Error,
                    errorMessage = "Permission denied — enable 'Allow access to all files'.")
            } catch (e: Exception) {
                _scan.value = ScanResult(ScanStatus.Error,
                    errorMessage = "Scan error: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun resetScanState() { _scan.value = ScanResult() }

    // ── Series playback ────────────────────────────────────────────────────────

    /** Play a series as one continuous timeline (see [SeriesPlayer]). */
    fun playSeries(seriesId: Long) {
        viewModelScope.launch { seriesPlayer.playSeries(seriesId) }
    }
}
