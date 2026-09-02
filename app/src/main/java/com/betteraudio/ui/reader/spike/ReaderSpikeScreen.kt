package com.betteraudio.ui.reader.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.ebook.ParagraphExtractor
import com.betteraudio.data.ebook.render.BlockMeasurer
import com.betteraudio.data.ebook.render.EpubDocumentParser
import com.betteraudio.data.ebook.render.Page
import com.betteraudio.data.ebook.render.Paginator
import com.betteraudio.data.ebook.render.RenderProjection
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.ui.reader.render.ReaderPageView
import com.betteraudio.ui.reader.render.ReaderTypography
import com.betteraudio.ui.reader.render.rememberBlockMeasurer
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.system.measureNanoTime

/**
 * ⚠️ THROWAWAY entry point (Phase 0 item 3). Originally proved the real Phase 1 pipeline —
 * [EpubDocumentParser] → [RenderProjection] (validated against the real, frozen
 * [ParagraphExtractor]) → [Paginator] → [ReaderPageView] — on a real book before that pipeline
 * replaced the old WebView reader for real (it now has, in [com.betteraudio.ui.reader.
 * EbookReaderScreen]/`EbookReaderViewModel`). Kept around as a minimal isolated harness for poking
 * at the pipeline directly, outside the full reader chrome/sync wiring. Still missing from the
 * real reader as of this writing: the clip-and-offset block-split mechanism (C.4), accessibility
 * semantics (C.7), fragment-anchor/TOC-jump wiring, and gesture arbitration between page-turn taps
 * and text selection (both currently want the same long-press).
 *
 * Reachable only via the "🔬 Native render spike" overflow item in
 * [com.betteraudio.ui.reader.EbookReaderScreen]. Delete this file (and that menu item) once Phase 1
 * lands for real or the approach is reconsidered.
 */
data class SpikeMetrics(
    val pageCount: Int = 0,
    val paragraphCount: Int = 0,
    val paginateAllMs: Long = 0,
    val measuredOffMainThread: Boolean = true,
    val projectionBuilt: Boolean = false,
    val projectionCheckedBlocks: Int = 0,
    val projectionMismatches: Int = 0,
)

data class SpikeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val pages: List<Page> = emptyList(),
    val metrics: SpikeMetrics = SpikeMetrics(),
)

@HiltViewModel
class ReaderSpikeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: AudiobookRepository,
) : ViewModel() {

    private val bookId: Long = savedStateHandle["bookId"] ?: -1L
    private val _state = MutableStateFlow(SpikeUiState())
    val state: StateFlow<SpikeUiState> = _state.asStateFlow()

    fun runSpike(measurer: BlockMeasurer, viewportWidthPx: Int, viewportHeightPx: Int) {
        viewModelScope.launch {
            val book = repository.getBookOnce(bookId)
            val epubPath = book?.ebookPath
            if (epubPath.isNullOrBlank() || !File(epubPath).exists()) {
                _state.value = SpikeUiState(loading = false, error = "No ebook file for this book")
                return@launch
            }

            var measuredOffMain = true
            var metrics = SpikeMetrics()
            val pages: List<Page>
            val totalNanos = measureNanoTime {
                pages = withContext(Dispatchers.Default) {
                    measuredOffMain = Thread.currentThread().name != "main"
                    val parser = EpubParser(File(epubPath))
                    val info = runCatching { parser.parse() }.getOrNull()

                    // Same "find a real chapter, not the cover/blurb" heuristic as before, now
                    // against the real render-document block count instead of a regex count.
                    var bestHref: String? = null
                    var bestDoc = com.betteraudio.data.ebook.render.RenderDocument(emptyList(), "", emptyMap())
                    for (item in info?.spine.orEmpty()) {
                        val bytes = parser.readEntry(item.href) ?: continue
                        val doc = EpubDocumentParser.parse(bytes)
                        if (doc.blocks.size > bestDoc.blocks.size) { bestDoc = doc; bestHref = item.href }
                    }

                    var projectionBuilt = false
                    var checked = 0
                    var mismatches = 0
                    if (bestHref != null) {
                        val xhtmlBytes = parser.readEntry(bestHref)
                        if (xhtmlBytes != null) {
                            val extractor = ParagraphExtractor.extract(xhtmlBytes)
                            val projection = RenderProjection.buildFor(bestDoc, extractor)
                            projectionBuilt = projection != null
                            if (projection != null) {
                                // C.3's correctness check, run for real against this book: project
                                // each surviving block's midpoint and confirm it lands in the
                                // extractor paragraph of the same ordinal.
                                val surviving = bestDoc.blocks.filter { !it.extractorDropped }
                                for ((idx, b) in surviving.withIndex()) {
                                    checked++
                                    val mid = b.renderStart + b.text.length / 2
                                    val extractorOffset = projection.toExtractorOffset(mid)
                                    val (paraIdx, _) = extractor.locate(extractorOffset)
                                    if (paraIdx != idx) mismatches++
                                }
                            }
                        }
                    }
                    parser.close()

                    val result = if (bestDoc.blocks.isEmpty()) emptyList()
                    else Paginator.paginate(bestDoc.blocks, measurer, viewportWidthPx, viewportHeightPx)
                    metrics = SpikeMetrics(
                        pageCount = result.size,
                        paragraphCount = bestDoc.blocks.size,
                        measuredOffMainThread = measuredOffMain,
                        projectionBuilt = projectionBuilt,
                        projectionCheckedBlocks = checked,
                        projectionMismatches = mismatches,
                    )
                    result
                }
            }
            if (pages.isEmpty()) {
                _state.value = SpikeUiState(loading = false, error = "No spine item produced any blocks")
                return@launch
            }
            AppLog.i(
                LogCat.EBOOK,
                "ReaderSpike: ${metrics.pageCount} pages, ${metrics.paragraphCount} blocks, " +
                    "layout=${totalNanos / 1_000_000}ms, offMainThread=${metrics.measuredOffMainThread}, " +
                    "projection built=${metrics.projectionBuilt} checked=${metrics.projectionCheckedBlocks} " +
                    "mismatches=${metrics.projectionMismatches}"
            )
            _state.value = SpikeUiState(loading = false, pages = pages, metrics = metrics.copy(paginateAllMs = totalNanos / 1_000_000))
        }
    }
}

@Composable
fun ReaderSpikeScreen(onBack: () -> Unit) {
    val viewModel: ReaderSpikeViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val density = LocalDensity.current
    val typography = remember { ReaderTypography() }
    val measurer = rememberBlockMeasurer(typography)

    var pageIndex by remember { mutableIntStateOf(0) }
    var kicked by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().background(Color(0xFFFDF6E3))) {
        if (state.loading) {
            if (kicked == 0) {
                kicked = 1
                val viewportHeightPx = with(density) { 700.dp.toPx() }.toInt()
                val viewportWidthPx = with(density) { 340.dp.toPx() }.toInt()
                viewModel.runSpike(measurer, viewportWidthPx, viewportHeightPx)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Box
        }
        state.error?.let { err ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(err) }
            return@Box
        }

        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.padding(12.dp)) {
                    Text("🔬 Reader spike — real Phase 1 pipeline", style = MaterialTheme.typography.titleMedium)
                    val m = state.metrics
                    Text(
                        "pages=${m.pageCount} blocks=${m.paragraphCount} layout=${m.paginateAllMs}ms " +
                            "offMainThread=${m.measuredOffMainThread}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "projection: built=${m.projectionBuilt} checked=${m.projectionCheckedBlocks} " +
                            "mismatches=${m.projectionMismatches}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text("page ${pageIndex + 1}/${state.pages.size} — tap left/right half to turn",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .pointerInput(state.pages.size) {
                        detectTapGestures { offset ->
                            if (offset.x < size.width / 2) { if (pageIndex > 0) pageIndex-- }
                            else { if (pageIndex < state.pages.lastIndex) pageIndex++ }
                        }
                    }
            ) {
                state.pages.getOrNull(pageIndex)?.let { page ->
                    ReaderPageView(page = page, typography = typography, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
