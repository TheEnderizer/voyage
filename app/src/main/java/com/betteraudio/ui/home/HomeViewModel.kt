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
    private val seriesPlayer: com.betteraudio.playback.SeriesPlayer,
    private val scanner: AudioFileScanner,
    private val ebookScanner: com.betteraudio.data.scanner.EbookScanner,
    private val paragraphCache: com.betteraudio.data.ebook.ParagraphCache,
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
            val book = repository.getBookById(bookId).first()
            val folder = book?.folderPath?.let { File(it) }
            // Prefer storing the cover INSIDE the book's folder (a timestamped ".cover_*.jpg") so it
            // persists with the audio and travels with a restructure move. Fall back to internal
            // storage for synthetic multi-book folders ("dir::stem", not a real directory).
            val path = if (folder != null && folder.isDirectory) {
                val target = File(folder, ".cover_${System.currentTimeMillis()}.jpg")
                if (coverSearchService.downloadTo(imageUrl, target)) {
                    // Remove a previous app-written cover in the same folder to avoid clutter.
                    book.coverArtPath?.let { old ->
                        val f = File(old)
                        if (f.parentFile == folder && f.name.startsWith(".cover")) runCatching { f.delete() }
                    }
                    target.absolutePath
                } else null
            } else {
                coverSearchService.download(imageUrl, bookId)
            }
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
        repository.getAllBooksWithProgressUngrouped().map { it.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val gridItems: StateFlow<List<HomeGridItem>> =
        combine(
            repository.getAllBooksWithProgressUngrouped(),
            seriesRepository.getAllSeries(),
            repository.getAllAuthorMeta(),
            combine(homeViewMode, homeSection) { mode, section -> mode to section },
            _sortFilter
        ) { bwpList, seriesList, authorMetas, (mode, section), sf ->
            buildGridItems(bwpList, seriesList, authorMetas, mode, section, sf)
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
        section: HomeSection,
        sf: SortFilter
    ): List<HomeGridItem> {
        val result = mutableListOf<HomeGridItem>()

        if (section == HomeSection.EBOOKS) {
            // The Ebooks section is a flat list of everything with a connected/standalone EPUB
            // (audiobooks-with-epub AND ebook-only rows); the Books/Series/Authors mode is ignored.
            bwpList.filter { it.book.ebookPath != null }
                .forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }
        } else {
            // Audio section: exclude ebook-only rows (they have no audio) — this single filter also
            // keeps them out of visibleGridItems and tabCounts, which both derive from gridItems.
            val audioList = bwpList.filter { !it.isEbookOnly }
            when (mode) {
                HomeViewMode.BOOKS ->
                    audioList.forEach { result.add(HomeGridItem.SingleBook(it, it.lastPlayedMs)) }

                HomeViewMode.SERIES -> {
                    val seriesById = seriesList.associateBy { it.id }
                    val (inSeries, standalone) = audioList.partition { it.book.seriesId != null && seriesById.containsKey(it.book.seriesId) }
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
                    // Group by the effective author (override-aware) so an author you change is reflected.
                    audioList.groupBy { it.book.displayAuthor.ifBlank { "Unknown" } }.forEach { (name, members) ->
                        val ordered = members.sortedWith(compareBy({ it.book.seriesName ?: "" }, { it.book.seriesOrder ?: Float.MAX_VALUE }, { it.book.title.lowercase() }))
                        val cover = metaByName[name]?.coverArtPath ?: ordered.firstOrNull { it.book.coverArtPath != null }?.book?.coverArtPath
                        result.add(HomeGridItem.AuthorItem(name, cover, ordered, ordered.maxOfOrNull { it.lastPlayedMs } ?: 0L))
                    }
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
            // In the Ebooks section, PROGRESS sorts by reading progress, not audio position.
            SortOption.PROGRESS ->
                if (section == HomeSection.EBOOKS) members(item).maxOf { it.readingFraction.toDouble() }
                else members(item).maxOf { it.progressFraction.toDouble() }
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
            // Effective audio: book override → series default → global default preset → fallback.
            val series = bwp.book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
            val gPreset = repository.getDefaultAudioPreset()
            val speed = com.betteraudio.playback.AudioCascade.speed(progress?.playbackSpeed, series?.playbackSpeed, gPreset?.speedMult ?: settings.currentDefaultSpeed)
            playerController.playBook(bwp.book, files, startIndex, startPos, speed)
            playerController.setVolumeBoost(com.betteraudio.playback.AudioCascade.boost(progress?.boostDb, series?.boostDb, gPreset?.boostDb ?: 0))
            playerController.setEqBands(com.betteraudio.playback.AudioCascade.eq(progress?.eqBandsJson, series?.eqBandsJson, gPreset?.eqBandsJson))
            playerController.setSkipSilence(com.betteraudio.playback.AudioCascade.skipSilence(bwp.book.skipSilenceEnabled, series?.skipSilenceEnabled))
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

    /** Play a series as one continuous timeline (see [SeriesPlayer]). */
    fun playSeries(seriesId: Long) {
        viewModelScope.launch { seriesPlayer.playSeries(seriesId) }
    }
}
