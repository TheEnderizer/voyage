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
    DURATION("Duration")
}

enum class SortDirection { ASC, DESC }

data class SortFilter(
    val option: SortOption = SortOption.TITLE,
    val direction: SortDirection = SortDirection.ASC
)

/** Items shown in the home library grid */
sealed class HomeGridItem {
    /** A single book */
    data class SingleBook(
        val bwp: BookWithProgress,
        val lastPlayedMs: Long
    ) : HomeGridItem()

    /** A user-created joined group */
    data class Group(
        val group: BookGroup,
        val books: List<Book>,
        val lastPlayedMs: Long
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
    fun setSortFilter(sf: SortFilter) { _sortFilter.value = sf }

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

        // Joined groups
        for (group in groups) {
            val memberBooks = groupRepository.getBooksForGroupOnce(group.id)
            val lastPlayed = memberBooks
                .mapNotNull { book ->
                    bwpList.find { it.book.id == book.id }?.progress?.lastPlayedMs
                }
                .maxOrNull() ?: group.createdAtMs
            result.add(HomeGridItem.Group(group, memberBooks, lastPlayed))
        }

        // Every ungrouped book is its own grid card (flat grid, no folders)
        for (bwp in bwpList) {
            result.add(HomeGridItem.SingleBook(bwp, bwp.lastPlayedMs))
        }

        // Sort by last played descending, then by title
        return result.sortedWith(
            compareByDescending<HomeGridItem> { item ->
                when (item) {
                    is HomeGridItem.SingleBook -> item.lastPlayedMs
                    is HomeGridItem.Group -> item.lastPlayedMs
                }
            }.thenBy { item ->
                when (item) {
                    is HomeGridItem.SingleBook -> item.bwp.book.title.lowercase()
                    is HomeGridItem.Group -> item.group.name.lowercase()
                }
            }
        )
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

    // ── Scan ─────────────────────────────────────────────────────────────────

    val savedFolder: StateFlow<String> =
        settings.libraryFolder
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _scan = MutableStateFlow(ScanResult())
    val scan: StateFlow<ScanResult> = _scan.asStateFlow()

    init {
        viewModelScope.launch {
            val folder = settings.libraryFolder.first()
            if (folder.isNotBlank()) {
                try { scanner.scanDirectory(folder) } catch (_: Exception) {}
            }
        }
    }

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
        }
    }
}
