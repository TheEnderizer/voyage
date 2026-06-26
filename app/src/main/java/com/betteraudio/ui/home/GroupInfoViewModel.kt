package com.betteraudio.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.BookGroupRepository
import com.betteraudio.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroupInfoUiState(
    val group: BookGroup,
    val books: List<Book>,
    val totalDurationMs: Long,
    val progressFraction: Float,
    val coverArtPath: String?
)

@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val groupRepository: BookGroupRepository,
    private val repository: AudiobookRepository,
    private val playerController: PlayerController
) : ViewModel() {

    val groupId: Long = checkNotNull(savedStateHandle["groupId"])

    val groupInfo: StateFlow<GroupInfoUiState?> =
        groupRepository.getBooksForGroup(groupId)
            .flatMapLatest { books ->
                flow {
                    val group = groupRepository.getGroupById(groupId) ?: run { emit(null); return@flow }
                    val totalMs = books.sumOf { it.totalDurationMs }
                    val progressList = books.map { repository.getProgressForBookOnce(it.id) }
                    val playedMs = books.zip(progressList).sumOf { (_, prog) -> prog?.positionMs ?: 0L }
                    val fraction = if (totalMs > 0) (playedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
                    emit(GroupInfoUiState(
                        group = group,
                        books = books,
                        totalDurationMs = totalMs,
                        progressFraction = fraction,
                        coverArtPath = group.coverArtPath ?: books.firstOrNull()?.coverArtPath
                    ))
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play() {
        viewModelScope.launch {
            val state = groupInfo.value ?: return@launch
            val filesPerBook = groupRepository.getAudioFilesForBooks(state.books.map { it.id })
            val progressMap = state.books.associateWith { book ->
                repository.getProgressForBookOnce(book.id)
            }
            val resumeBook = progressMap.entries
                .maxByOrNull { it.value?.lastPlayedMs ?: 0L }?.key ?: state.books.first()
            val resumeProgress = progressMap[resumeBook]

            var globalIndex = 0
            for (book in state.books) {
                val files = filesPerBook[book.id] ?: emptyList()
                if (book.id == resumeBook.id) {
                    val fileIdx = files.indexOfFirst { it.id == resumeProgress?.currentFileId }
                        .coerceAtLeast(0)
                    globalIndex += fileIdx
                    break
                }
                globalIndex += files.size
            }
            val startPos = if (resumeProgress?.isCompleted == true) 0L else resumeProgress?.positionMs ?: 0L

            playerController.playBookGroup(
                groupId = state.group.id,
                groupName = state.group.name,
                coverArtPath = state.group.coverArtPath,
                orderedBooks = state.books,
                filesPerBook = filesPerBook,
                startGlobalFileIndex = globalIndex,
                startPositionMs = startPos,
                speed = state.group.playbackSpeed
            )
        }
    }
}
