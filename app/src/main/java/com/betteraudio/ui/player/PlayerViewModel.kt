package com.betteraudio.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.util.AppLog
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.model.BookWithProgress
import org.json.JSONArray
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.data.synopsis.SynopsisResult
import com.betteraudio.data.synopsis.SynopsisService
import com.betteraudio.playback.PlaybackState
import com.betteraudio.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class GroupScreenState(
    val seriesId: Long,
    val name: String,
    val speed: Float,
    val books: List<Book>,
    val totalDurationMs: Long,
    val progressFraction: Float,
    val coverArtPath: String?
)

/** A row in the chapter list: a book-title header (for joined groups) or a chapter. */
sealed class ChapterRow {
    data class BookHeader(val title: String) : ChapterRow()
    data class Item(
        val title: String,
        val absStartMs: Long,
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
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore,
    private val synopsisService: SynopsisService,
    val playerController: PlayerController
) : ViewModel() {

    val bookId: Long = savedStateHandle["bookId"] ?: -1L
    val groupId: Long = savedStateHandle["groupId"] ?: -1L

    // Group screen model — populated when groupId != -1.
    private val _groupInfo = MutableStateFlow<GroupScreenState?>(null)
    val groupInfo: StateFlow<GroupScreenState?> = _groupInfo.asStateFlow()

    val bookWithProgress: StateFlow<BookWithProgress?> =
        if (bookId != -1L)
            repository.getBookWithProgress(bookId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        else
            MutableStateFlow(null)

    val playbackState: StateFlow<PlaybackState> = playerController.playbackState

    // Series cover mode: when true, a book that belongs to a series shows the SERIES cover in the
    // player instead of the book's own cover. Persisted globally; toggled from the overflow menu.
    val showSeriesCover: StateFlow<Boolean> =
        settings.playerShowSeriesCover.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleShowSeriesCover() {
        viewModelScope.launch { settings.setPlayerShowSeriesCover(!showSeriesCover.value) }
    }

    // The current book's series cover (if it belongs to one), for the cover toggle.
    @OptIn(ExperimentalCoroutinesApi::class)
    val seriesCover: StateFlow<String?> =
        bookWithProgress
            .map { it?.book?.seriesId }
            .distinctUntilChanged()
            .flatMapLatest { sid -> if (sid == null) flowOf(null) else seriesRepository.getSeries(sid).map { it?.coverArtPath } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Configured skip intervals — surfaced so the transport buttons can show the real seconds.
    val skipForwardMs: StateFlow<Long> =
        settings.skipForwardMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_FORWARD_MS)
    val skipBackMs: StateFlow<Long> =
        settings.skipBackMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_BACK_MS)

    // ── Audio presets (type-specific) ────────────────────────────────────────
    val speedPresets: StateFlow<List<AudioPreset>> =
        repository.getAudioPresetsByType(AudioPreset.TYPE_SPEED)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val boostPresets: StateFlow<List<AudioPreset>> =
        repository.getAudioPresetsByType(AudioPreset.TYPE_BOOST)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val eqPresets: StateFlow<List<AudioPreset>> =
        repository.getAudioPresetsByType(AudioPreset.TYPE_EQ)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _eqBandsMillibels = MutableStateFlow<IntArray?>(null)  // null = flat/bypass
    val eqBandsMillibels: StateFlow<IntArray?> = _eqBandsMillibels.asStateFlow()

    // Lazily bake the cover background effect the first time this book is opened (or after
    // its cover changes), so the player draws one cached bitmap instead of live-blurring.
    private var lastFxCover: String? = null
    init {
        if (bookId != -1L) {
            repository.getBookById(bookId)
                .onEach { b ->
                    val cover = b?.coverArtPath
                    if (cover != null && b.coverFxPath == null && cover != lastFxCover) {
                        lastFxCover = cover
                        repository.ensureCoverFx(bookId)
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    // Load series info when opened via a series (carried in the groupId route arg).
    init {
        if (groupId != -1L) {
            seriesRepository.getBooksInSeries(groupId)
                .flatMapLatest { books ->
                    flow {
                        val series = seriesRepository.getSeriesOnce(groupId)
                            ?: run { emit(null); return@flow }
                        val totalMs = books.sumOf { it.totalDurationMs }
                        val progressList = books.map { repository.getProgressForBookOnce(it.id) }
                        val playedMs = books.zip(progressList)
                            .sumOf { (_, prog) -> prog?.positionMs ?: 0L }
                        val fraction = if (totalMs > 0)
                            (playedMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
                        emit(GroupScreenState(
                            seriesId         = groupId,
                            name             = series.name,
                            speed            = series.playbackSpeed ?: settings.currentDefaultSpeed,
                            books            = books,
                            totalDurationMs  = totalMs,
                            progressFraction = fraction,
                            coverArtPath     = series.coverArtPath ?: books.firstOrNull()?.coverArtPath
                        ))
                    }
                }
                .onEach { _groupInfo.value = it }
                .launchIn(viewModelScope)
        }
    }

    /** Re-bake the reflection/progressive-blur background from the current cover. */
    fun refreshCoverEffect() {
        if (bookId == -1L) return
        lastFxCover = null
        viewModelScope.launch { repository.regenerateCoverFx(bookId) }
    }

    val currentBoostDb: Int get() = playerController.currentVolumeBoostDb

    fun setVolumeBoost(db: Int) {
        playerController.setVolumeBoost(db)
        viewModelScope.launch { repository.updateBoostDb(bookId, db) }
    }

    /** Toggle silence-skipping for this book: persist the flag and apply it to live playback. */
    fun setSkipSilenceEnabled(enabled: Boolean) {
        val targetBookId = bookId.takeIf { it != -1L } ?: playbackState.value.bookId
        if (targetBookId == -1L) return
        playerController.setSkipSilence(enabled)
        viewModelScope.launch { repository.setSkipSilenceEnabled(targetBookId, enabled) }
    }

    fun setEqBands(bands: IntArray?) {
        _eqBandsMillibels.value = bands
        val json = bands?.let { JSONArray(it.toList()).toString() }
        playerController.setEqBands(json)
        viewModelScope.launch { repository.updateEqBands(bookId, json) }
    }

    // ── Per-book "local preset" reset: clear this book's override back to defaults ────────
    /** The global default speed — what a book falls back to when its override is cleared. */
    val defaultSpeed: Float get() = settings.currentDefaultSpeed
    /** Reset this book's playback speed to the global default. */
    fun clearBookSpeed() = setSpeed(settings.currentDefaultSpeed)
    /** Reset this book's volume boost to 0 dB. */
    fun clearBookBoost() = setVolumeBoost(0)
    /** Reset this book's EQ to flat (falls back to the global default). */
    fun clearBookEq() = setEqBands(null)

    fun saveAudioPreset(name: String, type: String) {
        val preset = when (type) {
            AudioPreset.TYPE_SPEED -> AudioPreset(name = name.trim(), type = type, speedMult = playbackState.value.speed)
            AudioPreset.TYPE_BOOST -> AudioPreset(name = name.trim(), type = type, boostDb = playerController.currentVolumeBoostDb)
            AudioPreset.TYPE_EQ   -> AudioPreset(name = name.trim(), type = type, eqBandsJson = _eqBandsMillibels.value?.let { JSONArray(it.toList()).toString() })
            else -> return
        }
        viewModelScope.launch { repository.insertAudioPreset(preset) }
    }

    fun loadAudioPreset(preset: AudioPreset) {
        when (preset.type) {
            AudioPreset.TYPE_SPEED -> setSpeed(preset.speedMult)
            AudioPreset.TYPE_BOOST -> {
                playerController.setVolumeBoost(preset.boostDb)
                viewModelScope.launch { repository.updateBoostDb(bookId, preset.boostDb) }
            }
            AudioPreset.TYPE_EQ -> {
                val bands = preset.eqBandsJson?.let { json ->
                    try { val arr = JSONArray(json); IntArray(arr.length()) { i -> arr.getInt(i) } }
                    catch (_: Exception) { null }
                }
                _eqBandsMillibels.value = bands
                playerController.setEqBands(preset.eqBandsJson?.takeIf { it.isNotEmpty() })
                viewModelScope.launch { repository.updateEqBands(bookId, preset.eqBandsJson) }
            }
        }
    }

    fun overwritePreset(preset: AudioPreset) {
        val updated = when (preset.type) {
            AudioPreset.TYPE_SPEED -> preset.copy(speedMult = playbackState.value.speed)
            AudioPreset.TYPE_BOOST -> preset.copy(boostDb = playerController.currentVolumeBoostDb)
            AudioPreset.TYPE_EQ   -> preset.copy(eqBandsJson = _eqBandsMillibels.value?.let { JSONArray(it.toList()).toString() })
            else -> return
        }
        viewModelScope.launch { repository.updateAudioPreset(updated) }
    }

    fun deleteAudioPreset(id: Long) {
        viewModelScope.launch { repository.deleteAudioPreset(id) }
    }

    fun setAsDefaultPreset(id: Long) {
        viewModelScope.launch {
            repository.setDefaultAudioPreset(id)
            settings.setDefaultAudioPresetId(id)
        }
    }

    // ── Synopsis state ───────────────────────────────────────────────────────
    private val _synopsisGenerating = MutableStateFlow(false)
    val synopsisGenerating: StateFlow<Boolean> = _synopsisGenerating.asStateFlow()

    private val _synopsisError = MutableStateFlow<String?>(null)
    val synopsisError: StateFlow<String?> = _synopsisError.asStateFlow()

    // ── Bookmarks ────────────────────────────────────────────────────────────
    val bookmarks: StateFlow<List<Bookmark>> =
        repository.getBookmarksForBook(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Listening history (single books only; empty for groups) ───────────────
    val listeningSessions: StateFlow<List<com.betteraudio.data.db.entities.ListeningSession>> =
        if (bookId != -1L) repository.getSessionsForBook(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        else MutableStateFlow(emptyList())

    val skipEvents: StateFlow<List<SkipEvent>> =
        if (bookId != -1L) repository.getSkipsForBook(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        else MutableStateFlow(emptyList())

    // Position history stack — pushed before jumps; pop to return, auto-clears after 10 min of uninterrupted playback
    private val _positionStack = MutableStateFlow<List<Long>>(emptyList())
    val positionStack: StateFlow<List<Long>> = _positionStack.asStateFlow()
    private var autoCommitJob: Job? = null

    private fun pushPosition(absMs: Long) {
        _positionStack.update { stack ->
            val limited = if (stack.size >= 20) stack.drop(1) else stack
            limited + absMs
        }
        restartAutoCommit()
    }

    private fun restartAutoCommit() {
        autoCommitJob?.cancel()
        autoCommitJob = viewModelScope.launch {
            var continuousPlayMs = 0L
            while (isActive) {
                delay(1_000)
                if (playbackState.value.isPlaying) {
                    continuousPlayMs += 1_000
                    if (continuousPlayMs >= 10 * 60_000L) {
                        confirmPosition()
                        break
                    }
                } else {
                    continuousPlayMs = 0L
                }
            }
        }
    }

    // ── Chapters ─────────────────────────────────────────────────────────────
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
        val books = seriesRepository.getBooksInSeriesOnce(groupId)
        val filesPerBook = seriesRepository.getAudioFilesForBooks(books.map { it.id })
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

    init {
        // Periodic progress save
        viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                saveProgressIfActive()
            }
        }

        // Wait for both book data and Gemini key before attempting generation.
        combine(bookWithProgress, settings.geminiApiKey) { bwp, key -> Pair(bwp, key) }
            .filter { (bwp, key) -> bwp?.book?.synopsis == null && key.isNotBlank() }
            .onEach { (bwp, _) ->
                val book = bwp?.book ?: return@onEach
                if (_synopsisGenerating.value) return@onEach
                runSynopsisGeneration(book.id, book.title, book.author)
            }
            .launchIn(viewModelScope)
    }

    // ── Synopsis actions ─────────────────────────────────────────────────────

    private suspend fun runSynopsisGeneration(bookId: Long, title: String, author: String) {
        _synopsisGenerating.value = true
        _synopsisError.value = null
        when (val result = synopsisService.generateSynopsis(title, author)) {
            is SynopsisResult.Success -> repository.updateSynopsis(bookId, result.text)
            is SynopsisResult.Error   -> _synopsisError.value = result.message
        }
        _synopsisGenerating.value = false
    }

    fun retrySynopsis() {
        if (_synopsisGenerating.value) return
        val book = bookWithProgress.value?.book ?: return
        viewModelScope.launch { runSynopsisGeneration(book.id, book.title, book.author) }
    }

    // ── Bookmark actions ─────────────────────────────────────────────────────

    fun addBookmark(comment: String) {
        val state = playbackState.value
        val bwp = bookWithProgress.value ?: return
        val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        val currentFile = files.getOrNull(state.currentFileIndex) ?: return
        val absPos = if (state.bookTotalDurationMs > 0) state.bookPositionMs else state.currentPositionMs
        viewModelScope.launch {
            repository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    fileId = currentFile.id,
                    positionInFileMs = state.currentPositionMs,
                    absolutePositionMs = absPos,
                    comment = comment.trim()
                )
            )
        }
    }

    fun jumpToBookmark(bookmark: Bookmark) {
        val currentAbsPos = if (playbackState.value.bookTotalDurationMs > 0)
            playbackState.value.bookPositionMs
        else
            playbackState.value.currentPositionMs
        pushPosition(currentAbsPos)
        recordSkip(currentAbsPos, bookmark.absolutePositionMs)
        playerController.bookSeekTo(bookmark.absolutePositionMs)
    }

    /**
     * Resume from where a past listening session ended: jump to that absolute book position
     * and start playing. This is a confirmed jump, so it's recorded as a skip.
     */
    fun resumeFromHistory(endBookPositionMs: Long) {
        AppLog.i("History", "resumeFromHistory target=${endBookPositionMs}ms book=$bookId")
        val currentAbsPos = if (playbackState.value.bookTotalDurationMs > 0)
            playbackState.value.bookPositionMs
        else
            playbackState.value.currentPositionMs
        // Start playback if this book isn't loaded yet, then seek.
        if (playbackState.value.bookId != bookId) play()
        pushPosition(currentAbsPos)
        recordSkip(currentAbsPos, endBookPositionMs)
        playerController.bookSeekTo(endBookPositionMs)
        if (!playbackState.value.isPlaying) playerController.togglePlayPause()
    }

    /**
     * Record a *confirmed* navigation jump (chapter select / bookmark jump / large scrubber
     * drag) into listening history. Fixed-amount skip-button taps are intentionally excluded.
     */
    private fun recordSkip(fromMs: Long, toMs: Long) {
        val targetBookId = playbackState.value.bookId.takeIf { it != -1L } ?: bookId
        if (targetBookId == -1L) return
        val items = chapters.value.rows.filterIsInstance<ChapterRow.Item>()
        val active = items.lastOrNull { it.absStartMs <= toMs }
        val idx = active?.let { items.indexOf(it) } ?: -1
        AppLog.i("History", "skip recorded book=$targetBookId ${fromMs}ms→${toMs}ms ch=$idx")
        viewModelScope.launch {
            repository.insertSkipEvent(
                SkipEvent(
                    bookId = targetBookId,
                    fromPositionMs = fromMs,
                    toPositionMs = toMs,
                    chapterIndex = idx,
                    chapterName = active?.title ?: ""
                )
            )
        }
    }

    fun returnFromJump() {
        returnToIndex(_positionStack.value.size - 1)
    }

    fun returnToIndex(index: Int) {
        val stack = _positionStack.value
        if (index < 0 || index >= stack.size) return
        val targetMs = stack[index]
        _positionStack.update { it.subList(0, index) }
        if (_positionStack.value.isEmpty()) {
            autoCommitJob?.cancel()
            autoCommitJob = null
        }
        playerController.bookSeekTo(targetMs)
    }

    fun confirmPosition() {
        _positionStack.value = emptyList()
        autoCommitJob?.cancel()
        autoCommitJob = null
    }

    fun seekToChapter(absMs: Long) {
        val currentAbsPos = if (playbackState.value.bookTotalDurationMs > 0)
            playbackState.value.bookPositionMs
        else
            playbackState.value.currentPositionMs
        pushPosition(currentAbsPos)
        recordSkip(currentAbsPos, absMs)
        playerController.bookSeekTo(absMs)
    }

    fun pushPositionIfLargeJump(beforeMs: Long, afterMs: Long) {
        if (kotlin.math.abs(afterMs - beforeMs) > 5 * 60_000L) {
            pushPosition(beforeMs)
            recordSkip(beforeMs, afterMs)
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    // ── Playback actions ─────────────────────────────────────────────────────

    private fun computeAutoRewindMs(progress: com.betteraudio.data.db.entities.PlaybackProgress?): Long {
        if (progress == null || progress.lastPausedAt <= 0L) return 0L
        val rewindMs = settings.currentAutoRewindSeconds * 1_000L
        if (rewindMs <= 0L) return 0L
        // If the app was stopped (backgrounded/killed) after the last in-app pause, always rewind
        if (settings.currentAppStoppedAt > progress.lastPausedAt) return rewindMs
        // Otherwise apply threshold: only rewind if paused longer than configured threshold
        val thresholdMs = settings.currentAutoRewindThresholdMinutes * 60_000L
        if (thresholdMs <= 0L) return 0L
        val elapsed = System.currentTimeMillis() - progress.lastPausedAt
        return if (elapsed >= thresholdMs) rewindMs else 0L
    }

    fun play() {
        if (groupId != -1L) { playGroup(); return }
        viewModelScope.launch {
            val bwp = bookWithProgress.value ?: return@launch
            val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            if (files.isEmpty()) return@launch
            val progress = bwp.progress
            val startIndex = files.indexOfFirst { it.id == progress?.currentFileId }.coerceAtLeast(0)
            val rawPos = if (progress?.isCompleted == true) 0L else (progress?.positionMs ?: 0L)
            val rewind = computeAutoRewindMs(progress)
            // Never rewind past the chapter/file boundary: if the saved position is shorter than
            // the rewind amount, resume from the saved position instead of the file start.
            val startPos = if (rawPos >= rewind) rawPos - rewind else rawPos
            val speed = progress?.playbackSpeed ?: settings.currentDefaultSpeed
            AppLog.i("Player", "play() book=${bwp.book.id}" +
                " dbFile=${progress?.currentFileId} dbPos=${progress?.positionMs}ms isCompleted=${progress?.isCompleted}" +
                " → rawPos=${rawPos}ms rewind=${rewind}ms startIdx=$startIndex startPos=${startPos}ms")
            playerController.playBook(bwp.book, files, startIndex, startPos, speed)
            // Restore per-book boost and EQ so they don't bleed from other books
            playerController.setVolumeBoost(progress?.boostDb ?: 0)
            val savedEq = progress?.eqBandsJson
            _eqBandsMillibels.value = savedEq?.let { json ->
                try { val arr = JSONArray(json); IntArray(arr.length()) { i -> arr.getInt(i) } }
                catch (_: Exception) { null }
            }
            playerController.setEqBands(savedEq)
            playerController.setSkipSilence(bwp.book.skipSilenceEnabled)
            // Mark as just-played now so last-played sorting moves it to the top immediately.
            repository.touchLastPlayed(bwp.book.id)
            settings.setLastPlayedBookId(bwp.book.id)
        }
    }

    private fun playGroup() {
        viewModelScope.launch {
            val state = _groupInfo.value ?: return@launch
            val filesPerBook = seriesRepository.getAudioFilesForBooks(state.books.map { it.id })
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
                    globalIndex += files.indexOfFirst { it.id == resumeProgress?.currentFileId }
                        .coerceAtLeast(0)
                    break
                }
                globalIndex += files.size
            }
            val startPos = if (resumeProgress?.isCompleted == true) 0L
                           else resumeProgress?.positionMs ?: 0L
            playerController.playBookGroup(
                groupId              = state.seriesId,
                groupName            = state.name,
                coverArtPath         = state.coverArtPath,
                orderedBooks         = state.books,
                filesPerBook         = filesPerBook,
                startGlobalFileIndex = globalIndex,
                startPositionMs      = startPos,
                speed                = state.speed
            )
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
        if (bookId == -1L) return
        viewModelScope.launch { repository.updateBookStatus(bookId, status) }
    }

    fun updateBookMetadata(titleOverride: String?, authorOverride: String?) {
        if (bookId == -1L) return
        viewModelScope.launch { repository.updateBookMetadata(bookId, titleOverride, authorOverride) }
    }

    fun updateSeriesInfo(seriesName: String?, seriesOrder: Float?) {
        if (bookId == -1L) return
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
        if (bookId == -1L) return
        val state = playbackState.value
        if (state.bookId != bookId) return
        val bwp = bookWithProgress.value ?: return
        val sortedFiles = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        val currentFile = sortedFiles.getOrNull(state.currentFileIndex) ?: return
        val positionMs = playerController.currentPositionMs
        if (positionMs <= 0L) return
        // NonCancellable: this runs from onDispose where viewModelScope may be cancelled imminently.
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            repository.updatePosition(bookId, currentFile.id, positionMs)
        }
    }

    private fun saveProgressIfActive() {
        if (bookId == -1L) return
        val state = playbackState.value
        if (state.bookId != bookId) return
        val bwp = bookWithProgress.value ?: return
        val sortedFiles = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        val currentFile = sortedFiles.getOrNull(state.currentFileIndex) ?: return
        val positionMs = playerController.currentPositionMs
        if (positionMs <= 0L) return
        viewModelScope.launch {
            repository.updatePosition(bookId, currentFile.id, positionMs)
        }
    }
}
