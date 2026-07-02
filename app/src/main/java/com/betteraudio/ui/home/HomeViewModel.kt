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
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.scanner.AudioFileScanner
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlaybackState
import com.betteraudio.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
/** How the home library is grouped. */
enum class HomeViewMode { BOOKS, SERIES, AUTHORS }

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
        val bwp: BookWithProgress,
        override val lastPlayedMs: Long
    ) : HomeGridItem()

    /** A series tile (Series view) with its member books. */
    data class SeriesItem(
        val series: Series,
        val books: List<BookWithProgress>,
        val coverPath: String?,
        override val lastPlayedMs: Long
    ) : HomeGridItem()

    /** An author tile (Authors view) with that author's books. */
    data class AuthorItem(
        val name: String,
        val coverPath: String?,
        val books: List<BookWithProgress>,
        override val lastPlayedMs: Long
    ) : HomeGridItem()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val scanner: AudioFileScanner,
    private val settings: SettingsStore,
    private val coverSearchService: CoverSearchService,
    val playerController: PlayerController
) : ViewModel() {

    // ── Book options sheet ─────────────────────────────────────────────────

    private val _bookOptionsTarget = MutableStateFlow<Long?>(null)
    val bookOptionsTarget: StateFlow<Long?> = _bookOptionsTarget.asStateFlow()

    fun openBookOptions(bookId: Long) { _bookOptionsTarget.value = bookId }
    fun closeBookOptions() { _bookOptionsTarget.value = null }

    fun updateBookMetadata(bookId: Long, titleOverride: String?, authorOverride: String?) {
        viewModelScope.launch { repository.updateBookMetadata(bookId, titleOverride, authorOverride) }
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
            val path = coverSearchService.download(imageUrl, bookId)
            if (path != null) repository.updateCoverArt(bookId, path)
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
            when (target) {
                is CoverCollectionTarget.Series -> {
                    val path = coverSearchService.download(imageUrl, "series${target.seriesId}")
                    if (path != null) seriesRepository.setSeriesCover(target.seriesId, path)
                }
                is CoverCollectionTarget.Author -> {
                    val path = coverSearchService.download(imageUrl, "author${target.name}")
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

    // ── Selection mode ──────────────────────────────────────────────────────

    private val _selectedBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBookIds: StateFlow<Set<Long>> = _selectedBookIds.asStateFlow()

    val isSelectionMode: Boolean
        get() = _selectedBookIds.value.isNotEmpty()

    fun enterBookSelection(bookId: Long) {
        _selectedBookIds.value = setOf(bookId)
    }

    fun toggleBookSelection(bookId: Long) {
        _selectedBookIds.update { current ->
            if (bookId in current) current - bookId else current + bookId
        }
    }

    fun clearSelection() {
        _selectedBookIds.value = emptySet()
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

    val gridItems: StateFlow<List<HomeGridItem>> =
        combine(
            repository.getAllBooksWithProgressUngrouped(),
            seriesRepository.getAllSeries(),
            repository.getAllAuthorMeta(),
            homeViewMode,
            _sortFilter
        ) { bwpList, seriesList, authorMetas, mode, sf ->
            buildGridItems(bwpList, seriesList, authorMetas, mode, sf)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Library status tabs ──────────────────────────────────────────────────

    private val _libraryTab = MutableStateFlow(LibraryTab.ALL)
    val libraryTab: StateFlow<LibraryTab> = _libraryTab.asStateFlow()
    fun setLibraryTab(tab: LibraryTab) { _libraryTab.value = tab }

    /** Grid items filtered to the selected status tab. */
    val visibleGridItems: StateFlow<List<HomeGridItem>> =
        combine(gridItems, _libraryTab) { items, tab ->
            if (tab == LibraryTab.ALL) items else items.filter { statusOf(it) == tab }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Per-tab counts for the tab labels. */
    val tabCounts: StateFlow<Map<LibraryTab, Int>> =
        gridItems.map { items: List<HomeGridItem> ->
            LibraryTab.entries.associateWith { tab ->
                if (tab == LibraryTab.ALL) items.size else items.count { statusOf(it) == tab }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap<LibraryTab, Int>())

    private fun statusOf(item: HomeGridItem): LibraryTab = when (item) {
        is HomeGridItem.SingleBook -> tabFor(item.bwp.book.status)
        is HomeGridItem.SeriesItem -> collectionStatus(item.books.map { it.book.status })
        is HomeGridItem.AuthorItem -> collectionStatus(item.books.map { it.book.status })
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
        bwpList: List<BookWithProgress>,
        seriesList: List<Series>,
        authorMetas: List<AuthorMeta>,
        mode: HomeViewMode,
        sf: SortFilter
    ): List<HomeGridItem> {
        val result = mutableListOf<HomeGridItem>()

        when (mode) {
            HomeViewMode.BOOKS ->
                bwpList.forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }

            HomeViewMode.SERIES -> {
                val seriesById = seriesList.associateBy { it.id }
                val (inSeries, standalone) = bwpList.partition { it.book.seriesId != null && seriesById.containsKey(it.book.seriesId) }
                inSeries.groupBy { it.book.seriesId!! }.forEach { (sid, members) ->
                    val series = seriesById.getValue(sid)
                    val ordered = members.sortedWith(compareBy({ it.book.seriesOrder ?: Float.MAX_VALUE }, { it.book.title.lowercase() }))
                    val cover = series.coverArtPath ?: ordered.firstOrNull { it.book.coverArtPath != null }?.book?.coverArtPath
                    result.add(HomeGridItem.SeriesItem(series, ordered, cover, ordered.maxOfOrNull { it.lastPlayedMs } ?: series.createdAtMs))
                }
                standalone.forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }
            }

            HomeViewMode.AUTHORS -> {
                val metaByName = authorMetas.associateBy { it.name }
                bwpList.groupBy { it.book.author.ifBlank { "Unknown" } }.forEach { (name, members) ->
                    val ordered = members.sortedWith(compareBy({ it.book.seriesName ?: "" }, { it.book.seriesOrder ?: Float.MAX_VALUE }, { it.book.title.lowercase() }))
                    val cover = metaByName[name]?.coverArtPath ?: ordered.firstOrNull { it.book.coverArtPath != null }?.book?.coverArtPath
                    result.add(HomeGridItem.AuthorItem(name, cover, ordered, ordered.maxOfOrNull { it.lastPlayedMs } ?: 0L))
                }
            }
        }

        // Sort using the user-selected SortFilter
        fun members(item: HomeGridItem): List<BookWithProgress> = when (item) {
            is HomeGridItem.SingleBook -> listOf(item.bwp)
            is HomeGridItem.SeriesItem -> item.books
            is HomeGridItem.AuthorItem -> item.books
        }
        fun numericKey(item: HomeGridItem): Double = when (sf.option) {
            SortOption.DATE_ADDED -> members(item).maxOf { it.book.addedDateMs }.toDouble()
            SortOption.DURATION -> members(item).sumOf { it.book.totalDurationMs }.toDouble()
            SortOption.LAST_PLAYED -> item.lastPlayedMs.toDouble()
            SortOption.PROGRESS -> members(item).maxOf { it.progressFraction.toDouble() }
            else -> 0.0
        }
        fun textKey(item: HomeGridItem): String = when (item) {
            is HomeGridItem.SingleBook -> when (sf.option) {
                SortOption.AUTHOR -> item.bwp.book.author.lowercase()
                SortOption.SERIES -> {
                    val s = item.bwp.book.seriesName?.lowercase() ?: "￿"
                    val o = item.bwp.book.seriesOrder ?: Float.MAX_VALUE
                    "$s${o.toString().padStart(10, '0')}"
                }
                else -> item.bwp.book.title.lowercase()
            }
            is HomeGridItem.SeriesItem -> item.series.name.lowercase()
            is HomeGridItem.AuthorItem -> item.name.lowercase()
        }

        val useNumeric = sf.option in setOf(
            SortOption.DATE_ADDED, SortOption.DURATION, SortOption.LAST_PLAYED, SortOption.PROGRESS
        )
        val comparator: Comparator<HomeGridItem> = if (useNumeric) {
            if (sf.direction == SortDirection.DESC)
                compareByDescending<HomeGridItem> { numericKey(it) }.thenBy { textKey(it) }
            else
                compareBy<HomeGridItem> { numericKey(it) }.thenBy { textKey(it) }
        } else {
            if (sf.direction == SortDirection.DESC)
                compareByDescending<HomeGridItem> { textKey(it) }
            else
                compareBy<HomeGridItem> { textKey(it) }
        }
        return result.sortedWith(comparator)
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

    fun playResumeBook(bwp: BookWithProgress) {
        viewModelScope.launch {
            val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            if (files.isEmpty()) return@launch
            val progress = bwp.progress
            val startIndex = files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0)
            val startPos = if (progress?.isCompleted == true) 0L else (progress?.positionMs ?: 0L)
            val speed = progress?.playbackSpeed ?: settings.currentDefaultSpeed
            playerController.playBook(bwp.book, files, startIndex, startPos, speed)
            playerController.setVolumeBoost(progress?.boostDb ?: 0)
            repository.touchLastPlayed(bwp.book.id)
            settings.setLastPlayedBookId(bwp.book.id)
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

        viewModelScope.launch {
            val folder = settings.libraryFolder.first()
            // Only rescan on launch if we actually have file access. Scanning before the
            // user grants "All files access" imports nothing useful and can surface stale
            // restored state (see allowBackup=false). The home screen drives a manual scan
            // once permission is granted.
            // One-time cleanup of legacy auto-sliced chapters; the next scan rebuilds the
            // affected books' chapters from embedded markers (or one row per file).
            try { repository.purgeSyntheticChapters() } catch (_: Exception) {}
            if (folder.isNotBlank() && hasFileAccess()) {
                try { scanner.scanDirectory(folder) } catch (_: Exception) {}
            }
        }
    }

    private fun hasFileAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            android.os.Environment.isExternalStorageManager()

    fun startScan(path: String) {
        viewModelScope.launch { settings.setLibraryFolder(path) }
        viewModelScope.launch {
            _scan.value = ScanResult(ScanStatus.Running)
            val f = File(path)
            Log.e(TAG, "ScanStart: path=$path exists=${f.exists()}")
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

    /**
     * Play a series as one continuous timeline: all member books' files are flattened in series
     * order, so when a book ends the next book starts automatically. Playback resumes at the
     * member the listener was last in (most-recently-played → first unfinished → first), and each
     * book keeps its own progress, so resume stays correct even if the order later changes.
     */
    fun playSeries(seriesId: Long) {
        viewModelScope.launch {
            val series = seriesRepository.getSeriesOnce(seriesId) ?: return@launch
            val books = seriesRepository.getBooksInSeriesOnce(seriesId)
            if (books.isEmpty()) return@launch
            val filesPerBook = seriesRepository.getAudioFilesForBooks(books.map { it.id })

            val progressMap = books.associateWith { repository.getProgressForBookOnce(it.id) }
            val resumeBook = progressMap.entries
                .filter { (_, p) -> (p?.lastPlayedMs ?: 0L) > 0L }
                .maxByOrNull { (_, p) -> p?.lastPlayedMs ?: 0L }?.key
                ?: books.firstOrNull { it.status != BookStatus.FINISHED }
                ?: books.first()
            val resumeProgress = progressMap[resumeBook]

            var globalIndex = 0
            for (book in books) {
                val files = filesPerBook[book.id] ?: emptyList()
                if (book.id == resumeBook.id) {
                    globalIndex += files.indexOfFirst { it.id == resumeProgress?.currentFileId }.coerceAtLeast(0)
                    break
                }
                globalIndex += files.size
            }
            val startPos = if (resumeProgress?.isCompleted == true) 0L else resumeProgress?.positionMs ?: 0L

            playerController.playBookGroup(
                groupId = series.id,
                groupName = series.name,
                coverArtPath = series.coverArtPath,
                orderedBooks = books,
                filesPerBook = filesPerBook,
                startGlobalFileIndex = globalIndex,
                startPositionMs = startPos,
                speed = series.playbackSpeed ?: settings.currentDefaultSpeed
            )
            repository.touchLastPlayed(resumeBook.id)
            settings.setLastPlayedBookId(resumeBook.id)
        }
    }
}
