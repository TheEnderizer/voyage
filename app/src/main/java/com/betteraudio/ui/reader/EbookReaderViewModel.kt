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
import com.betteraudio.data.ebook.render.BlockMeasurer
import com.betteraudio.data.ebook.render.EpubDocumentParser
import com.betteraudio.data.ebook.render.Page
import com.betteraudio.data.ebook.render.Paginator
import com.betteraudio.data.ebook.render.RenderDocument
import com.betteraudio.data.ebook.render.RenderProjection
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
import com.betteraudio.util.log.LogCat
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
    // The native renderer's paginated output for currentSpineIndex (docs/reader-features-and-plan.md
    // Phase 1) — empty until [EbookReaderViewModel.preparePages] has run at least once for the
    // current spine item + viewport + font size (it needs a real TextMeasurer/Density, which only
    // exist in composition, so the screen drives it rather than `load()`).
    val pages: List<Page> = emptyList(),
    val currentPageIndex: Int = 0,
    val chromeVisible: Boolean = true,
    /** Every reading setting the renderer and chrome read (see [com.betteraudio.data.settings.
     *  ReaderPrefs]) — the book's own copy when [prefsArePerBook], else the global document. */
    val prefs: com.betteraudio.data.settings.ReaderPrefs = com.betteraudio.data.settings.ReaderPrefs(),
    /** True once this book has been detached from the global settings (inventory #160). */
    val prefsArePerBook: Boolean = false,
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
    // Live reading position for the current spine, as an EXTRACTOR-stream fraction (0..1) — the
    // same coordinate `PositionBridge`/the frozen sync path already speak, and the same role
    // `liveScrollFraction` played before the native renderer (updated on every page turn, unlike
    // `preparePages`'s one-shot "restore to on load" read, which only happens on a spine/font-size
    // change). "Listen from here" must read this, or it always seeks to wherever the chapter was
    // last opened rather than where the reader actually is.
    private var liveTextFraction: Float = 0f
    // Render-stream offset of the current page's first block — persisted as PlaybackProgress.
    // textCharOffset (Room v23) purely for the native renderer's own future use; the sync path
    // above never reads it, only [liveTextFraction].
    private var liveRenderOffset: Int = 0
    private var currentRenderDoc: RenderDocument? = null
    private var currentProjection: RenderProjection? = null

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
            // The whole sync surface ships frozen behind EBOOK_SYNC_UI (Phase 0) — don't subscribe
            // to the aligner or the on-device model manager (which would otherwise probe the
            // filesystem for a Vosk model on every reader open) while it's off.
            if (com.betteraudio.util.FeatureFlags.EBOOK_SYNC_UI) {
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

        val mappingAvailable = hasAudio && com.betteraudio.data.sync.MappingFileIO.exists(book.folderPath)

        liveTextFraction = initialFraction
        liveRenderOffset = 0

        val perBook = settings.readerPrefsForBook(bookId).first()
        val prefs = perBook ?: settings.readerPrefs.first()
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
                hasAudio = hasAudio,
                chapterMapApproximate = approximate,
                mappingFileAvailable = mappingAvailable,
                prefs = prefs,
                prefsArePerBook = perBook != null,
            )
        }
    }

    /**
     * (Re)paginates the current spine item against a real measurer/viewport — called from the
     * reader screen (which owns the `TextMeasurer`/`Density` this needs) whenever the spine, font
     * size, or viewport size changes. Builds the [RenderProjection] against the frozen
     * [com.betteraudio.data.ebook.ParagraphExtractor] output for this same spine item (C.3) so the
     * initial page can be resolved from [liveTextFraction] — the same extractor-stream coordinate
     * every stored position and the sync path already use — rather than a render-stream fraction
     * that would drift from what's on disk.
     */
    fun preparePages(measurer: BlockMeasurer, viewportWidthPx: Int, viewportHeightPx: Int) {
        val spineIndex = _state.value.currentSpineIndex
        val href = spineList.getOrNull(spineIndex)?.href ?: return
        val p = parser ?: return
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { p.readEntry(href) }
            if (bytes == null || _state.value.currentSpineIndex != spineIndex) return@launch
            val doc = withContext(Dispatchers.Default) { EpubDocumentParser.parse(bytes) }
            if (_state.value.currentSpineIndex != spineIndex) return@launch
            val extractor = paragraphsFor(spineIndex)
            val projection = extractor?.let { RenderProjection.buildFor(doc, it) }
            val pages = if (doc.blocks.isEmpty()) emptyList()
                else withContext(Dispatchers.Default) { Paginator.paginate(doc.blocks, measurer, viewportWidthPx, viewportHeightPx) }
            if (_state.value.currentSpineIndex != spineIndex) return@launch

            currentRenderDoc = doc
            currentProjection = projection

            val targetRenderOffset = if (extractor != null && extractor.totalChars > 0) {
                val extractorOffset = extractor.charOffsetForFraction(liveTextFraction)
                projection?.toRenderOffset(extractorOffset)
                    ?: (liveTextFraction.coerceIn(0f, 1f) * doc.text.length).toInt()
            } else (liveTextFraction.coerceIn(0f, 1f) * doc.text.length).toInt()

            val pageIndex = pages.indexOfFirst { page ->
                page.blocks.any { targetRenderOffset >= it.renderStart && targetRenderOffset < it.renderEnd }
            }.let { if (it < 0) 0 else it }

            _state.update { it.copy(pages = pages, currentPageIndex = pageIndex) }
        }
    }

    /** Called by the reader screen on every page turn. Persists (debounced) and updates
     *  [liveTextFraction]/[liveRenderOffset] so chapter navigation and "Listen from here" read the
     *  reader's true current position. */
    fun onPageChanged(pageIndex: Int) {
        val s = _state.value
        val clamped = pageIndex.coerceIn(0, (s.pages.size - 1).coerceAtLeast(0))
        if (clamped == s.currentPageIndex) return
        _state.value = s.copy(currentPageIndex = clamped)

        val page = s.pages.getOrNull(clamped) ?: return
        val renderOffset = page.blocks.firstOrNull()?.renderStart ?: 0
        liveRenderOffset = renderOffset
        val extractor = paragraphsFor(s.currentSpineIndex)
        liveTextFraction = if (extractor != null && extractor.totalChars > 0) {
            val docLen = currentRenderDoc?.text?.length?.coerceAtLeast(1) ?: 1
            val extractorOffset = currentProjection?.toExtractorOffset(renderOffset)
                ?: ((renderOffset.toFloat() / docLen) * extractor.totalChars).toInt()
            extractor.fractionForCharOffset(extractorOffset)
        } else 0f

        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(1_000)
            persist(s.currentSpineIndex, liveTextFraction, liveRenderOffset)
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
        recordTextSkip(s.currentSpineIndex, liveTextFraction, next, 0f)
        jumpToSpine(next, 0f)
    }

    fun prevChapter() {
        val s = _state.value
        val prev = (s.currentSpineIndex - 1).coerceAtLeast(0)
        if (prev == s.currentSpineIndex) return
        flushNow(s.currentSpineIndex, 0f)
        recordTextSkip(s.currentSpineIndex, liveTextFraction, prev, 1f)
        // Land on the previous chapter's LAST page, not its first — going backward across a
        // chapter boundary should feel like "the page before this one", the way turning back a
        // physical page never dumps you back at a chapter's start. 1f resolves (via preparePages'
        // fraction→render-offset path) to the end of the render stream, which the paginator's
        // last page always contains.
        jumpToSpine(prev, 1f)
    }

    fun openSpine(index: Int) {
        val s = _state.value
        val clamped = index.coerceIn(0, s.spine.size - 1)
        flushNow(s.currentSpineIndex, liveTextFraction, liveRenderOffset)
        if (clamped != s.currentSpineIndex) recordTextSkip(s.currentSpineIndex, liveTextFraction, clamped, 0f)
        jumpToSpine(clamped, 0f)
    }

    /** Scrubber drag end: [overall] is a whole-book fraction (0..1), treating every spine item as
     *  equal-length — the same coarse model `persist()`'s own `textOverallFraction` already uses,
     *  so the scrubber and the home-grid progress bar agree on what "40% through the book" means. */
    fun jumpToOverallFraction(overall: Float) {
        val s = _state.value
        val spineCount = s.spine.size.coerceAtLeast(1)
        val target = (overall.coerceIn(0f, 1f) * spineCount)
        val spineIndex = target.toInt().coerceIn(0, spineCount - 1)
        val fraction = (target - spineIndex).coerceIn(0f, 1f)
        flushNow(s.currentSpineIndex, liveTextFraction, liveRenderOffset)
        if (spineIndex != s.currentSpineIndex) recordTextSkip(s.currentSpineIndex, liveTextFraction, spineIndex, fraction)
        jumpToSpine(spineIndex, fraction)
    }

    /** Jumps to a new spine item, restoring to [fraction] (an EXTRACTOR-stream fraction — 0f for
     *  "start of chapter", 1f for "end of chapter"). Clears the previous spine's cached render
     *  document/projection and pages; the reader screen's `preparePages` call (keyed on
     *  `currentSpineIndex`) rebuilds them for the new spine. */
    private fun jumpToSpine(index: Int, fraction: Float) {
        liveTextFraction = fraction
        liveRenderOffset = 0
        currentRenderDoc = null
        currentProjection = null
        _state.value = _state.value.copy(currentSpineIndex = index, pages = emptyList(), currentPageIndex = 0)
    }

    // ── Reading settings ────────────────────────────────────────────────────────
    // One entry point for all ~45 of them. The settings screen calls `updatePrefs { it.copy(…) }`
    // rather than getting a setter each, which is what keeps adding a new setting to a single
    // field on ReaderPrefs plus its control — no ViewModel, state or store change needed.

    /** Applies [transform] to the live settings and persists them to whichever scope this book is
     *  currently on (its own override, or global). */
    fun updatePrefs(transform: (com.betteraudio.data.settings.ReaderPrefs) -> com.betteraudio.data.settings.ReaderPrefs) {
        val s = _state.value
        val next = transform(s.prefs)
        if (next == s.prefs) return
        _state.value = s.copy(prefs = next)
        viewModelScope.launch {
            if (s.prefsArePerBook) settings.setReaderPrefsForBook(bookId, next)
            else settings.setReaderPrefs(next)
        }
    }

    /** Detach this book from the global settings, or reattach it (inventory #160/#161). Detaching
     *  seeds the override from what's on screen, so it starts as a copy rather than a reset. */
    fun setPrefsScopePerBook(perBook: Boolean) {
        val s = _state.value
        if (perBook == s.prefsArePerBook) return
        viewModelScope.launch {
            if (perBook) {
                settings.setReaderPrefsForBook(bookId, s.prefs)
                _state.value = _state.value.copy(prefsArePerBook = true)
            } else {
                settings.clearReaderPrefsForBook(bookId)
                val global = settings.readerPrefs.first()
                _state.value = _state.value.copy(prefsArePerBook = false, prefs = global)
            }
        }
    }

    /** "Apply to all books": promote this book's settings to global and drop its override. */
    fun applyPrefsToAllBooks() {
        val s = _state.value
        viewModelScope.launch {
            settings.promoteReaderPrefsToGlobal(bookId, s.prefs)
            _state.value = _state.value.copy(prefsArePerBook = false)
        }
    }

    /** Back to the shipped defaults, in whichever scope is active. */
    fun resetPrefs() = updatePrefs { com.betteraudio.data.settings.ReaderPrefs() }

    // ── Position persistence ────────────────────────────────────────────────────

    /** Immediate save — used on chapter change and screen dispose, where a debounced write could
     *  be lost. NonCancellable: may run right as viewModelScope is about to be torn down. */
    fun flushNow(spineIndex: Int, fraction: Float, charOffset: Int? = null) {
        saveJob?.cancel()
        viewModelScope.launch(NonCancellable) { persist(spineIndex, fraction, charOffset) }
    }

    /** Immediate save of the current spine + the live reading position — this is what "closing the
     *  book" should call. A stale one-shot "restore to on load" value would save the wrong spot,
     *  which is exactly why [liveTextFraction]/[liveRenderOffset] are updated on every page turn
     *  ([onPageChanged]) rather than only read back from disk. */
    fun flushCurrent() {
        flushNow(_state.value.currentSpineIndex, liveTextFraction, liveRenderOffset)
    }

    private suspend fun persist(spineIndex: Int, fraction: Float, charOffset: Int?) {
        val spineCount = _state.value.spine.size.coerceAtLeast(1)
        val overall = ((spineIndex + fraction.coerceIn(0f, 1f)) / spineCount).coerceIn(0f, 1f)
        repository.updateTextPosition(bookId, spineIndex, fraction.coerceIn(0f, 1f), overall, charOffset)
        val book = _state.value.book ?: return
        if (book.status == BookStatus.NOT_STARTED && overall > 0f) {
            repository.updateBookStatus(bookId, BookStatus.IN_PROGRESS)
        }
        if (spineIndex == spineCount - 1 && fraction >= 0.999f && book.status != BookStatus.FINISHED) {
            repository.updateBookStatus(bookId, BookStatus.FINISHED)
        }
    }

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

        val targetMs = locatorToAudio(s.currentSpineIndex, liveTextFraction)

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
        AppLog.i(LogCat.UI, "listenFromHere book=${book.id} spine=${s.currentSpineIndex} frac=$liveTextFraction -> ${targetMs}ms")
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

    /** Manually (re)import the book's mapping data (see MappingFileIO) — e.g. after the user
     *  drops in a mapping file obtained elsewhere, or to restore one after a rescan missed it.
     *  Unlike the automatic scan-time import, this always replaces any existing anchors, since
     *  the user explicitly asked for it. */
    fun importMappingFile() {
        val s = _state.value
        val book = s.book ?: return
        if (!s.hasAudio || book.ebookPath == null) return
        viewModelScope.launch {
            val mapping = com.betteraudio.data.sync.MappingFileIO.read(book.folderPath)
            if (mapping == null) {
                _state.update { it.copy(mappingImportMessage = "No mapping data found for this book") }
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

    // ── Contents screen: search ──────────────────────────────────────────────

    /** One match: [spineIndex] + a coarse render-fraction hint (position within that spine
     *  item's render text). Deliberately not run through [RenderProjection] — that only exists
     *  for the current spine's cached document, and building one per search hit across a whole
     *  book would mean re-running `ParagraphExtractor` on every spine item just to jump. The same
     *  render-fraction approximation [jumpToSpine] already uses as a fallback everywhere else. */
    data class SearchResult(val spineIndex: Int, val spineTitle: String?, val snippet: String, val matchStart: Int, val matchEnd: Int, val renderFraction: Float)

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private var searchJob: Job? = null

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searching.value = false
            return
        }
        _searching.value = true
        searchJob = viewModelScope.launch {
            delay(300) // debounce — avoid re-scanning the whole book on every keystroke
            val p = parser
            if (p == null) { _searching.value = false; return@launch }
            val results = withContext(Dispatchers.Default) {
                val out = mutableListOf<SearchResult>()
                val needle = query.lowercase()
                for (item in spineList) {
                    if (out.size >= 200) break // sanity cap for a very long book
                    val bytes = p.readEntry(item.href) ?: continue
                    val doc = EpubDocumentParser.parse(bytes)
                    if (doc.text.isEmpty()) continue
                    val haystack = doc.text.lowercase()
                    var idx = haystack.indexOf(needle)
                    var perChapter = 0
                    while (idx >= 0 && perChapter < 5) {
                        val start = (idx - 40).coerceAtLeast(0)
                        val end = (idx + needle.length + 40).coerceAtMost(doc.text.length)
                        out.add(
                            SearchResult(
                                spineIndex = item.index, spineTitle = item.title,
                                snippet = doc.text.substring(start, end),
                                matchStart = idx - start, matchEnd = idx - start + needle.length,
                                renderFraction = idx.toFloat() / doc.text.length
                            )
                        )
                        idx = haystack.indexOf(needle, idx + needle.length)
                        perChapter++
                    }
                }
                out
            }
            _searchResults.value = results
            _searching.value = false
        }
    }

    fun jumpToSearchResult(result: SearchResult) {
        val s = _state.value
        flushNow(s.currentSpineIndex, liveTextFraction, liveRenderOffset)
        if (result.spineIndex != s.currentSpineIndex) {
            recordTextSkip(s.currentSpineIndex, liveTextFraction, result.spineIndex, result.renderFraction)
        }
        jumpToSpine(result.spineIndex, result.renderFraction)
    }

    override fun onCleared() {
        super.onCleared()
        parser?.close()
    }
}
