package com.betteraudio.ui.reader

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.betteraudio.ui.haptics.*

/** Which full-screen pane the reader shows. Contents and Reading settings used to be a
 *  `ModalBottomSheet` and an `AlertDialog` — both deleted. Neither is a separate nav destination:
 *  both need the same [EbookReaderViewModel] instance the reading pane already holds, and Hilt
 *  scopes a route's `hiltViewModel()` to that route's own back-stack entry, so a real second
 *  destination would mean either a fresh (wrong) ViewModel or plumbing the instance through nav
 *  args. A `BackHandler` on this local pane state gives the same "real screen, real back arrow,
 *  system back returns you to reading" behavior the redesign calls for. */
private enum class ReaderPane { READING, CONTENTS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookReaderScreen(
    onBack: () -> Unit,
    onListenFromHere: (Long) -> Unit,
    onOpenSpike: (Long) -> Unit = {},
    viewModel: EbookReaderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var pane by rememberSaveable { mutableStateOf(ReaderPane.READING) }

    androidx.activity.compose.BackHandler(enabled = pane != ReaderPane.READING) { pane = ReaderPane.READING }

    // Keep the screen on while reading — matches the expectation of a dedicated reading mode.
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            viewModel.flushCurrent()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> ReaderErrorContent(state.error!!, onBack)
            pane == ReaderPane.CONTENTS -> com.betteraudio.ui.reader.contents.ContentsScreen(
                state = state, viewModel = viewModel,
                onBack = { pane = ReaderPane.READING },
                onSelectSpine = { index -> viewModel.openSpine(index); pane = ReaderPane.READING }
            )
            pane == ReaderPane.SETTINGS -> com.betteraudio.ui.reader.contents.ReadingSettingsScreen(
                state = state, viewModel = viewModel,
                onBack = { pane = ReaderPane.READING }
            )
            else -> with(this) {
                ReaderContent(
                    state, viewModel, onBack, onListenFromHere, onOpenSpike, scope,
                    onOpenContents = { pane = ReaderPane.CONTENTS },
                    onOpenSettings = { pane = ReaderPane.SETTINGS }
                )
            }
        }
    }
}

@Composable
private fun ReaderErrorContent(error: ReaderError, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(error.message, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = onBack) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderContent(
    state: ReaderUiState,
    viewModel: EbookReaderViewModel,
    onBack: () -> Unit,
    onListenFromHere: (Long) -> Unit,
    onOpenSpike: (Long) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    NativeReaderPager(state = state, viewModel = viewModel, onToggleChrome = { viewModel.toggleChrome() })

    // ── Top chrome ──────────────────────────────────────────────────────────
    // The chapter title IS the button that opens Contents (with a trailing chevron saying so),
    // and Aa opens Reading settings — the redesign's replacement for the old ⋮ overflow, which
    // buried both behind an extra tap and a menu label. ⚠️ THROWAWAY "Native render spike" entry
    // point (Phase 0 item 3) moved here too, unlabelled by a menu — see its own doc comment.
    AnimatedVisibility(
        visible = state.chromeVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        TopAppBar(
            title = {
                Column(
                    Modifier.clickable(onClick = onOpenContents),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        state.book?.displayTitle ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            state.currentSpineTitle ?: "Contents",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            navigationIcon = {
                HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                // ⚠️ THROWAWAY — Phase 0 item 3. Not gated on BuildConfig.DEBUG: this app is only
                // ever deployed as a release-signed build, so a DEBUG-only gate would make the
                // spike unreachable on the build actually installed on the test device. Delete
                // this button along with ui/reader/spike/ when Phase 0 ends.
                state.book?.id?.let { bookId ->
                    HapticIconButton(onClick = { onOpenSpike(bookId) }) { Text("🔬") }
                }
                HapticTextButton(onClick = onOpenSettings) {
                    Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        )
    }

    // ── Bottom chrome ───────────────────────────────────────────────────────
    AnimatedVisibility(
        visible = state.chromeVisible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                BookScrubber(
                    spineCount = state.spine.size,
                    currentSpineIndex = state.currentSpineIndex,
                    currentPageIndex = state.currentPageIndex,
                    pageCount = state.pages.size,
                    onScrub = { viewModel.jumpToOverallFraction(it) }
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (state.pages.isNotEmpty()) "Page ${state.currentPageIndex + 1} of ${state.pages.size}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val pagesLeft = (state.pages.size - state.currentPageIndex - 1).coerceAtLeast(0)
                    Text(
                        if (state.pages.isNotEmpty()) "$pagesLeft page${if (pagesLeft == 1) "" else "s"} left in chapter" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HapticIconButton(
                        onClick = { viewModel.prevChapter() },
                        enabled = state.currentSpineIndex > 0
                    ) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous chapter") }

                    if (state.hasAudio) {
                        HapticFilledTonalButton(onClick = {
                            scope.launch { viewModel.listenFromHere()?.let(onListenFromHere) }
                        }) {
                            Icon(Icons.Default.Headphones, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Listen from here")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    HapticIconButton(
                        onClick = { viewModel.nextChapter() },
                        enabled = state.currentSpineIndex < state.spine.size - 1
                    ) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next chapter") }
                }
            }
        }
    }

    // ── Sync status (priority: aligning → model downloading → synced → approximate) ──
    // Gated behind EBOOK_SYNC_UI: the whole listen↔read sync surface ships frozen (Phase 0).
    // Its controls now live in the Contents screen's own overflow, not here.
    if (com.betteraudio.util.FeatureFlags.EBOOK_SYNC_UI) {
        val align = state.alignProgress
        val model = state.modelState
        Box(Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 12.dp, end = 12.dp)) {
            when {
                align?.running == true -> {
                    val total = align.chaptersTotal.coerceAtLeast(1)
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Improving sync… ${align.chaptersDone}/$total · ${align.anchorsFound} anchors",
                                    style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(
                                    progress = { align.chaptersDone.toFloat() / total },
                                    modifier = Modifier.width(180.dp).padding(top = 2.dp)
                                )
                            }
                            HapticIconButton(onClick = { viewModel.cancelSync() }) { Icon(Icons.Default.Close, "Cancel") }
                        }
                    }
                }
                model is com.betteraudio.data.transcribe.ModelState.Downloading ->
                    StatusChip("Downloading speech model… ${model.pct}%", MaterialTheme.colorScheme.secondaryContainer)
                model is com.betteraudio.data.transcribe.ModelState.Unzipping ->
                    StatusChip("Preparing speech model…", MaterialTheme.colorScheme.secondaryContainer)
                state.hasAudio && state.anchorCount > 0 ->
                    StatusChip("Synced · ${state.anchorCount} anchors", MaterialTheme.colorScheme.tertiaryContainer)
                state.chapterMapApproximate && state.hasAudio ->
                    StatusChip("Approximate alignment — see Contents to align or improve sync", MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

/** A draggable whole-book scrubber with a tick per chapter boundary, replacing the plain prev/
 *  next-only chrome. Dragging previews the target chapter; releasing calls [onScrub] with the
 *  whole-book fraction (same coarse "every spine item is equal length" model `persist()`'s
 *  `textOverallFraction` already uses). */
@Composable
private fun BookScrubber(
    spineCount: Int,
    currentSpineIndex: Int,
    currentPageIndex: Int,
    pageCount: Int,
    onScrub: (Float) -> Unit,
) {
    val settledFraction = remember(spineCount, currentSpineIndex, currentPageIndex, pageCount) {
        if (spineCount <= 0) 0f
        else {
            val withinSpine = if (pageCount > 0) currentPageIndex.toFloat() / pageCount else 0f
            ((currentSpineIndex + withinSpine) / spineCount).coerceIn(0f, 1f)
        }
    }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(spineCount) {
                if (spineCount <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFraction?.let(onScrub); dragFraction = null },
                    onDragCancel = { dragFraction = null }
                )
            }
    ) {
        val shownFraction = dragFraction ?: settledFraction
        Canvas(Modifier.fillMaxSize()) {
            val midY = size.height / 2f
            drawLine(trackColor, androidx.compose.ui.geometry.Offset(0f, midY), androidx.compose.ui.geometry.Offset(size.width, midY), strokeWidth = 3f)
            drawLine(
                fillColor, androidx.compose.ui.geometry.Offset(0f, midY),
                androidx.compose.ui.geometry.Offset(size.width * shownFraction, midY), strokeWidth = 3f
            )
            if (spineCount > 1) {
                for (i in 1 until spineCount) {
                    val x = size.width * (i.toFloat() / spineCount)
                    drawLine(
                        trackColor, androidx.compose.ui.geometry.Offset(x, midY - 4f),
                        androidx.compose.ui.geometry.Offset(x, midY + 4f), strokeWidth = 2f
                    )
                }
            }
            drawCircle(fillColor, radius = 6f, center = androidx.compose.ui.geometry.Offset(size.width * shownFraction, midY))
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color, tonalElevation = 2.dp) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

/**
 * The native page renderer (docs/reader-features-and-plan.md Phase 1) that replaced the old
 * WebView-based reader (`ReaderWebView.kt`, deleted). Owns the tap-zone page-turn gestures;
 * left/right thirds turn pages (falling
 * through to chapter navigation at a spine item's first/last page), the center toggles chrome.
 *
 * Known, documented gap: this tap-zone gesture layer and [ReaderPageView]'s `SelectionContainer`
 * both want the same long-press — confirmed on-device (Phase 0 spike testing) to currently favor
 * page-turn over starting a selection. Real gesture arbitration between the two is Phase 4 scope
 * (plan C.4/A5), not fixed here.
 */
@Composable
private fun NativeReaderPager(state: ReaderUiState, viewModel: EbookReaderViewModel, onToggleChrome: () -> Unit) {
    val fontScale = state.fontSizePct / 100f
    val readerTheme = com.betteraudio.ui.reader.render.ReaderTheme.fromName(state.readerTheme)
    val fontFamily = com.betteraudio.ui.reader.render.ReaderFontFamilyChoice.fromName(state.readerFontFamily)
    val lineSpacing = com.betteraudio.ui.reader.render.ReaderLineSpacing.fromName(state.readerLineSpacing)
    val margins = com.betteraudio.ui.reader.render.ReaderMargins.fromName(state.readerMargins)
    val typography = remember(fontScale, readerTheme, fontFamily, lineSpacing, state.readerJustify, state.readerHyphenate) {
        com.betteraudio.ui.reader.render.ReaderTypography(
            baseSizeSp = 18f * fontScale,
            fontFamily = fontFamily.family,
            lineHeightMultiplier = lineSpacing.multiplier,
            justify = state.readerJustify,
            hyphenate = state.readerHyphenate,
            color = readerTheme.fg
        )
    }
    val measurer = com.betteraudio.ui.reader.render.rememberBlockMeasurer(typography)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Reserve top/bottom chrome space UNCONDITIONALLY — found on-device: computing pagination
        // against the full viewport, then only padding the rendered content when chrome happened
        // to be visible, meant a page laid out while chrome was hidden would overflow its box (and
        // get clipped) the moment chrome was toggled back on for the same page. Reserving the same
        // margin always means toggling chrome only fades the bars in/out over that margin — the
        // text itself never reflows or shifts, which is also just the correct reader behavior.
        val topPad = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 20.dp
        val bottomPad = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 64.dp + 20.dp
        val widthPx = with(density) { (maxWidth - margins.horizontal * 2).toPx() }.toInt()
        val heightPx = with(density) { (maxHeight - topPad - bottomPad).toPx() }.toInt()

        androidx.compose.runtime.LaunchedEffect(
            state.currentSpineIndex, state.fontSizePct, state.readerFontFamily,
            state.readerLineSpacing, state.readerMargins, state.readerJustify, state.readerHyphenate,
            widthPx, heightPx
        ) {
            if (widthPx > 0 && heightPx > 0) viewModel.preparePages(measurer, widthPx, heightPx)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(readerTheme.bg)
                .pointerInput(state.pages.size, state.currentPageIndex, state.currentSpineIndex) {
                    detectTapGestures(
                        onTap = { offset ->
                            when {
                                offset.x < size.width * 0.3f -> {
                                    if (state.currentPageIndex > 0) viewModel.onPageChanged(state.currentPageIndex - 1)
                                    else viewModel.prevChapter()
                                }
                                offset.x > size.width * 0.7f -> {
                                    if (state.currentPageIndex < state.pages.lastIndex) viewModel.onPageChanged(state.currentPageIndex + 1)
                                    else viewModel.nextChapter()
                                }
                                else -> onToggleChrome()
                            }
                        }
                    )
                }
        ) {
            state.pages.getOrNull(state.currentPageIndex)?.let { page ->
                com.betteraudio.ui.reader.render.ReaderPageView(
                    page = page,
                    typography = typography,
                    modifier = Modifier.fillMaxSize().padding(start = margins.horizontal, end = margins.horizontal, top = topPad, bottom = bottomPad)
                )
            }
        }
    }
}
