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
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.first
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
import kotlinx.coroutines.withContext
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
        val absStartMs: Long,   // position within its own book
        val durationMs: Long,
        val key: Long,
        val bookId: Long = -1L  // which book this chapter belongs to (for series chapter lists)
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
    private val seriesPlayer: com.betteraudio.playback.SeriesPlayer,
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

    // The current book's series (if any), for the cover toggle and author/narrator fallback.
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentSeries: StateFlow<com.betteraudio.data.db.entities.Series?> =
        bookWithProgress
            .map { it?.book?.seriesId }
            .distinctUntilChanged()
            .flatMapLatest { sid -> if (sid == null) flowOf(null) else seriesRepository.getSeries(sid) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val seriesCover: StateFlow<String?> =
        currentSeries.map { it?.coverArtPath }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Configured skip intervals — surfaced so the transport buttons can show the real seconds.
    val skipForwardMs: StateFlow<Long> =
        settings.skipForwardMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_FORWARD_MS)
    val skipBackMs: StateFlow<Long> =
        settings.skipBackMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_BACK_MS)

    // ── Audio presets (unified bundles: speed + boost + EQ) ───────────────────
    val allPresets: StateFlow<List<AudioPreset>> =
        repository.getAllAudioPresets()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The global default preset (drives a book's audio when it has no override of its own). */
    val defaultPreset: StateFlow<AudioPreset?> =
        allPresets.map { list -> list.firstOrNull { it.isDefault } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    /** The global default speed — the default preset's speed, else the scalar fallback. */
    val defaultSpeed: Float get() = defaultPreset.value?.speedMult ?: settings.currentDefaultSpeed
    /** Reset this book's playback speed to the global default. */
    fun clearBookSpeed() = setSpeed(defaultSpeed)
    /** Reset this book's volume boost to 0 dB. */
    fun clearBookBoost() = setVolumeBoost(0)
    /** Reset this book's EQ to flat (falls back to the global default). */
    fun clearBookEq() = setEqBands(null)

    /** Snapshot the live speed + boost + EQ as a full preset bundle. */
    private fun currentEqJson(): String? =
        _eqBandsMillibels.value?.takeIf { it.any { b -> b != 0 } }?.let { JSONArray(it.toList()).toString() }

    fun saveAudioPreset(name: String) {
        val preset = AudioPreset(
            name = name.trim(),
            type = AudioPreset.TYPE_BUNDLE,
            speedMult = playbackState.value.speed,
            boostDb = playerController.currentVolumeBoostDb,
            eqBandsJson = currentEqJson()
        )
        viewModelScope.launch { repository.insertAudioPreset(preset) }
    }

    /** Apply a whole preset bundle (speed + boost + EQ) to the current book and persist it. */
    fun loadAudioPreset(preset: AudioPreset) {
        setSpeed(preset.speedMult)
        playerController.setVolumeBoost(preset.boostDb)
        val bands = preset.eqBandsJson?.let { json ->
            try { val arr = JSONArray(json); IntArray(arr.length()) { i -> arr.getInt(i) } }
            catch (_: Exception) { null }
        }
        _eqBandsMillibels.value = bands
        playerController.setEqBands(preset.eqBandsJson?.takeIf { it.isNotEmpty() })
        viewModelScope.launch {
            repository.updateBoostDb(bookId, preset.boostDb)
            repository.updateEqBands(bookId, preset.eqBandsJson)
        }
    }

    fun overwritePreset(preset: AudioPreset) {
        val updated = preset.copy(
            speedMult = playbackState.value.speed,
            boostDb = playerController.currentVolumeBoostDb,
            eqBandsJson = currentEqJson()
        )
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
    // When the current book belongs to a series, the list spans the WHOLE series (a header per
    // member book, then its chapters). Otherwise it's just this book's chapters.
    @OptIn(ExperimentalCoroutinesApi::class)
    val chapters: StateFlow<ChapterUiState> =
        if (bookId == -1L) MutableStateFlow(ChapterUiState())
        else repository.getBookById(bookId)
            .map { it?.seriesId }
            .distinctUntilChanged()
            .flatMapLatest { seriesId ->
                if (seriesId != null) flow { emit(buildSeriesChapters(seriesId)) }
                else combine(
                    repository.getChaptersForBook(bookId),
                    bookWithProgress
                ) { chs, bwp -> buildBookChapters(chs, bwp) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterUiState())

    private fun buildBookChapters(chapters: List<Chapter>, bwp: BookWithProgress?): ChapterUiState {
        val bId = bwp?.book?.id ?: bookId
        val files = bwp?.audioFiles
            ?.sortedWith(compareBy({ it.trackNumber }, { it.fileName })) ?: emptyList()
        val cum = cumulativeStarts(files.map { it.id to it.durationMs })
        val rows = if (chapters.isNotEmpty()) {
            chapters.map { c ->
                ChapterRow.Item(c.title, (cum[c.fileId] ?: 0L) + c.startInFileMs, c.durationMs, c.id, bId)
            }
        } else {
            files.map { f ->
                ChapterRow.Item(f.chapterTitle ?: f.fileName, cum[f.id] ?: 0L, f.durationMs, f.id, bId)
            }
        }
        return ChapterUiState(rows)
    }

    /** All member books of [seriesId] as headers + their chapters; positions are within each book. */
    private suspend fun buildSeriesChapters(seriesId: Long): ChapterUiState {
        val books = seriesRepository.getBooksInSeriesOnce(seriesId)
        val filesPerBook = seriesRepository.getAudioFilesForBooks(books.map { it.id })
        val rows = mutableListOf<ChapterRow>()
        for (b in books) {
            rows.add(ChapterRow.BookHeader(b.displayTitle))
            val files = filesPerBook[b.id] ?: emptyList()
            val cum = cumulativeStarts(files.map { it.id to it.durationMs })   // within this book
            val chs = repository.getChaptersForBookOnce(b.id)
            if (chs.isNotEmpty()) {
                chs.forEach { c ->
                    rows.add(ChapterRow.Item(c.title, (cum[c.fileId] ?: 0L) + c.startInFileMs, c.durationMs, c.id, b.id))
                }
            } else {
                files.forEach { f ->
                    rows.add(ChapterRow.Item(f.chapterTitle ?: f.fileName, cum[f.id] ?: 0L, f.durationMs, f.id, b.id))
                }
            }
        }
        return ChapterUiState(rows)
    }

    /**
     * Chapter tapped in the chapter list. Within the currently-playing book → seek; a chapter in
     * a different series book → start that book at the chapter's position (the series continues
     * from there).
     */
    fun onChapterSelected(item: ChapterRow.Item) {
        val playingBook = playbackState.value.bookId
        if (item.bookId == -1L || item.bookId == playingBook) {
            seekToChapter(item.absStartMs)
        } else {
            val sid = bookWithProgress.value?.book?.seriesId ?: return
            viewModelScope.launch { seriesPlayer.playSeriesBookAt(sid, item.bookId, item.absStartMs) }
        }
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
            // A book inherits, in order: its own override → its series default → the global
            // default preset → the scalar fallback. The default preset makes global speed/boost/EQ
            // apply to every book that hasn't been individually tuned.
            val series = bwp.book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
            val gPreset = repository.getDefaultAudioPreset()
            val speed = com.betteraudio.playback.AudioCascade.speed(progress?.playbackSpeed, series?.playbackSpeed, gPreset?.speedMult ?: settings.currentDefaultSpeed)
            AppLog.i("Player", "play() book=${bwp.book.id}" +
                " dbFile=${progress?.currentFileId} dbPos=${progress?.positionMs}ms isCompleted=${progress?.isCompleted}" +
                " → rawPos=${rawPos}ms rewind=${rewind}ms startIdx=$startIndex startPos=${startPos}ms")
            playerController.playBook(bwp.book, files, startIndex, startPos, speed)
            // Restore per-book (or inherited series/global) boost and EQ so they don't bleed between books
            playerController.setVolumeBoost(com.betteraudio.playback.AudioCascade.boost(progress?.boostDb, series?.boostDb, gPreset?.boostDb ?: 0))
            val savedEq = com.betteraudio.playback.AudioCascade.eq(progress?.eqBandsJson, series?.eqBandsJson, gPreset?.eqBandsJson)
            _eqBandsMillibels.value = savedEq?.let { json ->
                try { val arr = JSONArray(json); IntArray(arr.length()) { i -> arr.getInt(i) } }
                catch (_: Exception) { null }
            }
            playerController.setEqBands(savedEq)
            playerController.setSkipSilence(com.betteraudio.playback.AudioCascade.skipSilence(bwp.book.skipSilenceEnabled, series?.skipSilenceEnabled))
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

    /** Change the global skip intervals (from long-pressing the transport skip buttons). */
    fun setSkipForwardMs(ms: Long) = viewModelScope.launch { settings.setSkipForwardMs(ms) }
    fun setSkipBackMs(ms: Long)    = viewModelScope.launch { settings.setSkipBackMs(ms) }
    fun seekTo(posMs: Long) = playerController.seekTo(posMs)
    fun bookSeekTo(bookPosMs: Long) = playerController.bookSeekTo(bookPosMs)
    fun jumpToFile(index: Int) = playerController.jumpToFile(index)

    /**
     * "Read from here": converts the current audio position into a text locator via
     * [com.betteraudio.sync.PositionBridge] and persists it, then invokes [onReady] with the book
     * id so the caller can navigate to the reader. No-op if this book has no connected epub.
     */
    fun readFromHere(onReady: (Long) -> Unit) {
        if (bookId == -1L) return
        val book = bookWithProgress.value?.book ?: return
        val epubPath = book.ebookPath ?: return
        viewModelScope.launch {
            val files = repository.getAudioFilesOnce(bookId)
            if (files.isEmpty()) return@launch
            val chapterEntities = repository.getChaptersForBookOnce(bookId)
            val spans = com.betteraudio.sync.AudioSpanBuilder.build(files, chapterEntities)
            if (spans.isEmpty()) return@launch

            val bookPosMs = playbackState.value.bookPositionMs
            val anchors = repository.getSyncAnchorsOnce(bookId)
                .map { com.betteraudio.sync.AnchorPoint(it.audioMs, it.spineIndex, it.charOffset) }

            // Parse + resolve inside a single parser session (transient — the reader has its own).
            val locator = withContext(Dispatchers.IO) {
                com.betteraudio.data.ebook.EpubParser(java.io.File(epubPath)).use { parser ->
                    val info = runCatching { parser.parse() }.getOrNull()
                    if (info == null || info.encrypted || info.spine.isEmpty()) return@use null
                    val paraCache = HashMap<Int, com.betteraudio.data.ebook.SpineParagraphs?>()
                    fun paragraphsFor(idx: Int): com.betteraudio.data.ebook.SpineParagraphs? =
                        paraCache.getOrPut(idx) {
                            info.spine.getOrNull(idx)?.href?.let { href ->
                                parser.readEntry(href)?.let { com.betteraudio.data.ebook.ParagraphExtractor.extract(it) }
                            }
                        }

                    // Prefer the anchors alone (immune to a ChapterMap that's wrong because the
                    // audio is split into narration "Parts" rather than actual chapters).
                    if (anchors.size >= 2) {
                        com.betteraudio.sync.PositionBridge.audioToCharAnchored(bookPosMs, anchors) { idx -> paragraphsFor(idx)?.totalChars ?: 0 }
                            ?.let { (spineIdx, charOffset) ->
                                val paras = paragraphsFor(spineIdx)
                                if (paras != null && paras.totalChars > 0) {
                                    return@use com.betteraudio.sync.TextLocator(spineIdx, paras.fractionForCharOffset(charOffset))
                                }
                            }
                    }

                    val map = com.betteraudio.sync.ChapterMap.fromJson(book.chapterMapJson) ?: run {
                        val matched = com.betteraudio.sync.ChapterMatcher.autoMatch(spans, info.spine)
                        repository.setChapterMap(bookId, matched.toJson())
                        matched
                    }
                    val coarse = com.betteraudio.sync.PositionBridge.audioToText(bookPosMs, spans, map, info.spine.size)
                    val paras = paragraphsFor(coarse.spineIndex)
                    if (paras == null || paras.totalChars == 0) coarse
                    else {
                        val (spineIdx, charOffset) = com.betteraudio.sync.PositionBridge.audioToChar(
                            bookPosMs, spans, map, info.spine.size, paras.totalChars, anchors
                        )
                        com.betteraudio.sync.TextLocator(spineIdx, paras.fractionForCharOffset(charOffset))
                    }
                }
            } ?: return@launch

            val spineCount = book.ebookSpineCount.coerceAtLeast(locator.spineIndex + 1)
            val overall = (locator.spineIndex + locator.fraction) / spineCount
            repository.updateTextPosition(bookId, locator.spineIndex, locator.fraction, overall)
            AppLog.i("Player", "readFromHere book=$bookId pos=${bookPosMs}ms -> spine=${locator.spineIndex} frac=${locator.fraction}")
            onReady(bookId)
        }
    }

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
