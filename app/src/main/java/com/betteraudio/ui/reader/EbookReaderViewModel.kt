package com.betteraudio.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.ebook.SpineItem
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.AudioCascade
import com.betteraudio.playback.PlayerController
import com.betteraudio.sync.AudioChapterSpan
import com.betteraudio.sync.AudioSpanBuilder
import com.betteraudio.sync.ChapterMap
import com.betteraudio.sync.ChapterMatcher
import com.betteraudio.sync.PositionBridge
import com.betteraudio.sync.TextLocator
import com.betteraudio.util.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

enum class ReaderError(val message: String) {
    MISSING_FILE("This ebook file could not be found."),
    DRM("DRM-protected EPUB is not supported."),
    PARSE_FAILED("This EPUB could not be opened.")
}

data class ReaderUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val spine: List<SpineItem> = emptyList(),
    val currentSpineIndex: Int = 0,
    // Scroll fraction (0..1) the WebView should restore to when it loads currentSpineIndex.
    // Consumed once per spine change — bumping [restoreToken] forces a re-apply (e.g. after a
    // font-size change reloads the same chapter).
    val restoreFraction: Float = 0f,
    val restoreToken: Int = 0,
    val chromeVisible: Boolean = true,
    val fontSizePct: Int = 100,
    val hasAudio: Boolean = false,
    val chapterMapApproximate: Boolean = false,
    val error: ReaderError? = null
) {
    val currentSpineTitle: String?
        get() = spine.getOrNull(currentSpineIndex)?.title
}

@HiltViewModel
class EbookReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository,
    private val settings: SettingsStore,
    private val playerController: PlayerController
) : ViewModel() {

    private val bookId: Long = savedStateHandle["bookId"] ?: -1L

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var parser: EpubParser? = null
    private var cachedSpans: List<AudioChapterSpan> = emptyList()
    private var cachedMap: ChapterMap = ChapterMap(emptyList())
    private var saveJob: Job? = null

    init {
        if (bookId == -1L) {
            _state.value = ReaderUiState(loading = false, error = ReaderError.MISSING_FILE)
        } else {
            viewModelScope.launch { load() }
        }
    }

    private suspend fun load() {
        val book = repository.getBookById(bookId).first()
        val epubPath = book?.ebookPath
        if (book == null || epubPath.isNullOrBlank()) {
            _state.value = ReaderUiState(loading = false, error = ReaderError.MISSING_FILE)
            return
        }
        val file = File(epubPath)
        if (!file.exists()) {
            _state.value = ReaderUiState(loading = false, error = ReaderError.MISSING_FILE)
            return
        }

        val info = withContext(Dispatchers.IO) {
            runCatching {
                val p = EpubParser(file)
                parser = p
                p.parse()
            }.getOrNull()
        }
        if (info == null) {
            _state.value = ReaderUiState(loading = false, error = ReaderError.PARSE_FAILED)
            return
        }
        if (info.encrypted) {
            _state.value = ReaderUiState(loading = false, error = ReaderError.DRM)
            return
        }
        if (book.ebookSpineCount != info.spine.size) {
            runCatching { repository.updateEbookSpineCount(book.id, info.spine.size) }
        }

        val hasAudio = book.fileCount > 0
        val progress = repository.getProgressForBookOnce(bookId)
        var initialSpine = 0
        var initialFraction = 0f
        var approximate = false

        if (hasAudio) {
            val files = repository.getAudioFilesOnce(bookId)
            val chapters = repository.getChaptersForBookOnce(bookId)
            cachedSpans = AudioSpanBuilder.build(files, chapters)
            if (cachedSpans.isNotEmpty()) {
                cachedMap = ensureChapterMap(book, cachedSpans, info.spine)
                approximate = isApproximate(cachedMap)
                if (progress?.lastMode == "AUDIO") {
                    // Continue where you left off listening: derive the text locator from the
                    // live audio position rather than a (possibly stale) stored text position.
                    val bookPosMs = bookPositionMsFromProgress(progress, files)
                    val locator = PositionBridge.audioToText(bookPosMs, cachedSpans, cachedMap, info.spine.size)
                    initialSpine = locator.spineIndex
                    initialFraction = locator.fraction
                }
            }
        }
        if (!hasAudio || progress?.lastMode != "AUDIO") {
            progress?.textSpineIndex?.let { saved ->
                initialSpine = saved.coerceIn(0, (info.spine.size - 1).coerceAtLeast(0))
                initialFraction = progress.textFraction ?: 0f
            }
        }

        _state.value = ReaderUiState(
            loading = false,
            book = book,
            spine = info.spine,
            currentSpineIndex = initialSpine,
            restoreFraction = initialFraction,
            hasAudio = hasAudio,
            chapterMapApproximate = approximate,
            fontSizePct = settings.readerFontSize.first()
        )
    }

    private suspend fun ensureChapterMap(book: Book, spans: List<AudioChapterSpan>, spine: List<SpineItem>): ChapterMap {
        ChapterMap.fromJson(book.chapterMapJson)?.let { return it }
        val map = ChapterMatcher.autoMatch(spans, spine)
        runCatching { repository.setChapterMap(book.id, map.toJson()) }
        return map
    }

    private fun isApproximate(map: ChapterMap): Boolean {
        if (map.audioToSpine.isEmpty()) return true
        val matched = map.audioToSpine.count { it >= 0 }
        return matched.toFloat() / map.audioToSpine.size < 0.30f
    }

    /** Book-timeline position implied by a saved (per-file) [PlaybackProgress], using the same
     *  cumulative-file-offset math as `PlayerController`. */
    private fun bookPositionMsFromProgress(progress: PlaybackProgress, files: List<AudioFile>): Long {
        val fileId = progress.currentFileId ?: return 0L
        val sorted = files.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        var cumulative = 0L
        for (f in sorted) {
            if (f.id == fileId) return cumulative + progress.positionMs
            cumulative += f.durationMs
        }
        return progress.positionMs
    }

    // ── Chrome / navigation ────────────────────────────────────────────────────

    fun toggleChrome() {
        _state.value = _state.value.copy(chromeVisible = !_state.value.chromeVisible)
    }

    fun nextChapter() {
        val s = _state.value
        val next = (s.currentSpineIndex + 1).coerceAtMost(s.spine.size - 1)
        if (next == s.currentSpineIndex) return
        flushNow(s.currentSpineIndex, 1f)
        jumpToSpine(next, 0f)
    }

    fun prevChapter() {
        val s = _state.value
        val prev = (s.currentSpineIndex - 1).coerceAtLeast(0)
        if (prev == s.currentSpineIndex) return
        flushNow(s.currentSpineIndex, 0f)
        jumpToSpine(prev, 0f)
    }

    fun openSpine(index: Int) {
        val s = _state.value
        val clamped = index.coerceIn(0, s.spine.size - 1)
        flushNow(s.currentSpineIndex, s.restoreFraction)
        jumpToSpine(clamped, 0f)
    }

    private fun jumpToSpine(index: Int, fraction: Float) {
        _state.value = _state.value.copy(
            currentSpineIndex = index, restoreFraction = fraction,
            restoreToken = _state.value.restoreToken + 1
        )
    }

    fun setFontSize(pct: Int) {
        val clamped = pct.coerceIn(70, 200)
        viewModelScope.launch { settings.setReaderFontSize(clamped) }
        val s = _state.value
        _state.value = s.copy(fontSizePct = clamped, restoreToken = s.restoreToken + 1)
    }

    // ── Position persistence ────────────────────────────────────────────────────

    /** Called by the WebView as the user scrolls; debounced ~1s. */
    fun onScrollFraction(spineIndex: Int, fraction: Float) {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000)
            persist(spineIndex, fraction)
        }
    }

    /** Immediate save — used on chapter change and screen dispose, where a debounced write could
     *  be lost. NonCancellable: may run right as viewModelScope is about to be torn down. */
    fun flushNow(spineIndex: Int, fraction: Float) {
        saveJob?.cancel()
        viewModelScope.launch(NonCancellable) { persist(spineIndex, fraction) }
    }

    private suspend fun persist(spineIndex: Int, fraction: Float) {
        val spineCount = _state.value.spine.size.coerceAtLeast(1)
        val overall = ((spineIndex + fraction.coerceIn(0f, 1f)) / spineCount).coerceIn(0f, 1f)
        repository.updateTextPosition(bookId, spineIndex, fraction.coerceIn(0f, 1f), overall)
        val book = _state.value.book ?: return
        if (book.status == BookStatus.NOT_STARTED && overall > 0f) {
            repository.updateBookStatus(bookId, BookStatus.IN_PROGRESS)
        }
        if (spineIndex == spineCount - 1 && fraction >= 0.999f && book.status != BookStatus.FINISHED) {
            repository.updateBookStatus(bookId, BookStatus.FINISHED)
        }
    }

    // ── WebView content access (synchronous — safe off the main thread) ────────

    fun readEntry(href: String): ByteArray? = parser?.readEntry(href)
    fun mimeTypeFor(href: String): String = parser?.mimeTypeFor(href) ?: "application/octet-stream"

    // ── Listen from here (text -> audio) ───────────────────────────────────────

    /** Starts audio playback at the position corresponding to the current reading spot, exactly
     *  like `HomeViewModel.playResumeBook`'s cascade but seeking to the converted position
     *  afterward. Returns the book id on success so the caller can expand the player sheet. */
    suspend fun listenFromHere(): Long? {
        val s = _state.value
        val book = s.book ?: return null
        if (book.fileCount == 0) return null // ebook-only guard (button is hidden in this case too)

        val files = repository.getAudioFilesOnce(book.id)
        if (files.isEmpty()) return null
        if (cachedSpans.isEmpty()) {
            val chapters = repository.getChaptersForBookOnce(book.id)
            cachedSpans = AudioSpanBuilder.build(files, chapters)
            cachedMap = ensureChapterMap(book, cachedSpans, s.spine)
        }

        val locator = TextLocator(s.currentSpineIndex, s.restoreFraction)
        val targetMs = PositionBridge.textToAudio(locator, cachedSpans, cachedMap)

        val progress = repository.getProgressForBookOnce(book.id)
        val series = book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
        val defaultPreset = repository.getDefaultAudioPreset()
        val speed = AudioCascade.speed(progress?.playbackSpeed, series?.playbackSpeed, defaultPreset?.speedMult ?: settings.currentDefaultSpeed)

        playerController.playBook(book, files, 0, 0L, speed)
        playerController.setVolumeBoost(AudioCascade.boost(progress?.boostDb, series?.boostDb, defaultPreset?.boostDb ?: 0))
        playerController.setEqBands(AudioCascade.eq(progress?.eqBandsJson, series?.eqBandsJson, defaultPreset?.eqBandsJson))
        playerController.setSkipSilence(AudioCascade.skipSilence(book.skipSilenceEnabled, series?.skipSilenceEnabled))
        playerController.bookSeekTo(targetMs)

        repository.touchLastPlayed(book.id)
        settings.setLastPlayedBookId(book.id)
        repository.setLastModeAudio(book.id)
        AppLog.i("Reader", "listenFromHere book=${book.id} spine=${s.currentSpineIndex} frac=${s.restoreFraction} -> ${targetMs}ms")
        return book.id
    }

    // ── Manual chapter alignment ────────────────────────────────────────────────

    /** Audio chapter spans for the align sheet (empty until an audio-linked book has loaded). */
    fun audioSpansForAlign(): List<AudioChapterSpan> = cachedSpans

    /** The chapter map currently in effect (for pre-filling the align sheet's dropdowns). */
    fun currentChapterMap(): ChapterMap = cachedMap

    fun autoMatchChapters() {
        val s = _state.value
        val book = s.book ?: return
        if (cachedSpans.isEmpty()) return
        val map = ChapterMatcher.autoMatch(cachedSpans, s.spine)
        cachedMap = map
        _state.value = s.copy(chapterMapApproximate = isApproximate(map))
        viewModelScope.launch { repository.setChapterMap(book.id, map.toJson()) }
    }

    fun saveManualChapterMap(map: ChapterMap) {
        val book = _state.value.book ?: return
        cachedMap = map
        _state.value = _state.value.copy(chapterMapApproximate = isApproximate(map))
        viewModelScope.launch { repository.setChapterMap(book.id, map.toJson()) }
    }

    override fun onCleared() {
        super.onCleared()
        parser?.close()
    }
}
