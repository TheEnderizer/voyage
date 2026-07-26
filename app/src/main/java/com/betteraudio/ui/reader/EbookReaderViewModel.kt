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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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
    // Tier-2 sync state: number of verified anchors, live alignment progress, and the model status.
    val anchorCount: Int = 0,
    val alignProgress: com.betteraudio.data.transcribe.AlignProgress? = null,
    val modelState: com.betteraudio.data.transcribe.ModelState = com.betteraudio.data.transcribe.ModelState.NotDownloaded,
    // A bundled "mapping.json" (see MappingFileIO) sitting in the book's folder — lets the user
    // manually (re)import it instead of waiting for the next scan to pick it up.
    val mappingFileAvailable: Boolean = false,
    val mappingImportMessage: String? = null,
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
    private val playerController: PlayerController,
    private val paragraphCache: com.betteraudio.data.ebook.ParagraphCache,
    private val syncAligner: com.betteraudio.data.transcribe.SyncAligner,
    private val modelManager: com.betteraudio.data.transcribe.VoskModelManager
) : ViewModel() {

    private val bookId: Long = savedStateHandle["bookId"] ?: -1L

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private var parser: EpubParser? = null
    private var cachedSpans: List<AudioChapterSpan> = emptyList()
    private var cachedMap: ChapterMap = ChapterMap(emptyList())
    // Verified alignment anchors (Tier 2). Empty until "Improve sync" has run — Phase-5 populates.
    private var cachedAnchors: List<com.betteraudio.sync.AnchorPoint> = emptyList()
    private var spineList: List<SpineItem> = emptyList()
    private var saveJob: Job? = null
    // Live top-of-viewport scroll fraction for the current spine — updated immediately on every
    // scroll event (unlike `state.restoreFraction`, which is a one-shot "restore to on load" value
    // that's only set when a chapter loads/jumps and is never touched by scrolling). "Listen from
    // here" must read this, not restoreFraction, or it always seeks to wherever the chapter was
    // last opened at rather than where the reader is actually scrolled to.
    private var liveScrollFraction: Float = 0f

    /** Parsed paragraphs for a spine item (cached across the session); null if unreadable. */
    private fun paragraphsFor(spineIndex: Int): com.betteraudio.data.ebook.SpineParagraphs? {
        val href = spineList.getOrNull(spineIndex)?.href ?: return null
        val p = parser ?: return null
        return paragraphCache.get(bookId, spineIndex) { p.readEntry(href) }
    }

    init {
        if (bookId == -1L) {
            _state.value = ReaderUiState(loading = false, error = ReaderError.MISSING_FILE)
        } else {
            viewModelScope.launch { load() }
            // Live sync state → UI. The aligner is a singleton, so a run started here keeps going
            // (and stays observable) even after the reader is closed and re-opened.
            repository.syncAnchorCount(bookId)
                .onEach { count -> _state.update { it.copy(anchorCount = count) } }
                .launchIn(viewModelScope)
            syncAligner.progress
                .onEach { m -> _state.update { it.copy(alignProgress = m[bookId]) } }
                .launchIn(viewModelScope)
            // Re-probe the filesystem on open so a model already on disk (or restored/pushed
            // outside the app) is detected without a process restart.
            modelManager.refreshState()
            modelManager.state
                .onEach { s -> _state.update { it.copy(modelState = s) } }
                .launchIn(viewModelScope)
        }
    }

    private suspend fun load() {
        val book = repository.getBookOnce(bookId)
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
        spineList = info.spine

        val hasAudio = book.fileCount > 0
        val progress = repository.getProgressForBookOnce(bookId)
        var initialSpine = 0
        var initialFraction = 0f
        var approximate = false

        if (hasAudio) {
            val files = repository.getAudioFilesOnce(bookId)
            val chapters = repository.getChaptersForBookOnce(bookId)
            cachedSpans = AudioSpanBuilder.build(files, chapters)
            cachedAnchors = repository.getSyncAnchorsOnce(bookId)
                .map { com.betteraudio.sync.AnchorPoint(it.audioMs, it.spineIndex, it.charOffset) }
            if (cachedSpans.isNotEmpty()) {
                cachedMap = ensureChapterMap(book, cachedSpans, info.spine)
                approximate = isApproximate(cachedMap)
                if (progress?.lastMode == "AUDIO") {
                    // Continue where you left off listening: derive the text locator from the
                    // live audio position (paragraph-resolution via char offsets + anchors).
                    val bookPosMs = bookPositionMsFromProgress(progress, files)
                    val locator = audioPositionToLocator(bookPosMs, info.spine.size)
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

        val mappingAvailable = hasAudio && File(book.folderPath).let { dir ->
            dir.isDirectory && com.betteraudio.data.sync.MappingFileIO.mappingFile(dir).isFile
        }

        liveScrollFraction = initialFraction

        val fontSizePct = settings.readerFontSize.first()
        // copy(), not a fresh ReaderUiState: the init mirrors already wrote modelState /
        // anchorCount / alignProgress into _state, and their StateFlows won't re-emit an
        // unchanged value — a wholesale reset here would clobber modelState back to
        // NotDownloaded forever (the "asks to download the model again" bug).
        _state.update {
            it.copy(
                loading = false,
                error = null,
                book = book,
                spine = info.spine,
                currentSpineIndex = initialSpine,
                restoreFraction = initialFraction,
                hasAudio = hasAudio,
                chapterMapApproximate = approximate,
                mappingFileAvailable = mappingAvailable,
                fontSizePct = fontSizePct
            )
        }
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
        recordTextSkip(s.currentSpineIndex, liveScrollFraction, next, 0f)
        jumpToSpine(next, 0f)
    }

    fun prevChapter() {
        val s = _state.value
        val prev = (s.currentSpineIndex - 1).coerceAtLeast(0)
        if (prev == s.currentSpineIndex) return
        flushNow(s.currentSpineIndex, 0f)
        recordTextSkip(s.currentSpineIndex, liveScrollFraction, prev, 0f)
        jumpToSpine(prev, 0f)
    }

    fun openSpine(index: Int) {
        val s = _state.value
        val clamped = index.coerceIn(0, s.spine.size - 1)
        flushNow(s.currentSpineIndex, liveScrollFraction)
        if (clamped != s.currentSpineIndex) recordTextSkip(s.currentSpineIndex, liveScrollFraction, clamped, 0f)
        jumpToSpine(clamped, 0f)
    }

    private fun jumpToSpine(index: Int, fraction: Float) {
        liveScrollFraction = fraction
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

    /** Called by the WebView as the user scrolls. The DB write is debounced ~1s, but
     *  [liveScrollFraction] updates immediately so "Listen from here" always reads the reader's
     *  true current position, not a stale/unsaved one. */
    fun onScrollFraction(spineIndex: Int, fraction: Float) {
        liveScrollFraction = fraction
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

    /** Immediate save of the current spine + the live scroll position — this is what "closing the
     *  book" should call. Using `state.restoreFraction` there instead would save the wrong spot:
     *  it's a one-shot "restore to on load" value that scrolling never updates, so closing shortly
     *  after scrolling (before the ~1s debounced auto-save in [onScrollFraction] fires) would
     *  silently overwrite a good pending save with a stale one. */
    fun flushCurrent() {
        flushNow(_state.value.currentSpineIndex, liveScrollFraction)
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

        val targetMs = locatorToAudio(s.currentSpineIndex, liveScrollFraction)

        val progress = repository.getProgressForBookOnce(book.id)
        val series = book.seriesId?.let { seriesRepository.getSeriesOnce(it) }
        val defaultPreset = repository.getDefaultAudioPreset()
        val speed = AudioCascade.speed(progress?.playbackSpeed, series?.playbackSpeed, defaultPreset?.speedMult ?: settings.currentDefaultSpeed)

        playerController.playBook(book, files, 0, 0L, speed)
        playerController.setVolumeBoost(AudioCascade.boost(progress?.boostDb, series?.boostDb, defaultPreset?.boostDb ?: 0))
        playerController.setEqBands(AudioCascade.eq(progress?.eqBandsJson, series?.eqBandsJson, defaultPreset?.eqBandsJson))
        playerController.setSkipSilence(AudioCascade.skipSilence(book.skipSilenceEnabled, series?.skipSilenceEnabled))
        playerController.bookSeekTo(targetMs)

        val fromMs = progress?.let { bookPositionMsFromProgress(it, files) } ?: 0L
        recordAudioSkip(book.id, fromMs, targetMs)

        repository.touchLastPlayed(book.id)
        settings.setLastPlayedBookId(book.id)
        settings.setThemeBookId(book.id)
        repository.setLastModeAudio(book.id)
        AppLog.i("Reader", "listenFromHere book=${book.id} spine=${s.currentSpineIndex} frac=$liveScrollFraction -> ${targetMs}ms")
        return book.id
    }

    /** Confirmed audio-side jump (kind = "AUDIO") — used for "Listen from here". Mirrors
     *  `PlayerViewModel.recordSkip`, kept local since the reader has its own [cachedSpans] rather
     *  than that ViewModel's `ChapterRow` list. */
    private fun recordAudioSkip(bookId: Long, fromMs: Long, toMs: Long) {
        val active = cachedSpans.lastOrNull { it.absStartMs <= toMs }
        val idx = active?.let { cachedSpans.indexOf(it) } ?: -1
        viewModelScope.launch {
            repository.insertSkipEvent(
                com.betteraudio.data.db.entities.SkipEvent(
                    bookId = bookId, kind = "AUDIO",
                    fromPositionMs = fromMs, toPositionMs = toMs,
                    chapterIndex = idx, chapterName = active?.title ?: ""
                )
            )
        }
    }

    /** Confirmed text-side jump (kind = "TEXT") — reader chapter/TOC navigation. */
    private fun recordTextSkip(fromSpine: Int, fromFraction: Float, toSpine: Int, toFraction: Float) {
        val bookId = _state.value.book?.id ?: return
        viewModelScope.launch {
            repository.insertSkipEvent(
                com.betteraudio.data.db.entities.SkipEvent(
                    bookId = bookId, kind = "TEXT",
                    fromSpineIndex = fromSpine, fromFraction = fromFraction,
                    toSpineIndex = toSpine, toFraction = toFraction,
                    toSpineTitle = spineList.getOrNull(toSpine)?.title
                )
            )
        }
    }

    /** Audio book-position → text locator at paragraph resolution (char offset + anchors), with a
     *  fallback to the coarse chapter-fraction mapping when the spine has no extractable text. */
    private fun audioPositionToLocator(bookPosMs: Long, spineCount: Int): TextLocator {
        // Prefer the anchors alone: they were each verified against the WHOLE book's text, so
        // they're immune to a ChapterMap that's wrong because the audio is split into narration
        // "Parts" rather than actual chapters (see PositionBridge.audioToCharAnchored).
        if (cachedAnchors.size >= 2) {
            PositionBridge.audioToCharAnchored(bookPosMs, cachedAnchors) { idx -> paragraphsFor(idx)?.totalChars ?: 0 }
                ?.let { (spineIdx, charOffset) ->
                    val paras = paragraphsFor(spineIdx)
                    if (paras != null && paras.totalChars > 0) {
                        return TextLocator(spineIdx, paras.fractionForCharOffset(charOffset))
                    }
                }
        }
        val coarse = PositionBridge.audioToText(bookPosMs, cachedSpans, cachedMap, spineCount)
        val paras = paragraphsFor(coarse.spineIndex)
        if (paras == null || paras.totalChars == 0) return coarse
        val (spineIdx, charOffset) = PositionBridge.audioToChar(
            bookPosMs, cachedSpans, cachedMap, spineCount, paras.totalChars, cachedAnchors
        )
        return TextLocator(spineIdx, paras.fractionForCharOffset(charOffset))
    }

    /** Text scroll position → audio book-position (char offset + anchors), with the coarse
     *  chapter-fraction fallback. */
    private fun locatorToAudio(spineIndex: Int, fraction: Float): Long {
        val paras = paragraphsFor(spineIndex)
        if (paras == null || paras.totalChars == 0) {
            return PositionBridge.textToAudio(TextLocator(spineIndex, fraction), cachedSpans, cachedMap)
        }
        val charOffset = paras.charOffsetForFraction(fraction)
        if (cachedAnchors.size >= 2) {
            PositionBridge.charToAudioAnchored(spineIndex, charOffset, cachedAnchors) { idx -> paragraphsFor(idx)?.totalChars ?: 0 }
                ?.let { return it }
        }
        return PositionBridge.charToAudio(spineIndex, charOffset, paras.totalChars, cachedSpans, cachedMap, cachedAnchors)
    }

    // ── Tier-2 sync (on-device forced alignment) ────────────────────────────────

    /** "Improve sync": align now if the model is ready, otherwise download it first then align. */
    fun improveSync() {
        val s = _state.value
        if (!s.hasAudio || s.book == null) return
        when (s.modelState) {
            is com.betteraudio.data.transcribe.ModelState.Ready -> syncAligner.start(bookId)
            is com.betteraudio.data.transcribe.ModelState.Downloading,
            com.betteraudio.data.transcribe.ModelState.Unzipping -> { /* already in progress */ }
            else -> viewModelScope.launch {
                modelManager.download()
                if (modelManager.modelDirOrNull() != null) syncAligner.start(bookId)
            }
        }
    }

    fun cancelSync() = syncAligner.cancel(bookId)

    /** Manually (re)import "mapping.json" from the book's folder (see MappingFileIO) — e.g. after
     *  the user drops in a mapping file obtained elsewhere, or to restore one after a rescan
     *  missed it. Unlike the automatic scan-time import, this always replaces any existing
     *  anchors, since the user explicitly asked for it. */
    fun importMappingFile() {
        val s = _state.value
        val book = s.book ?: return
        if (!s.hasAudio || book.ebookPath == null) return
        viewModelScope.launch {
            val folder = File(book.folderPath)
            val mapping = if (folder.isDirectory) com.betteraudio.data.sync.MappingFileIO.read(folder) else null
            if (mapping == null) {
                _state.update { it.copy(mappingImportMessage = "No mapping.json found in this book's folder") }
                return@launch
            }
            mapping.chapterMapJson?.let { repository.setChapterMap(bookId, it) }
            repository.deleteSyncAnchors(bookId)
            if (mapping.anchors.isNotEmpty()) {
                repository.insertSyncAnchors(mapping.anchors.map { it.copy(bookId = bookId) })
                mapping.chapterMapJson?.let { ChapterMap.fromJson(it) }?.let { cachedMap = it }
                cachedAnchors = mapping.anchors.map { com.betteraudio.sync.AnchorPoint(it.audioMs, it.spineIndex, it.charOffset) }
            }
            _state.update { it.copy(mappingImportMessage = "Imported ${mapping.anchors.size} anchors from mapping.json") }
        }
    }

    fun clearMappingImportMessage() {
        _state.update { it.copy(mappingImportMessage = null) }
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
