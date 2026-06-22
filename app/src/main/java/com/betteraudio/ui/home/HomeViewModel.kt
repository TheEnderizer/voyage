package com.betteraudio.ui.home

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.BookGroupRepository
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

/** Items shown in the home library grid */
sealed class HomeGridItem {
    abstract val lastPlayedMs: Long

    /** A single book */
    data class SingleBook(
        val bwp: BookWithProgress,
        override val lastPlayedMs: Long
    ) : HomeGridItem()

    /** A user-created joined group */
    data class Group(
        val group: BookGroup,
        val books: List<Book>,
        override val lastPlayedMs: Long
    ) : HomeGridItem()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: AudiobookRepository,
    private val groupRepository: BookGroupRepository,
    private val scanner: AudioFileScanner,
    private val settings: SettingsStore,
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

    fun ignoreBook(bookId: Long) {
        viewModelScope.launch { repository.setBookIgnored(bookId, true) }
    }

    fun deleteBook(bookId: Long, deleteFiles: Boolean) {
        viewModelScope.launch { repository.deleteBook(bookId, deleteFiles) }
    }

    // ── Selection mode ──────────────────────────────────────────────────────

    private val _selectedBookIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedBookIds: StateFlow<Set<Long>> = _selectedBookIds.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroupId: StateFlow<Long?> = _selectedGroupId.asStateFlow()

    val isSelectionMode: Boolean
        get() = _selectedBookIds.value.isNotEmpty() || _selectedGroupId.value != null

    fun enterBookSelection(bookId: Long) {
        _selectedGroupId.value = null
        _selectedBookIds.value = setOf(bookId)
    }

    fun toggleBookSelection(bookId: Long) {
        _selectedBookIds.update { current ->
            if (bookId in current) current - bookId else current + bookId
        }
    }

    fun selectGroup(groupId: Long) {
        _selectedBookIds.value = emptySet()
        _selectedGroupId.value = groupId
    }

    fun clearSelection() {
        _selectedBookIds.value = emptySet()
        _selectedGroupId.value = null
    }

    /** Split a selected group back into individual books */
    fun splitSelectedGroup(onDone: () -> Unit) {
        val gid = _selectedGroupId.value ?: return
        viewModelScope.launch {
            groupRepository.dissolveGroup(gid)
            clearSelection()
            onDone()
        }
    }

    // ── Grid items ───────────────────────────────────────────────────────────

    private val _sortFilter = MutableStateFlow(SortFilter())
    val sortFilter: StateFlow<SortFilter> = _sortFilter.asStateFlow()
    fun setSortFilter(sf: SortFilter) {
        _sortFilter.value = sf
        viewModelScope.launch { settings.setSort(sf.option.name, sf.direction.name) }
    }

    val gridItems: StateFlow<List<HomeGridItem>> =
        combine(
            repository.getAllBooksWithProgressUngrouped(),
            groupRepository.getAllGroups(),
            _sortFilter
        ) { bwpList, groups, sf ->
            buildGridItems(bwpList, groups, sf)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private suspend fun buildGridItems(
        bwpList: List<BookWithProgress>,
        groups: List<BookGroup>,
        sf: SortFilter
    ): List<HomeGridItem> {
        val result = mutableListOf<HomeGridItem>()

        // Joined groups — read member progress directly (grouped books are not in bwpList,
        // which only contains ungrouped books), so last-played stays accurate for groups too.
        for (group in groups) {
            val memberBooks = groupRepository.getBooksForGroupOnce(group.id)
            val lastPlayed = memberBooks
                .mapNotNull { book -> repository.getProgressForBookOnce(book.id)?.lastPlayedMs }
                .maxOrNull() ?: group.createdAtMs
            result.add(HomeGridItem.Group(group, memberBooks, lastPlayed))
        }

        // Every ungrouped book is its own grid card (flat grid, no folders)
        for (bwp in bwpList) {
            result.add(HomeGridItem.SingleBook(bwp, bwp.lastPlayedMs))
        }

        // Sort using the user-selected SortFilter
        fun numericKey(item: HomeGridItem): Double = when (sf.option) {
            SortOption.DATE_ADDED -> when (item) {
                is HomeGridItem.SingleBook -> item.bwp.book.addedDateMs.toDouble()
                is HomeGridItem.Group -> item.group.createdAtMs.toDouble()
            }
            SortOption.DURATION -> when (item) {
                is HomeGridItem.SingleBook -> item.bwp.book.totalDurationMs.toDouble()
                is HomeGridItem.Group -> item.books.sumOf { it.totalDurationMs }.toDouble()
            }
            SortOption.LAST_PLAYED -> item.lastPlayedMs.toDouble()
            SortOption.PROGRESS -> when (item) {
                is HomeGridItem.SingleBook -> item.bwp.progressFraction.toDouble()
                is HomeGridItem.Group -> item.books
                    .mapNotNull { b -> bwpList.find { it.book.id == b.id }?.progressFraction?.toDouble() }
                    .maxOrNull() ?: 0.0
            }
            else -> 0.0
        }
        fun textKey(item: HomeGridItem): String = when (sf.option) {
            SortOption.AUTHOR -> when (item) {
                is HomeGridItem.SingleBook -> item.bwp.book.author.lowercase()
                is HomeGridItem.Group -> item.books.firstOrNull()?.author?.lowercase() ?: ""
            }
            SortOption.SERIES -> when (item) {
                is HomeGridItem.SingleBook -> {
                    val s = item.bwp.book.seriesName?.lowercase() ?: "￿"
                    val o = item.bwp.book.seriesOrder ?: Float.MAX_VALUE
                    "$s${o.toString().padStart(10, '0')}"
                }
                is HomeGridItem.Group -> item.group.name.lowercase()
            }
            else -> when (item) { // TITLE and numeric fallback secondary key
                is HomeGridItem.SingleBook -> item.bwp.book.title.lowercase()
                is HomeGridItem.Group -> item.group.name.lowercase()
            }
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
                val count = scanner.scanDirectory(path, autoJoin = true)
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

    // ── Group player launch ────────────────────────────────────────────────────

    fun launchGroupPlayback(groupId: Long) {
        viewModelScope.launch {
            val group = groupRepository.getGroupById(groupId) ?: return@launch
            val books = groupRepository.getBooksForGroupOnce(groupId)
            val filesPerBook = groupRepository.getAudioFilesForBooks(books.map { it.id })

            // Find resume point: the book with most recent lastPlayedMs
            val progressMap = books.associateWith { book ->
                repository.getProgressForBook(book.id).first()
            }
            val resumeBook = progressMap.entries
                .maxByOrNull { it.value?.lastPlayedMs ?: 0L }?.key ?: books.first()
            val resumeProgress = progressMap[resumeBook]

            // Compute global start index
            var globalIndex = 0
            var found = false
            for (book in books) {
                val files = filesPerBook[book.id] ?: emptyList()
                if (!found) {
                    if (book.id == resumeBook.id) {
                        val fileIdx = files.indexOfFirst { it.id == resumeProgress?.currentFileId }
                            .coerceAtLeast(0)
                        globalIndex += fileIdx
                        found = true
                        break
                    }
                    globalIndex += files.size
                }
            }
            val startPos = if (resumeProgress?.isCompleted == true) 0L
                          else resumeProgress?.positionMs ?: 0L

            playerController.playBookGroup(
                groupId = group.id,
                groupName = group.name,
                coverArtPath = group.coverArtPath,
                orderedBooks = books,
                filesPerBook = filesPerBook,
                startGlobalFileIndex = globalIndex,
                startPositionMs = startPos,
                speed = group.playbackSpeed
            )
            // Bump the resume member's last-played so the group rises to the top right away.
            repository.touchLastPlayed(resumeBook.id)
        }
    }
}
