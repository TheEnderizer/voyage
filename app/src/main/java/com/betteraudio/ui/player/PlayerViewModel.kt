package com.betteraudio.ui.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
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
import com.betteraudio.playback.ChapterTimeline
import com.betteraudio.playback.JumpRestore
import com.betteraudio.playback.JumpRestoreStore
import com.betteraudio.playback.PlaybackState
import com.betteraudio.playback.PlayerController
import com.betteraudio.playback.PositionState
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

/** A row in the chapter list: a book-title header (for a playing series' member books) or a chapter. */
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

// Jump events are kept generously (see AudiobookRepository.insertSkipEventPruned) — a user
// relies on them to find "where was I yesterday". PlayerController has its own, tighter keep
// counts for the higher-frequency "auto"/"skip_button" sources it records.
private const val JUMP_HISTORY_KEEP = 100

// A scrubber drag this small is a nudge, not navigation — below it nothing is armed, so a tap
// that lands where it started doesn't leave a Return pill behind. Anything larger does: the only
// deliberately exempt seeks are the fixed-amount skip buttons, which never route through here.
private const val SCRUB_RETURN_MIN_MS = 3_000L
// Listening history's "Skips" list keeps its original, coarser meaning — only a genuinely long
// drag is a navigation event worth remembering, even though far smaller ones now arm the pills.
private const val SCRUB_HISTORY_MIN_MS = 5 * 60_000L

/**
 * A bookmark resolved against the book's *current* file/chapter timeline.
 *
 * The stored anchor is file-relative ([Bookmark.fileId] + [Bookmark.positionInFileMs]), so
 * [absPositionMs] is re-derived here on every emission rather than trusting the absolute offset
 * saved at creation time — that offset shifts for every bookmark after any file the user deletes,
 * while the file-relative anchor keeps pointing at the same audio. [Bookmark.absolutePositionMs]
 * is only the fallback for a bookmark whose own file is gone (and [fileMissing] flags that).
 */
data class BookmarkUi(
    val bookmark: Bookmark,
    val absPositionMs: Long,
    val chapterName: String,
    val positionInChapterMs: Long,
    val fileMissing: Boolean,
)

/**
 * Re-anchors [b] onto [tl]. Top-level and pure so the file-deleted case is unit-testable
 * (`BookmarkResolveTest`) — the whole point of the feature is behaviour under a changed file set.
 *
 * When the bookmark's own file is gone there is nothing to anchor to: it falls back to the stored
 * absolute offset, which after a deletion is only approximate, so no chapter is named — that
 * offset can land past the end of the shortened book, where [ChapterTimeline.chapterAt] clamps to
 * the last mark and would otherwise print a confident but wrong "Epilogue - 1:12:44".
 */
internal fun resolveBookmark(b: Bookmark, tl: ChapterTimeline): BookmarkUi {
    val known = tl.fileIds.contains(b.fileId)
    if (!known) {
        return BookmarkUi(
            bookmark = b,
            absPositionMs = b.absolutePositionMs,
            chapterName = "",
            positionInChapterMs = b.absolutePositionMs,
            // An empty timeline just means it hasn't loaded yet — don't call every bookmark orphaned.
            fileMissing = tl.fileIds.isNotEmpty(),
        )
    }
    val abs = tl.startOfFileMs(b.fileId) + b.positionInFileMs
    val mark = tl.chapterAt(abs)
    return BookmarkUi(
        bookmark = b,
        absPositionMs = abs,
        chapterName = mark?.title.orEmpty(),
        positionInChapterMs = (abs - (mark?.startMs ?: 0L)).coerceAtLeast(0L),
        fileMissing = false,
    )
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
    private val widgetUpdater: com.betteraudio.widget.WidgetUpdater,
    private val jumpRestoreStore: JumpRestoreStore,
    private val libraryRestructurer: com.betteraudio.data.files.LibraryRestructurer,
    private val diskMirror: com.betteraudio.data.diskstore.DiskMirror,
    private val bookDataStore: com.betteraudio.data.diskstore.BookDataStore,
    private val coverSearchService: com.betteraudio.data.covers.CoverSearchService,
    val playerController: PlayerController
) : ViewModel() {

    // ── Online cover search ─────────────────────────────────────────────────
    // Same behaviour as HomeViewModel's, scoped to this screen's one book: the picked image is
    // written into the book's own data/ folder tagged "user", which is what makes the choice
    // outrank a folder cover.png on the next scan and survive a reinstall (see
    // AudioFileScanner's cover-priority chain).

    private val _coverSearchOpen = MutableStateFlow(false)
    val coverSearchOpen: StateFlow<Boolean> = _coverSearchOpen.asStateFlow()

    fun openCoverSearch() { _coverSearchOpen.value = true }
    fun closeCoverSearch() { _coverSearchOpen.value = false }

    suspend fun searchCovers(query: String): List<String> = coverSearchService.search(query)

    fun setCoverFromUrl(imageUrl: String) {
        if (bookId == -1L) return
        viewModelScope.launch {
            val book = repository.getBookOnce(bookId)
            val bytes = coverSearchService.downloadBytes(imageUrl)
            val path = if (book != null && bytes != null)
                bookDataStore.writeCoverBytes(book.folderPath, "user", "jpg", bytes) else null
            if (path != null) {
                repository.updateCoverArt(bookId, path)
                widgetUpdater.refreshCoverIfCurrent(bookId, path)
            }
            closeCoverSearch()
        }
    }

    val bookId: Long = savedStateHandle["bookId"] ?: -1L

    val bookWithProgress: StateFlow<BookWithProgress?> =
        if (bookId != -1L)
            repository.getBookWithProgress(bookId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
        else
            MutableStateFlow(null)

    val playbackState: StateFlow<PlaybackState> = playerController.playbackState
    val positionState: StateFlow<PositionState> = playerController.positionState

    // An involuntary jump detected service-side (PlaybackService.handleJumpDetection), filtered to
    // whichever book is actually playing right now — so the restore pill shows a jump caught
    // during background/cold-widget playback the moment the player is next opened.
    val jumpRestore: StateFlow<JumpRestore?> =
        combine(jumpRestoreStore.restore, playbackState) { restore, pbState ->
            val activeBookId = pbState.bookId.takeIf { it != -1L } ?: bookId
            restore?.takeIf { it.bookId == activeBookId }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Series cover mode: when true, a book that belongs to a series shows the SERIES cover in the
    // player instead of the book's own cover. Persisted globally; toggled from the overflow menu.
    val showSeriesCover: StateFlow<Boolean> =
        settings.playerShowSeriesCover.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun toggleShowSeriesCover() {
        viewModelScope.launch { settings.setPlayerShowSeriesCover(!showSeriesCover.value) }
    }

    // Which of the two landscape layouts the Material You player draws (Settings → Theme). Read
    // only by the Material You player; portrait and the Immersive look ignore it entirely.
    val landscapePlayerStyle: StateFlow<com.betteraudio.ui.material.player.LandscapePlayerStyle> =
        settings.playerLandscapeStyle
            .map { com.betteraudio.ui.material.player.LandscapePlayerStyle.fromName(it) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5_000),
                com.betteraudio.ui.material.player.LandscapePlayerStyle.RAILS
            )

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

    // Skip-silence tuning (global) — surfaced so the player's long-press sheet (SkipSilenceSettingsSheet)
    // can show/edit the same values as Settings → Playback without pulling SettingsViewModel into
    // the player's nested NavHost.
    val skipSilenceMinMs: StateFlow<Long> =
        settings.skipSilenceMinMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SILENCE_MIN_MS)
    val skipSilenceThreshold: StateFlow<Int> =
        settings.skipSilenceThreshold.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SILENCE_THRESHOLD)
    val skipSilencePaddingMs: StateFlow<Long> =
        settings.skipSilencePaddingMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SKIP_SILENCE_PADDING_MS)
    fun setSkipSilenceMinMs(ms: Long) = viewModelScope.launch { settings.setSkipSilenceMinMs(ms) }
    fun setSkipSilenceThreshold(level: Int) = viewModelScope.launch { settings.setSkipSilenceThreshold(level) }
    fun setSkipSilencePaddingMs(ms: Long) = viewModelScope.launch { settings.setSkipSilencePaddingMs(ms) }

    // Sleep timer (global) — surfaced so the player's long-press options sheet (SleepTimerSheet)
    // can show/edit the same values as Settings → Playback without pulling SettingsViewModel into
    // the player's nested NavHost.
    val sleepTimerMinutes: StateFlow<Int> =
        settings.sleepTimerMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_TIMER_MINUTES)
    val sleepFadeSeconds: StateFlow<Int> =
        settings.sleepFadeSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_FADE_SECONDS)
    val sleepShakeEnabled: StateFlow<Boolean> =
        settings.sleepShakeEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SHAKE_ENABLED)
    val sleepShakeResetMinutes: StateFlow<Int> =
        settings.sleepShakeResetMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SHAKE_RESET_MINUTES)
    val sleepScheduleEnabled: StateFlow<Boolean> =
        settings.sleepScheduleEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val sleepScheduleStartMinutes: StateFlow<Int> =
        settings.sleepScheduleStartMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SCHEDULE_START_MINUTES)
    val sleepScheduleEndMinutes: StateFlow<Int> =
        settings.sleepScheduleEndMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SCHEDULE_END_MINUTES)
    val sleepScheduleDefaultMinutes: StateFlow<Int> =
        settings.sleepScheduleDefaultMinutes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsStore.DEFAULT_SLEEP_SCHEDULE_DEFAULT_MINUTES)
    fun setSleepTimerMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepTimerMinutes(minutes) }
    fun setSleepFadeSeconds(seconds: Int) = viewModelScope.launch { settings.setSleepFadeSeconds(seconds) }
    fun setSleepShakeEnabled(enabled: Boolean) = viewModelScope.launch { settings.setSleepShakeEnabled(enabled) }
    fun setSleepShakeResetMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepShakeResetMinutes(minutes) }
    fun setSleepScheduleEnabled(enabled: Boolean) = viewModelScope.launch { settings.setSleepScheduleEnabled(enabled) }
    fun setSleepScheduleStartMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepScheduleStartMinutes(minutes) }
    fun setSleepScheduleEndMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepScheduleEndMinutes(minutes) }
    fun setSleepScheduleDefaultMinutes(minutes: Int) = viewModelScope.launch { settings.setSleepScheduleDefaultMinutes(minutes) }

    fun setEqBands(bands: IntArray?) {
        _eqBandsMillibels.value = bands
        val json = bands?.let { JSONArray(it.toList()).toString() }
        playerController.setEqBands(json)
        viewModelScope.launch { repository.updateEqBands(bookId, json) }
    }

    // ── Stereo balance / mono — global (not per-book), unlike speed/boost/EQ above ────────────
    val audioBalance: StateFlow<Float> =
        settings.audioBalance.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)
    val monoAudio: StateFlow<Boolean> =
        settings.monoAudio.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAudioBalance(value: Float) {
        playerController.setChannelMix(value, monoAudio.value)
        viewModelScope.launch { settings.setAudioBalance(value) }
    }

    fun setMonoAudio(enabled: Boolean) {
        playerController.setChannelMix(audioBalance.value, enabled)
        viewModelScope.launch { settings.setMonoAudio(enabled) }
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
        val files = bwp?.audioFiles ?: emptyList()
        // ChapterTimeline.build sorts files itself, derives endMs from the next mark (immune to
        // a truncated chpl atom's bogus last-chapter duration), and drops orphan-fileId rows
        // instead of silently anchoring them at position 0.
        val timeline = ChapterTimeline.build(files, chapters, bId)
        val rows = timeline.marks.map { m -> ChapterRow.Item(m.title, m.startMs, m.durationMs, m.key, bId) }
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
            val chs = repository.getChaptersForBookOnce(b.id)
            val timeline = ChapterTimeline.build(files, chs, b.id)
            timeline.marks.forEach { m ->
                rows.add(ChapterRow.Item(m.title, m.startMs, m.durationMs, m.key, b.id))
            }
        }
        return ChapterUiState(rows)
    }

    // The screen's own book's chapter/file timeline — the single source behind the chapter pill,
    // the chapter-relative scrubber, and next/previous-chapter button state. Deliberately keyed
    // on THIS screen's book (bookId), not whichever book happens to be playing: PlayerScreen
    // already has a separate serviceHasBook check for "is the service actually on this book", and
    // mixing the two here would make the chapter list come from one book while the position came
    // from another. DB-backed, so it's populated before anything plays (see Part B).
    @OptIn(ExperimentalCoroutinesApi::class)
    val chapterTimeline: StateFlow<ChapterTimeline> =
        if (bookId == -1L) MutableStateFlow(ChapterTimeline.EMPTY)
        else combine(repository.getChaptersForBook(bookId), bookWithProgress) { chs, bwp ->
            ChapterTimeline.build(bwp?.audioFiles ?: emptyList(), chs, bookId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterTimeline.EMPTY)

    /** [bookmarks] re-anchored onto the live [chapterTimeline] — see [BookmarkUi]. Declared after
     *  [chapterTimeline] because it reads it during initialisation. */
    val bookmarkRows: StateFlow<List<BookmarkUi>> =
        combine(bookmarks, chapterTimeline) { list, tl ->
            // Re-sorted here, not left to the DAO's `ORDER BY absolutePositionMs`: that column is
            // the frozen creation-time offset, which stops matching the resolved order as soon as
            // a file is removed.
            list.map { resolveBookmark(it, tl) }.sortedBy { it.absPositionMs }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class ChapterNavState(
        val count: Int = 0,
        val hasPrev: Boolean = false,
        val hasNext: Boolean = false,
        val currentIndex: Int = -1,
    )

    /** Recomputes on every position tick, but data-class equality on the resulting StateFlow
     *  value suppresses re-emission except at an actual chapter boundary (same trick
     *  [PlayerController]'s own syncState uses for [PlaybackState]). */
    val chapterNav: StateFlow<ChapterNavState> =
        combine(chapterTimeline, positionState) { tl, pos ->
            if (tl.isEmpty) return@combine ChapterNavState()
            val bookPos = if (pos.bookTotalDurationMs > 0) pos.bookPositionMs else pos.currentPositionMs
            val mark = tl.chapterAt(bookPos)
            val idx = mark?.index ?: -1
            val hasPrev = idx > 0 || (mark != null && bookPos - mark.startMs > ChapterTimeline.PREV_RESTART_MS)
            ChapterNavState(
                count = tl.marks.size,
                hasPrev = hasPrev,
                hasNext = tl.nextStartMs(bookPos) != null,
                currentIndex = idx,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChapterNavState())

    /** Jump to the next/previous chapter of THIS screen's book, routed through [seekToChapter] so
     *  it gets the same position-stack push + confirmed-jump history a chapter-list tap gets. */
    fun nextChapter() {
        val pos = positionState.value.let { if (it.bookTotalDurationMs > 0) it.bookPositionMs else it.currentPositionMs }
        val target = chapterTimeline.value.nextStartMs(pos) ?: return
        seekToChapter(target)
    }

    fun prevChapter() {
        val pos = positionState.value.let { if (it.bookTotalDurationMs > 0) it.bookPositionMs else it.currentPositionMs }
        val target = chapterTimeline.value.prevStartMs(pos) ?: return
        seekToChapter(target)
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

    /**
     * Arms the sleep timer to fire at the end of the chapter currently playing, resolved from
     * [chapterTimeline] (this screen's book). Only meaningful when this screen's book is the one
     * actually playing — a mismatch (e.g. a series auto-advanced past this screen) leaves it a
     * no-op rather than arming a timer against a chapter position from the wrong book.
     */
    fun setSleepTimerEndOfCurrentChapter() {
        val playingBookId = playbackState.value.bookId
        if (playingBookId != -1L && playingBookId != bookId) return
        val bookPos = positionState.value.bookPositionMs
        val target = chapterTimeline.value.chapterAt(bookPos)?.endMs ?: return
        playerController.setSleepTimerEndOfChapter(target)
    }

    init {
        // Periodic progress save lives in PlaybackService's saver (survives the UI dying);
        // this ViewModel still saves on pause/dispose (saveProgress) and on demand.

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

    /**
     * True when the service actually holds THIS screen's book. [PlayerController] resolves every
     * position and every `bookSeekTo` against whichever book it currently has loaded, so anything
     * here that reads [positionState] or seeks must check this first — in a series the service can
     * be a member book ahead of this screen, and on a cold open it holds nothing at all. Mirrors
     * `serviceHasBook` in both PlayerScreens.
     */
    private fun serviceHasThisBook(): Boolean =
        bookId != -1L && playbackState.value.bookId == bookId

    /** Where this screen's book currently sits: the live position when the service holds it, else
     *  its saved DB position re-derived through [chapterTimeline] — i.e. the same value the
     *  scrubber is displaying in that case, rather than another book's playhead. */
    private fun displayBookPositionMs(): Long {
        if (serviceHasThisBook()) {
            val pos = positionState.value
            return if (pos.bookTotalDurationMs > 0) pos.bookPositionMs else pos.currentPositionMs
        }
        val p = bookWithProgress.value?.progress ?: return 0L
        val fileId = p.currentFileId ?: return p.positionMs
        return chapterTimeline.value.startOfFileMs(fileId) + p.positionMs
    }

    /**
     * Saves the current spot as a **file-relative** anchor (which file, how far into it) — the
     * absolute book offset is stored alongside only as a fallback for when that file goes missing.
     * The file/offset pair comes from [chapterTimeline] rather than
     * [PlaybackState.currentFileIndex], so it's derived from the same timeline everything else on
     * this screen uses instead of a queue index that belongs to whichever book is playing.
     */
    fun addBookmark(comment: String) {
        val bwp = bookWithProgress.value ?: return
        // displayBookPositionMs (not positionState directly) so bookmarking a book the service
        // isn't holding saves the spot this screen is actually showing, rather than another
        // book's playhead reinterpreted against this book's timeline.
        val absPos = displayBookPositionMs()
        val locus = chapterTimeline.value.locate(absPos)
        val fileId: Long
        val inFileMs: Long
        if (locus != null) {
            fileId = locus.fileId
            inFileMs = locus.offsetInFileMs
        } else {
            // No timeline yet (nothing scanned) — fall back to the playing queue index.
            val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            fileId = files.getOrNull(playbackState.value.currentFileIndex)?.id ?: return
            inFileMs = positionState.value.currentPositionMs
        }
        viewModelScope.launch {
            repository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    fileId = fileId,
                    positionInFileMs = inFileMs,
                    absolutePositionMs = absPos,
                    comment = comment.trim()
                )
            )
        }
    }

    /** Seeks to the bookmark's file-relative anchor re-resolved against the current timeline, so a
     *  book that has since lost a file still lands on the right audio. See [BookmarkUi]. */
    fun jumpToBookmark(bookmark: Bookmark) {
        val currentAbsPos = displayBookPositionMs()
        val target = resolveBookmark(bookmark, chapterTimeline.value).absPositionMs
        viewModelScope.launch {
            // Same ordering constraint as resumeFromHistory: load this book before seeking into it.
            if (!serviceHasThisBook()) startPlayback()
            pushPosition(currentAbsPos)
            recordSkip(currentAbsPos, target)
            playerController.bookSeekTo(target)
        }
    }

    /**
     * Resume from where a past listening session ended: jump to that absolute book position
     * and start playing. This is a confirmed jump, so it's recorded as a skip.
     */
    fun resumeFromHistory(endBookPositionMs: Long) {
        AppLog.i(LogCat.PLAYBACK, "resumeFromHistory target=${endBookPositionMs}ms book=$bookId")
        val currentAbsPos = if (positionState.value.bookTotalDurationMs > 0)
            positionState.value.bookPositionMs
        else
            positionState.value.currentPositionMs
        viewModelScope.launch {
            // Load this book FIRST if it isn't the one playing, and await it: bookSeekTo resolves
            // against whichever book the controller currently holds, so seeking before the load
            // completes either no-ops or lands in the previously-playing book. History is normally
            // opened before pressing play, so that is the common path, not the edge case.
            if (playbackState.value.bookId != bookId) startPlayback()
            pushPosition(currentAbsPos)
            recordSkip(currentAbsPos, endBookPositionMs)
            playerController.bookSeekTo(endBookPositionMs)
            if (!playbackState.value.isPlaying) playerController.togglePlayPause()
        }
    }

    /**
     * Restore playback to the position it was at right before a detected involuntary jump
     * (non-destructive — only runs when the user taps the restore pill). Records the correction
     * as a confirmed `"jump"` skip, same as any other deliberate navigation.
     */
    fun restoreFromJump(preJumpBookPosMs: Long) {
        val targetBookId = playbackState.value.bookId.takeIf { it != -1L } ?: bookId
        val currentAbsPos = if (positionState.value.bookTotalDurationMs > 0)
            positionState.value.bookPositionMs
        else
            positionState.value.currentPositionMs
        recordSkip(currentAbsPos, preJumpBookPosMs)
        playerController.bookSeekTo(preJumpBookPosMs)
        jumpRestoreStore.clear(targetBookId)
    }

    /** Dismiss a detected jump's restore offer without moving playback. */
    fun dismissJumpRestore() {
        val targetBookId = playbackState.value.bookId.takeIf { it != -1L } ?: bookId
        jumpRestoreStore.clear(targetBookId)
    }

    /**
     * Record a *confirmed* navigation jump (chapter select / bookmark jump / large scrubber
     * drag) into listening history. Fixed-amount skip-button taps are intentionally excluded.
     */
    private fun recordSkip(fromMs: Long, toMs: Long) {
        val targetBookId = playbackState.value.bookId.takeIf { it != -1L } ?: bookId
        if (targetBookId == -1L) return
        // In a series, chapters.value.rows spans every member book, each with absStartMs relative
        // to its OWN book — without this filter (unlike setSleepTimerEndOfCurrentChapter, which
        // always applied it), an unfiltered lastOrNull{} here could match another book's row and
        // record the wrong chapter index/name for this skip. Note: NOT viewModel's own
        // (book-scoped) chapterTimeline — targetBookId is whichever book the skip actually
        // happened in, which in a series can differ from the screen's own bookId.
        val items = chapters.value.rows.filterIsInstance<ChapterRow.Item>()
            .filter { it.bookId == -1L || it.bookId == targetBookId }
        val active = items.lastOrNull { it.absStartMs <= toMs }
        val idx = active?.let { items.indexOf(it) } ?: -1
        AppLog.i(LogCat.PLAYBACK, "skip recorded book=$targetBookId ${fromMs}ms→${toMs}ms ch=$idx")
        viewModelScope.launch {
            repository.insertSkipEventPruned(
                SkipEvent(
                    bookId = targetBookId,
                    fromPositionMs = fromMs,
                    toPositionMs = toMs,
                    chapterIndex = idx,
                    chapterName = active?.title ?: "",
                    source = "jump"
                ),
                keep = JUMP_HISTORY_KEEP
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
        val currentAbsPos = if (positionState.value.bookTotalDurationMs > 0)
            positionState.value.bookPositionMs
        else
            positionState.value.currentPositionMs
        pushPosition(currentAbsPos)
        recordSkip(currentAbsPos, absMs)
        playerController.bookSeekTo(absMs)
    }

    /**
     * A manual scrubber seek finished. Every real move arms the Return/Confirm pills so the user
     * can always get back to where they were — only the fixed-amount skip buttons are exempt
     * (they never call this; see [PlayerController.skipForward]/[skipBack], which log their own
     * `"skip_button"` history entries instead). Listening history's Skips list still only records
     * the long drags, so it keeps meaning "real navigation" rather than filling with small scrubs.
     */
    fun onScrubSeek(beforeMs: Long, afterMs: Long) {
        // The sliders stay draggable while the service holds a different book (or nothing), where
        // the seek itself is a no-op. Arming the pills there would strand a "Return …" chip for a
        // jump that never happened, and with nothing playing the 10-minute auto-commit can never
        // fire to clear it — leaving Confirm as the only way out.
        if (!serviceHasThisBook()) return
        val delta = kotlin.math.abs(afterMs - beforeMs)
        if (delta < SCRUB_RETURN_MIN_MS) return
        pushPosition(beforeMs)
        if (delta > SCRUB_HISTORY_MIN_MS) recordSkip(beforeMs, afterMs)
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { repository.deleteBookmark(id) }
    }

    // ── Playback actions ─────────────────────────────────────────────────────

    fun play() {
        viewModelScope.launch { startPlayback() }
    }

    /** The body of [play], as a suspend function so callers that must seek *after* the book is
     *  actually loaded (see [resumeFromHistory]) can await it instead of racing the coroutine. */
    private suspend fun startPlayback() {
            val bwp = bookWithProgress.value ?: return
            val files = bwp.audioFiles.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
            if (files.isEmpty()) return
            val progress = bwp.progress
            // If reading is the freshest activity (lastMode == TEXT) and an epub is connected,
            // resume audio from the equivalent converted position instead of the stale audio spot
            // — this is the audio-side half of the two-way listen↔read resume link (the reader
            // already does the reverse: it re-derives its text locator from the audio position
            // when lastMode == AUDIO).
            val bridgedMs = com.betteraudio.sync.TextToAudioResume.resolve(bwp.book, progress, files, repository)
            // A book inherits, in order: its own override → its series default → the global
            // default preset → the scalar fallback. The default preset makes global speed/boost/EQ
            // apply to every book that hasn't been individually tuned.
            val series = bwp.book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
            val gPreset = repository.getDefaultAudioPreset()
            val audio = com.betteraudio.playback.AudioCascade.resolve(bwp.book, progress, series, gPreset, settings.currentDefaultSpeed)
            if (bridgedMs != null) {
                AppLog.i(LogCat.PLAYBACK, "play() book=${bwp.book.id} bridged from reading position -> ${bridgedMs}ms")
                playerController.playBook(bwp.book, files, 0, 0L, audio.speed)
                playerController.bookSeekTo(bridgedMs)
            } else {
                // resolveStart forces file 0 / position 0 for a finished book, never file 0 of the
                // last file played — see its KDoc for the incident that made this matter.
                val rewind = com.betteraudio.playback.AudioCascade.autoRewindMs(settings, progress?.lastPausedAt ?: 0L)
                val (startIndex, startPos) = com.betteraudio.playback.AudioCascade.resolveStart(
                    files, progress, rewind, bwp.book.id, jumpRestoreStore
                )
                AppLog.i(LogCat.PLAYBACK, "play() book=${bwp.book.id}" +
                    " dbFile=${progress?.currentFileId} dbPos=${progress?.positionMs}ms isCompleted=${progress?.isCompleted}" +
                    " → rewind=${rewind}ms startIdx=$startIndex startPos=${startPos}ms")
                playerController.playBook(bwp.book, files, startIndex, startPos, audio.speed)
            }
            // Restore per-book (or inherited series/global) boost and EQ so they don't bleed between books
            playerController.setVolumeBoost(audio.boostDb)
            _eqBandsMillibels.value = audio.eqBandsJson?.let { json ->
                try { val arr = JSONArray(json); IntArray(arr.length()) { i -> arr.getInt(i) } }
                catch (_: Exception) { null }
            }
            playerController.setEqBands(audio.eqBandsJson)
            playerController.setSkipSilence(audio.skipSilence)
            // Mark as just-played now so last-played sorting moves it to the top immediately.
            repository.touchLastPlayed(bwp.book.id)
            settings.setLastPlayedBookId(bwp.book.id)
            settings.setThemeBookId(bwp.book.id)
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

            val bookPosMs = positionState.value.bookPositionMs
            val prevProgress = repository.getProgressForBookOnce(bookId)
            val anchors = repository.getSyncAnchorsOnce(bookId)
                .map { com.betteraudio.sync.AnchorPoint(it.audioMs, it.spineIndex, it.charOffset) }

            // Parse + resolve inside a single parser session (transient — the reader has its own).
            val resolved = withContext(Dispatchers.IO) {
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
                                    val loc = com.betteraudio.sync.TextLocator(spineIdx, paras.fractionForCharOffset(charOffset))
                                    return@use loc to info.spine.getOrNull(spineIdx)?.title
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
                    val finalLocator = if (paras == null || paras.totalChars == 0) coarse
                    else {
                        val (spineIdx, charOffset) = com.betteraudio.sync.PositionBridge.audioToChar(
                            bookPosMs, spans, map, info.spine.size, paras.totalChars, anchors
                        )
                        com.betteraudio.sync.TextLocator(spineIdx, paras.fractionForCharOffset(charOffset))
                    }
                    finalLocator to info.spine.getOrNull(finalLocator.spineIndex)?.title
                }
            } ?: return@launch
            val (locator, spineTitle) = resolved

            val spineCount = book.ebookSpineCount.coerceAtLeast(locator.spineIndex + 1)
            val overall = (locator.spineIndex + locator.fraction) / spineCount
            repository.updateTextPosition(bookId, locator.spineIndex, locator.fraction, overall)
            repository.insertSkipEvent(
                com.betteraudio.data.db.entities.SkipEvent(
                    bookId = bookId, kind = "TEXT",
                    fromSpineIndex = prevProgress?.textSpineIndex, fromFraction = prevProgress?.textFraction,
                    toSpineIndex = locator.spineIndex, toFraction = locator.fraction,
                    toSpineTitle = spineTitle
                )
            )
            AppLog.i(LogCat.PLAYBACK, "readFromHere book=$bookId pos=${bookPosMs}ms -> spine=${locator.spineIndex} frac=${locator.fraction}")
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
        viewModelScope.launch {
            repository.updateBookMetadata(bookId, titleOverride, authorOverride)
            // Unlike Home/Series, this book can be LIVE in ExoPlayer right now — the running
            // MediaSession queue still references its files by their OLD paths, so moving the
            // folder out from under it (copy → repoint DB → delete original) would break playback
            // at the next file transition/seek. Skip the on-disk move while this book is the one
            // actually loaded in the service; it still restructures correctly from Home/Series,
            // or here too once a different book is loaded.
            if (playbackState.value.bookId != bookId) {
                libraryRestructurer.restructureBooks(listOf(bookId))
            }
        }
    }

    fun updateSeriesInfo(seriesName: String?, seriesOrder: Float?) {
        if (bookId == -1L) return
        viewModelScope.launch {
            seriesRepository.setBookSeriesByName(bookId, seriesName, seriesOrder)
            // Series name is part of the folder scheme, so this moves the book on disk too — with
            // updateBookMetadata's guard, for the same reason: not while this book is the one live
            // in ExoPlayer, whose queue still references the old paths.
            if (playbackState.value.bookId != bookId) {
                libraryRestructurer.restructureBooks(listOf(bookId))
            }
        }
    }

    fun updateCoverArt(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val book = repository.getBookOnce(bookId)
                    ?: throw IllegalStateException("book $bookId not found")
                val dest = context.contentResolver.openInputStream(uri)?.use { input ->
                    bookDataStore.writeCoverStream(book.folderPath, "user", "jpg", input)
                } ?: throw IllegalStateException("couldn't open picked image")
                repository.updateCoverArt(bookId, dest)
                widgetUpdater.refreshCoverIfCurrent(bookId, dest)
            } catch (e: Exception) {
                AppLog.e(LogCat.PLAYBACK, "updateCoverArt failed for book $bookId", e)
                android.widget.Toast.makeText(
                    context, "Couldn't set that cover image", android.widget.Toast.LENGTH_LONG
                ).show()
            }
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
            // Player sheet dismissed is a flush point — same cadence as pause/stop/book-close.
            diskMirror.flushBook(bookId)
        }
    }
}
