package com.betteraudio.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.dao.ReaderMarkDao
import com.betteraudio.data.db.entities.ReaderMark
import com.betteraudio.data.db.entities.ReaderMarkKind
import com.betteraudio.data.ebook.render.RenderBlock
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

/**
 * One chapter's blocks, parsed and ready to draw. The unit the continuous reader is built out of:
 * scrolled mode renders a *window* of these end to end, so the chapter boundary is a paragraph
 * break like any other rather than the point where the book stops.
 */
data class LoadedChapter(
    val spineIndex: Int,
    val title: String?,
    val blocks: List<com.betteraudio.data.ebook.render.RenderBlock>,
)

/**
 * Where the continuous reader should be put, and a [generation] that says whether it is a *new*
 * instruction.
 *
 * Only a deliberate jump — opening a chapter from Contents, following a bookmark, dragging the
 * scrubber, the footer's chapter arrows — bumps the generation. Reading across a chapter boundary
 * by scrolling does not, which is the whole point: the window rotates and the current chapter
 * changes underneath, and if that re-anchored the list it would yank the page out from under the
 * reader at exactly the moment the seam is supposed to be invisible.
 */
data class ScrollAnchor(val generation: Long, val spineIndex: Int, val renderStart: Int)

/**
 * A paragraph to flash briefly, and a [generation] so that flashing the *same* paragraph twice
 * still reads as two separate instructions.
 *
 * Raised only by the two commands that cross between listening and reading — "Listen from here"
 * here in the reader, and the player's "Read from here", which arrives as the `flash` nav
 * argument. Everything else that moves the reader (a page turn, Contents, the scrubber) leaves it
 * alone: the flash means "this is the paragraph the other half of the book is at", and firing it
 * for ordinary navigation would spend the meaning.
 */
data class ParagraphFlash(val generation: Long, val spineIndex: Int, val renderStart: Int)

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
    /** The chapters resident for continuous mode: the current one plus its neighbours, in spine
     *  order. Empty in paged mode's terms — paged reads [pages] instead. */
    val scrollWindow: List<LoadedChapter> = emptyList(),
    val scrollAnchor: ScrollAnchor? = null,
    /** The listen↔read landing spot to flash, until the screen has played it and cleared it. */
    val flash: ParagraphFlash? = null,
    /** Every bookmark and highlight in this book, in reading order (see [ReaderMarkDao]). */
    val marks: List<ReaderMark> = emptyList(),
    /** Tap-a-paragraph-to-highlight is armed. Screen state, never persisted: it is a thing you are
     *  doing for the next few seconds, not a preference. */
    val highlighting: Boolean = false,
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
    private val readerMarkDao: ReaderMarkDao,
    private val syncAligner: com.betteraudio.data.transcribe.SyncAligner,
    private val modelManager: com.betteraudio.data.transcribe.VoskModelManager
) : ViewModel() {

    private val bookId: Long = savedStateHandle["bookId"] ?: -1L

    // Set by the player's "Read from here", which navigates here rather than calling us — the
    // route carries the intent because the jump itself is already done by the time this reader
    // exists (the locator is persisted, and load() restores to it like any other saved position).
    // Consumed by the first [preparePages], which is where the landing paragraph is finally known.
    private var pendingFlash: Boolean = savedStateHandle["flash"] ?: false
    private var flashGeneration: Long = 0L

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

    // Parsed chapters, kept so the neighbours of wherever you are can be drawn without waiting on
    // a parse. Small and bounded: reading forward evicts the chapter you left three chapters ago,
    // which is far enough back that turning round does not re-parse anything.
    private val renderDocCache = object : LinkedHashMap<Int, RenderDocument>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, RenderDocument>) = size > 5
    }
    // Which spine item the scroll anchor has already been published for. Guards against
    // re-anchoring a chapter the reader has scrolled into of their own accord — see [ScrollAnchor].
    private var anchoredSpine: Int = -1
    private var anchorGeneration: Long = 0L

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
            readerMarkDao.getForBook(bookId)
                .onEach { marks -> _state.update { it.copy(marks = marks) } }
                .launchIn(viewModelScope)
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
        if (spineList.getOrNull(spineIndex) == null) return
        if (parser == null) return
        viewModelScope.launch {
            val doc = renderDocFor(spineIndex)
            if (doc == null || _state.value.currentSpineIndex != spineIndex) return@launch
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

            // The restored position in render coordinates. Until this landed, [liveRenderOffset]
            // stayed at 0 from load() until the first page turn — so opening a book part-way
            // through and pressing "Listen from here" straight away flashed (and reported) the
            // first paragraph of the chapter rather than the one on screen. The fraction it is
            // derived from is unchanged, so the persisted position still agrees with itself.
            liveRenderOffset = targetRenderOffset

            _state.update { it.copy(pages = pages, currentPageIndex = pageIndex) }

            // The scroll window follows the current chapter, always. Publishing it here rather
            // than on a separate trigger means it is rebuilt by exactly the same events that
            // rebuild pagination — a chapter change, a jump — and by nothing else.
            refreshScrollWindow(spineIndex)

            // Anchor the continuous reader, but only for a chapter it has not been placed in yet.
            // Scrolling across a boundary sets [anchoredSpine] itself precisely so this does not
            // fire and drag the reader back to the top of the chapter they just flowed into.
            if (anchoredSpine != spineIndex) {
                anchoredSpine = spineIndex
                anchorGeneration++
                _state.update {
                    it.copy(scrollAnchor = ScrollAnchor(anchorGeneration, spineIndex, targetRenderOffset))
                }
            }

            // "Read from here" landed. Deliberately here and not in `load()`: the paragraph a
            // locator falls in is a property of the *rendered* chapter, which does not exist
            // until this runs — and by this point the page index and the scroll anchor above have
            // both already been pointed at it, so the flash is drawn on something on screen.
            if (pendingFlash) {
                pendingFlash = false
                flashParagraph(spineIndex, targetRenderOffset, doc.blocks)
            }
        }
    }

    /** Raise a one-shot flash on the paragraph containing [renderOffset]. */
    private fun flashParagraph(spineIndex: Int, renderOffset: Int, blocks: List<RenderBlock>) {
        val block = blocks.firstOrNull { renderOffset >= it.renderStart && renderOffset < it.renderEnd }
            ?: blocks.lastOrNull() ?: return
        flashGeneration++
        _state.update { it.copy(flash = ParagraphFlash(flashGeneration, spineIndex, block.renderStart)) }
    }

    /** Flash wherever the reader is right now — "Listen from here" saying which paragraph it just
     *  handed to the player. Uses the live render offset rather than the current page's first
     *  block so it means the same thing in continuous mode, where there is no page. */
    private fun flashCurrentParagraph() {
        val spineIndex = _state.value.currentSpineIndex
        val blocks = currentRenderDoc?.blocks ?: renderDocCache[spineIndex]?.blocks ?: return
        flashParagraph(spineIndex, liveRenderOffset, blocks)
    }

    /** The screen, once it has played [generation]'s flash. Clearing matters: the glow is a
     *  composable-lifetime animation, so a paragraph left marked would flash again every time it
     *  scrolled off the screen and back. */
    fun clearFlash(generation: Long) {
        _state.update { if (it.flash?.generation == generation) it.copy(flash = null) else it }
    }

    /** Parse [index], from the cache when it is there. The parse is the expensive half of opening a
     *  chapter, so caching it is what makes both the continuous window and a paged chapter turn
     *  land without a visible gap. */
    private suspend fun renderDocFor(index: Int): RenderDocument? {
        renderDocCache[index]?.let { return it }
        val href = spineList.getOrNull(index)?.href ?: return null
        val p = parser ?: return null
        val bytes = withContext(Dispatchers.IO) { p.readEntry(href) } ?: return null
        val doc = withContext(Dispatchers.Default) { EpubDocumentParser.parse(bytes) }
        renderDocCache[index] = doc
        return doc
    }

    /**
     * Load the chapter at [center] and its two neighbours and publish them as the continuous
     * reader's window.
     *
     * Three, not more: one either side is everything needed for the seam to be invisible in both
     * directions, and the window rotates as soon as the reader's position crosses into a
     * neighbour — so the chapter after next is being parsed while there is still a whole chapter
     * of reading in front of it. A wider window would hold more of the book in memory to buy
     * nothing.
     *
     * The centre is published first and the neighbours are added as they arrive, so a chapter jump
     * shows its text immediately instead of waiting on two parses it does not need yet.
     */
    private suspend fun refreshScrollWindow(center: Int) {
        fun chapterOf(index: Int, doc: RenderDocument) =
            LoadedChapter(index, spineList.getOrNull(index)?.title, doc.blocks)

        val wanted = (center - 1..center + 1).filter { it in spineList.indices }

        // Start from what is ALREADY on screen, keeping only the chapters still wanted — never
        // from scratch. When the reader scrolls across a boundary this is a rotation: two of the
        // three chapters are already resident and must stay exactly where they are. Rebuilding the
        // window from the centre outwards would empty the list down to one chapter for however
        // long a parse takes, and the text above and below the reader would vanish and come back
        // — the precise flash this whole mechanism exists to remove.
        val loaded = sortedMapOf<Int, LoadedChapter>()
        _state.value.scrollWindow.forEach { if (it.spineIndex in wanted) loaded[it.spineIndex] = it }
        if (loaded.isNotEmpty()) _state.update { it.copy(scrollWindow = loaded.values.toList()) }

        // Centre first, then out: on a jump the reader is looking at a blank screen until the
        // chapter they asked for arrives, and the neighbours can take as long as they need.
        for (index in listOf(center, center + 1, center - 1)) {
            if (index !in wanted || loaded.containsKey(index)) continue
            val doc = renderDocFor(index) ?: continue
            // The reader may have moved on while that parse ran; a window centred on a chapter
            // they have left is worse than none.
            if (_state.value.currentSpineIndex != center) return
            loaded[index] = chapterOf(index, doc)
            _state.update { it.copy(scrollWindow = loaded.values.toList()) }
        }
    }

    /**
     * The continuous reader reporting where it now is: [renderStart] of the first visible block,
     * and which chapter that block belongs to.
     *
     * When the chapter differs from the current one the reader has simply *read* across a
     * boundary, and that is treated as reading, not as navigation — no skip is recorded (nothing
     * was skipped), and [anchoredSpine] is moved forward so the repagination this triggers does
     * not re-anchor the list. The window rotation follows from `preparePages` seeing a new spine
     * index, which is the same path a jump takes.
     */
    fun onScrolledTo(spineIndex: Int, renderStart: Int) {
        val s = _state.value
        val crossed = spineIndex != s.currentSpineIndex
        if (crossed) {
            if (spineIndex !in spineList.indices) return
            flushNow(s.currentSpineIndex, if (spineIndex > s.currentSpineIndex) 1f else 0f)
            anchoredSpine = spineIndex
            currentRenderDoc = renderDocCache[spineIndex]
            currentProjection = null
            _state.update { it.copy(currentSpineIndex = spineIndex, pages = emptyList(), currentPageIndex = 0) }
        }
        liveRenderOffset = renderStart
        // Keep the footer's "page x of y" honest while scrolling, when this chapter's pagination
        // is loaded. Right after a crossing it is not, and it catches up when preparePages lands.
        val pages = _state.value.pages
        val pageIndex = pages.indexOfFirst { page ->
            page.blocks.any { renderStart >= it.renderStart && renderStart < it.renderEnd }
        }
        if (pageIndex >= 0 && pageIndex != _state.value.currentPageIndex) {
            _state.update { it.copy(currentPageIndex = pageIndex) }
        }

        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // Off the critical path on purpose: resolving the fraction can parse this chapter's
            // extractor stream, and that must not happen inside a scroll frame.
            liveTextFraction = withContext(Dispatchers.Default) {
                fractionForRenderOffset(spineIndex, renderStart)
            }
            delay(1_000)
            persist(spineIndex, liveTextFraction, renderStart)
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
        // A jump IS an instruction to move the continuous reader, unlike scrolling across a
        // boundary — so let preparePages publish a fresh anchor for wherever this lands.
        anchoredSpine = -1
        currentRenderDoc = null
        currentProjection = null
        _state.value = _state.value.copy(currentSpineIndex = index, pages = emptyList(), currentPageIndex = 0)
    }

    // ── Bookmarks & highlights ───────────────────────────────────────────

    /** The extractor-stream fraction a render offset sits at — the coordinate [jumpToSpine] takes.
     *  Same derivation `onPageChanged` uses for the live position; factored out because a mark has
     *  to record it at the moment it is made, for a chapter that may not be loaded when it is
     *  followed. Returns 0f when this book has no extractor stream (no audio counterpart), which is
     *  the same fallback the reading position itself uses. */
    private fun fractionForRenderOffset(spineIndex: Int, renderOffset: Int): Float {
        val extractor = paragraphsFor(spineIndex) ?: return 0f
        if (extractor.totalChars <= 0) return 0f
        val docLen = currentRenderDoc?.text?.length?.coerceAtLeast(1) ?: 1
        val extractorOffset = currentProjection?.toExtractorOffset(renderOffset)
            ?: ((renderOffset.toFloat() / docLen) * extractor.totalChars).toInt()
        return extractor.fractionForCharOffset(extractorOffset)
    }

    /** The mark, if any, already covering [renderStart] in [spineIndex]. Used both to make the
     *  bookmark button a toggle and to make tapping a highlighted paragraph un-highlight it. */
    private fun markAt(kind: String, spineIndex: Int, renderStart: Int): ReaderMark? =
        _state.value.marks.firstOrNull {
            it.kind == kind && it.spineIndex == spineIndex && it.renderStart == renderStart
        }

    /** Whether the page currently on screen is bookmarked. */
    fun currentPageBookmark(): ReaderMark? {
        val s = _state.value
        val start = s.pages.getOrNull(s.currentPageIndex)?.blocks?.firstOrNull()?.renderStart ?: return null
        return markAt(ReaderMarkKind.BOOKMARK, s.currentSpineIndex, start)
    }

    /**
     * Bookmark (or un-bookmark) the page on screen. A bookmark is anchored to the page's FIRST
     * block rather than to the page number: page numbers are a function of the font size, the
     * margins and the screen, so a bookmark stored as "page 41" would wander to a different
     * paragraph the moment any of those changed — which for a bookmark is the same as being lost.
     */
    fun toggleBookmark() {
        val s = _state.value
        val page = s.pages.getOrNull(s.currentPageIndex) ?: return
        val block = page.blocks.firstOrNull() ?: return
        val existing = markAt(ReaderMarkKind.BOOKMARK, s.currentSpineIndex, block.renderStart)
        viewModelScope.launch {
            if (existing != null) { readerMarkDao.deleteById(existing.id); return@launch }
            readerMarkDao.insert(
                ReaderMark(
                    bookId = bookId,
                    spineIndex = s.currentSpineIndex,
                    kind = ReaderMarkKind.BOOKMARK,
                    renderStart = block.renderStart,
                    renderEnd = page.blocks.lastOrNull()?.renderEnd ?: block.renderEnd,
                    textFraction = fractionForRenderOffset(s.currentSpineIndex, block.renderStart),
                    preview = block.text.take(PREVIEW_CHARS).trim(),
                    chapterTitle = s.currentSpineTitle.orEmpty()
                )
            )
        }
    }

    /** Highlight, or un-highlight, one paragraph. [colorArgb] is the tint the reader picked.
     *  [spineIndex] is passed rather than assumed: continuous mode shows the neighbouring chapters
     *  too, so the paragraph under the finger is not always in the current one. */
    fun toggleHighlight(spineIndex: Int, block: RenderBlock, colorArgb: Int) {
        val existing = markAt(ReaderMarkKind.HIGHLIGHT, spineIndex, block.renderStart)
        viewModelScope.launch {
            if (existing != null) {
                // Same colour again means "remove"; a different one means "recolour", so a second
                // pass with a new tint is not a delete-then-re-add the user has to do by hand.
                if (existing.colorArgb == colorArgb) readerMarkDao.deleteById(existing.id)
                else readerMarkDao.update(existing.copy(colorArgb = colorArgb))
                return@launch
            }
            readerMarkDao.insert(
                ReaderMark(
                    bookId = bookId,
                    spineIndex = spineIndex,
                    kind = ReaderMarkKind.HIGHLIGHT,
                    renderStart = block.renderStart,
                    renderEnd = block.renderEnd,
                    textFraction = fractionForRenderOffset(spineIndex, block.renderStart),
                    colorArgb = colorArgb,
                    preview = block.text.take(PREVIEW_CHARS).trim(),
                    chapterTitle = spineList.getOrNull(spineIndex)?.title.orEmpty()
                )
            )
        }
    }

    fun setHighlighting(on: Boolean) { _state.update { it.copy(highlighting = on) } }

    fun deleteMark(id: Long) { viewModelScope.launch { readerMarkDao.deleteById(id) } }

    fun setMarkNote(mark: ReaderMark, note: String) {
        viewModelScope.launch { readerMarkDao.update(mark.copy(note = note)) }
    }

    /** Go to a mark. Within the current chapter this is a plain page change, which keeps the
     *  render document (and so every highlight already painted) exactly as it is; across chapters
     *  it goes through the same [jumpToSpine] path a Contents tap uses. */
    fun openMark(mark: ReaderMark) {
        val s = _state.value
        if (mark.spineIndex == s.currentSpineIndex && s.pages.isNotEmpty()) {
            val index = s.pages.indexOfFirst { page ->
                page.blocks.any { it.renderEnd > mark.renderStart }
            }
            if (index >= 0) { onPageChanged(index); return }
        }
        flushNow(s.currentSpineIndex, liveTextFraction, liveRenderOffset)
        if (mark.spineIndex != s.currentSpineIndex) {
            recordTextSkip(s.currentSpineIndex, liveTextFraction, mark.spineIndex, mark.textFraction)
        }
        jumpToSpine(mark.spineIndex, mark.textFraction)
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

        // Say which paragraph was handed over, before the player sheet starts rising over the
        // page. Raised only once the audio-side guards above have passed, so it never promises a
        // jump that is about to be abandoned.
        flashCurrentParagraph()

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

/** How much of a marked paragraph is kept for the list in Contents. Enough to recognise the
 *  passage, short enough that a hundred marks are not a hundred paragraphs of duplicated book. */
private const val PREVIEW_CHARS = 160
