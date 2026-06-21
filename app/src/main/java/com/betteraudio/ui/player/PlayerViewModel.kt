package com.betteraudio.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.BookGroupRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.synopsis.SynopsisService
import com.betteraudio.playback.PlaybackState
import com.betteraudio.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A row in the chapter list: a book-title header (for joined groups) or a chapter. */
sealed class ChapterRow {
    data class BookHeader(val title: String) : ChapterRow()
    data class Item(
        val title: String,
        val absStartMs: Long,   // position within the currently-loaded timeline
        val durationMs: Long,
        val key: Long
    ) : ChapterRow()
}

data class ChapterUiState(val rows: List<ChapterRow> = emptyList()) {
    val chapterCount: Int get() = rows.count { it is ChapterRow.Item }
    val hasChapters: Boolean get() = chapterCount > 1
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val groupRepository: BookGroupRepository,
    private val settings: SettingsStore,
    private val synopsisService: SynopsisService,
    val playerController: PlayerController
) : ViewModel() {

    val bookId: Long = checkNotNull(savedStateHandle["bookId"])

    val bookWithProgress: StateFlow<BookWithProgress?> =
        repository.getBookWithProgress(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val playbackState: StateFlow<PlaybackState> = playerController.playbackState

    val currentBoostDb: Int get() = playerController.currentVolumeBoostDb

    fun setVolumeBoost(db: Int) = playerController.setVolumeBoost(db)

    // ── Chapters (per-file fallback or embedded; grouped per book for joined groups) ──
    @OptIn(ExperimentalCoroutinesApi::class)
    val chapters: StateFlow<ChapterUiState> =
        playbackState
            .map { it.groupId }
            .distinctUntilChanged()
            .flatMapLatest { groupId ->
                if (groupId != -1L) flow { emit(buildGroupChapters(groupId)) }
                else combine(
                    repository.getChaptersForBook(bookId),
                    bookWithProgress
                ) { chs, bwp -> buildBookChapters(chs, bwp) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterUiState())

    private fun buildBookChapters(chapters: List<Chapter>, bwp: BookWithProgress?): ChapterUiState {
        val files = bwp?.audioFiles
            ?.sortedWith(compareBy({ it.trackNumber }, { it.fileName })) ?: emptyList()
        val cum = cumulativeStarts(files.map { it.id to it.durationMs })
        val rows = if (chapters.isNotEmpty()) {
            chapters.map { c ->
                ChapterRow.Item(c.title, (cum[c.fileId] ?: 0L) + c.startInFileMs, c.durationMs, c.id)
            }
        } else {
            files.map { f ->
                ChapterRow.Item(f.chapterTitle ?: f.fileName, cum[f.id] ?: 0L, f.durationMs, f.id)
            }
        }
        return ChapterUiState(rows)
    }

    private suspend fun buildGroupChapters(groupId: Long): ChapterUiState {
        val books = groupRepository.getBooksForGroupOnce(groupId)
        val filesPerBook = groupRepository.getAudioFilesForBooks(books.map { it.id })
        // Global cumulative offsets across every file in group order
        val ordered = books.flatMap { b -> (filesPerBook[b.id] ?: emptyList()) }
        val cum = cumulativeStarts(ordered.map { it.id to it.durationMs })
        val rows = mutableListOf<ChapterRow>()
        for (b in books) {
            rows.add(ChapterRow.BookHeader(b.title))
            val chs = repository.getChaptersForBookOnce(b.id)
            if (chs.isNotEmpty()) {
                chs.forEach { c ->
                    rows.add(ChapterRow.Item(c.title, (cum[c.fileId] ?: 0L) + c.startInFileMs, c.durationMs, c.id))
                }
            } else {
                (filesPerBook[b.id] ?: emptyList()).forEach { f ->
                    rows.add(ChapterRow.Item(f.chapterTitle ?: f.fileName, cum[f.id] ?: 0L, f.durationMs, f.id))
                }
            }
        }
        return ChapterUiState(rows)
    }

    private fun cumulativeStarts(idDur: List<Pair<Long, Long>>): Map<Long, Long> {
        val map = HashMap<Long, Long>(idDur.size)
        var t = 0L
        idDur.forEach { (id, dur) -> map[id] = t; t += dur }
        return map
    }

    private var synopsisGenerating = false

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgressIfActive()
            }
        }
        // Generate synopsis on first open if API key is set and synopsis is missing
        viewModelScope.launch {
            bookWithProgress.collect { bwp ->
                val book = bwp?.book ?: return@collect
                if (book.synopsis == null && !synopsisGenerating && settings.currentGeminiApiKey.isNotBlank()) {
                    synopsisGenerating = true
                    val text = synopsisService.generateSynopsis(book.title, book.author)
                    if (text != null) repository.updateSynopsis(book.id, text)
                }
            }
        }
    }

    fun play() {
        viewModelScope.launch {
            val bwp = bookWithProgress.value ?: return@launch
            val files = bwp.audioFiles.sortedWith(
                compareBy({ it.trackNumber }, { it.fileName })
            )
            if (files.isEmpty()) return@launch
            val progress = bwp.progress
            val startIndex = files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0)
            val startPos = if (progress?.isCompleted == true) 0L else (progress?.positionMs ?: 0L)
            // Use book's saved speed, falling back to global default
            val speed = progress?.playbackSpeed ?: settings.currentDefaultSpeed
            playerController.playBook(bwp.book, files, startIndex, startPos, speed)
        }
    }

    fun togglePlayPause() = playerController.togglePlayPause()
    fun skipForward()     = playerController.skipForward()
    fun skipBack()        = playerController.skipBack()
    fun seekTo(posMs: Long) = playerController.seekTo(posMs)
    fun bookSeekTo(bookPosMs: Long) = playerController.bookSeekTo(bookPosMs)
    fun jumpToFile(index: Int) = playerController.jumpToFile(index)

    fun setSpeed(speed: Float) {
        playerController.setSpeed(speed)
        viewModelScope.launch { repository.updateSpeed(bookId, speed) }
    }

    fun updateBookStatus(status: BookStatus) {
        viewModelScope.launch { repository.updateBookStatus(bookId, status) }
    }

    fun updateSeriesInfo(seriesName: String?, seriesOrder: Float?) {
        viewModelScope.launch { repository.updateSeriesInfo(bookId, seriesName, seriesOrder) }
    }

    fun updateCoverArt(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val dir = File(context.filesDir, "covers").also { it.mkdirs() }
                val dest = File(dir, "$bookId.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                repository.updateCoverArt(bookId, dest.absolutePath)
            } catch (_: Exception) {}
        }
    }

    fun saveProgress() {
        val state = playbackState.value
        if (state.bookId != bookId) return
        val bwp = bookWithProgress.value ?: return
        val currentFile = bwp.audioFiles.getOrNull(state.currentFileIndex) ?: return
        viewModelScope.launch {
            repository.updatePosition(bookId, currentFile.id, playerController.currentPositionMs)
        }
    }

    private fun saveProgressIfActive() {
        val state = playbackState.value
        if (state.bookId != bookId) return
        val bwp = bookWithProgress.value ?: return
        val currentFile = bwp.audioFiles.getOrNull(state.currentFileIndex) ?: return
        viewModelScope.launch {
            repository.updatePosition(bookId, currentFile.id, playerController.currentPositionMs)
        }
    }
}
