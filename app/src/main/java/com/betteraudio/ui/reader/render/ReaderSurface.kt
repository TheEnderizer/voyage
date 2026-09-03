package com.betteraudio.ui.reader.render

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.betteraudio.data.ebook.render.Page
import com.betteraudio.data.settings.ReaderPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Everything between the two chrome bars: the page (or the continuous scroll), the gestures that
 * turn it, and the two overlays that sit on top of it (the reading ruler and the software dimmer).
 *
 * Split out of `EbookReaderScreen` because the settings that drive it — scrolled vs paged, column
 * count, the four margins, tap zones, swipe, page-turn animation, the ruler — are a self-contained
 * cluster with no bearing on the top/bottom bars, and keeping them here stops the screen file from
 * becoming the place every reading setting has to be threaded through.
 */
@Composable
fun ReaderSurface(
    pages: List<Page>,
    pageIndex: Int,
    prefs: ReaderPrefs,
    palette: ReaderPalette,
    typography: ReaderTypography,
    /** Extra top/bottom space the chrome bars occupy, reserved whether or not they're showing. */
    chromeTop: Dp,
    chromeBottom: Dp,
    onPageIndexChange: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToggleChrome: () -> Unit,
    onBrightnessDrag: (Float) -> Unit,
    /** Reports the usable column size back so the caller can (re)paginate to it. */
    onViewportMeasured: (widthPx: Int, heightPx: Int, columns: Int) -> Unit,
    /** Anything else that invalidates the existing pagination — the chapter, and the typography
     *  that changes how tall each block measures. Without it, a settings change that doesn't alter
     *  the viewport's pixel size (line spacing, paragraph gap, weight, hyphenation) would restyle
     *  the text but keep the old page breaks, silently overflowing or under-filling every page. */
    paginationKey: Any,
    aidsRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(palette.bg)) {
        val density = LocalDensity.current
        val columns = resolveColumnCount(prefs.columnCount, maxWidth)

        // Geometry. maxColumnWidthDp caps the measure and centres what's left over, so a landscape
        // phone or a tablet doesn't render 140-character lines just because it can (#41).
        val availableWidth = maxWidth - prefs.marginLeftDp.dp - prefs.marginRightDp.dp
        val gap = availableWidth * (prefs.columnGapPct.coerceIn(0, 20) / 100f)
        val cappedWidth = if (prefs.maxColumnWidthDp > 0) {
            minOf(availableWidth, prefs.maxColumnWidthDp.dp * columns + gap * (columns - 1))
        } else availableWidth
        val sideSlack = ((availableWidth - cappedWidth) / 2).coerceAtLeast(0.dp)
        val columnWidth = ((cappedWidth - gap * (columns - 1)) / columns).coerceAtLeast(1.dp)
        val contentHeight = (maxHeight - chromeTop - chromeBottom).coerceAtLeast(1.dp)

        val widthPx = with(density) { columnWidth.toPx() }.toInt()
        val heightPx = with(density) { contentHeight.toPx() }.toInt()
        LaunchedEffect(widthPx, heightPx, columns, paginationKey) {
            if (widthPx > 0 && heightPx > 0) onViewportMeasured(widthPx, heightPx, columns)
        }

        val contentPadding = PaddingValues(
            start = prefs.marginLeftDp.dp + sideSlack,
            end = prefs.marginRightDp.dp + sideSlack,
            top = chromeTop,
            bottom = chromeBottom,
        )

        if (prefs.scrolled) {
            ScrolledSurface(pages, pageIndex, prefs, typography, contentPadding, aidsRunning, onPageIndexChange, onToggleChrome)
        } else {
            PagedSurface(
                pages = pages, pageIndex = pageIndex, prefs = prefs, typography = typography,
                columns = columns, columnWidth = columnWidth, gap = gap,
                contentPadding = contentPadding, aidsRunning = aidsRunning,
                onPageIndexChange = onPageIndexChange,
                onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
                onToggleChrome = onToggleChrome, onBrightnessDrag = onBrightnessDrag,
            )
        }

        // ── Reading ruler (#130) ────────────────────────────────────────────
        // A translucent band tracking N lines, draggable up and down the page. Trivial to do
        // natively — we know the line height exactly — where a WebView reader has to guess.
        if (prefs.rulerEnabled) {
            ReadingRuler(
                lineHeightDp = with(density) { (typography.baseSizeSp * typography.lineHeightMultiplier).sp.toDp() },
                lines = prefs.rulerLines,
                opacity = prefs.rulerOpacity,
                dark = palette.isDark,
                topInset = chromeTop,
                height = contentHeight,
            )
        }

        // ── Software dimmer (#48) ───────────────────────────────────────────
        // Goes darker than the OS brightness floor by painting black over the page. Must not eat
        // touches, hence no pointerInput here — the page underneath still turns normally.
        if (prefs.dimBelowFloor > 0f) {
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(2f)
                    .background(Color.Black.copy(alpha = prefs.dimBelowFloor.coerceIn(0f, 0.85f)))
            )
        }
    }
}

/** "Auto" (#7) means two columns once there is genuinely room for two readable measures. */
private fun resolveColumnCount(setting: Int, width: Dp): Int = when (setting) {
    1, 2 -> setting
    else -> if (width >= 640.dp) 2 else 1
}

@Composable
private fun PagedSurface(
    pages: List<Page>,
    pageIndex: Int,
    prefs: ReaderPrefs,
    typography: ReaderTypography,
    columns: Int,
    columnWidth: Dp,
    gap: Dp,
    contentPadding: PaddingValues,
    aidsRunning: Boolean,
    onPageIndexChange: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onToggleChrome: () -> Unit,
    onBrightnessDrag: (Float) -> Unit,
) {
    // A "page" from the paginator is one column; a spread is the `columns` of them on screen at
    // once. Keeping the index in columns means the stored position, the scrubber and the progress
    // maths all stay in one unit whether the reader is showing one column or two.
    val spreadStart = (pageIndex / columns) * columns
    var forward by remember { mutableStateOf(true) }

    fun turn(delta: Int) {
        val target = spreadStart + delta * columns
        when {
            target < 0 -> onPrevChapter()
            target > pages.lastIndex -> onNextChapter()
            else -> { forward = delta > 0; onPageIndexChange(target) }
        }
    }

    // Auto page-turn (#129) — a plain timer on top of the same turn() every gesture uses.
    if (aidsRunning && prefs.autoPageTurnSeconds > 0) {
        LaunchedEffect(spreadStart, prefs.autoPageTurnSeconds, aidsRunning) {
            delay(prefs.autoPageTurnSeconds * 1000L)
            turn(1)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .readerGestures(prefs, onTurn = { turn(it) }, onToggleChrome = onToggleChrome, onBrightnessDrag = onBrightnessDrag)
    ) {
        val animate = prefs.animated && ReaderPageTurn.fromName(prefs.pageTurn) != ReaderPageTurn.NONE
        AnimatedContent(
            targetState = spreadStart,
            transitionSpec = { pageTurnTransition(ReaderPageTurn.fromName(prefs.pageTurn), forward, animate) },
            label = "page-turn"
        ) { start ->
            Row(Modifier.fillMaxSize().padding(contentPadding)) {
                for (c in 0 until columns) {
                    if (c > 0) Spacer(Modifier.width(gap))
                    val page = pages.getOrNull(start + c)
                    Box(Modifier.width(columnWidth).fillMaxHeight()) {
                        if (page != null) ReaderPageView(page, typography, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrolledSurface(
    pages: List<Page>,
    pageIndex: Int,
    prefs: ReaderPrefs,
    typography: ReaderTypography,
    contentPadding: PaddingValues,
    aidsRunning: Boolean,
    onPageIndexChange: (Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    // Pagination still runs in scrolled mode; it is just not what gets drawn. That is what lets
    // the position model, the scrubber and the "page x of y" footer stay identical across modes
    // instead of needing a second, scroll-only notion of where you are.
    val blocks = remember(pages) { pages.flatMap { it.blocks } }
    val pageOfBlock = remember(pages) {
        buildMap { pages.forEachIndexed { i, p -> p.blocks.forEach { put(it.renderStart, i) } } }
    }
    val listState = rememberLazyListState()

    // Restore the scroll position when the mode is switched or the chapter changes.
    LaunchedEffect(pages) {
        val firstOfPage = pages.getOrNull(pageIndex)?.blocks?.firstOrNull()?.renderStart
        val index = blocks.indexOfFirst { it.renderStart == firstOfPage }
        if (index >= 0) listState.scrollToItem(index)
    }

    LaunchedEffect(listState, pageOfBlock) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { i ->
                blocks.getOrNull(i)?.let { b -> pageOfBlock[b.renderStart]?.let(onPageIndexChange) }
            }
    }

    // Auto-scroll (#126): a steady creep down the page, in lines per second. Driven off frame
    // timestamps rather than a fixed delay so the speed is the same on a 60 Hz and a 120 Hz panel.
    if (aidsRunning && prefs.autoScrollSpeed > 0f) {
        val density = LocalDensity.current
        LaunchedEffect(aidsRunning, prefs.autoScrollSpeed, typography) {
            val pxPerSecond = with(density) {
                (typography.baseSizeSp * typography.lineHeightMultiplier).sp.toPx()
            } * prefs.autoScrollSpeed
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1_000_000_000f
                last = now
                listState.scrollBy(pxPerSecond * dt)
            }
        }
    }

    ReaderScrollView(
        blocks = blocks,
        typography = typography,
        listState = listState,
        contentPadding = contentPadding,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) },
    )
}

/**
 * Tap zones (#64–67), swipe (#68) and the brightness edge-swipe (#47), arbitrated in one place so
 * they cannot fight each other: the vertical drag detector claims the left edge for brightness
 * before the horizontal one sees it, and every gesture is individually switchable off.
 */
private fun Modifier.readerGestures(
    prefs: ReaderPrefs,
    onTurn: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onBrightnessDrag: (Float) -> Unit,
): Modifier = this
    .pointerInput(prefs.tapToTurn, prefs.tapZonePct, prefs.swapTapSides, prefs.fullscreenTapTurns) {
        detectTapGestures(onTap = { offset ->
            if (!prefs.tapToTurn) { onToggleChrome(); return@detectTapGestures }
            if (prefs.fullscreenTapTurns) { onTurn(1); return@detectTapGestures }
            val zone = size.width * (prefs.tapZonePct.coerceIn(5, 50) / 100f)
            val back = if (prefs.swapTapSides) 1 else -1
            when {
                offset.x < zone -> onTurn(back)
                offset.x > size.width - zone -> onTurn(-back)
                else -> onToggleChrome()
            }
        })
    }
    .pointerInput(prefs.brightnessGesture) {
        if (!prefs.brightnessGesture) return@pointerInput
        var active = false
        detectVerticalDragGestures(
            onDragStart = { active = it.x < size.width * 0.15f },
            onDragEnd = { active = false },
            onDragCancel = { active = false },
            onVerticalDrag = { _, dy ->
                // Up is brighter. One full screen height is the whole 0..1 range.
                if (active) onBrightnessDrag(-dy / size.height)
            }
        )
    }
    .pointerInput(prefs.swipeToTurn) {
        if (!prefs.swipeToTurn) return@pointerInput
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragEnd = {
                val threshold = size.width * 0.15f
                if (abs(total) > threshold) onTurn(if (total < 0) 1 else -1)
            },
            onHorizontalDrag = { _, dx -> total += dx }
        )
    }

/** The page-turn animations (#11). `PUSH` moves the outgoing page too; `SLIDE` lets the incoming
 *  page ride over a stationary one, which is what most readers mean by a page turn. */
private fun pageTurnTransition(style: ReaderPageTurn, forward: Boolean, animate: Boolean) =
    if (!animate) {
        (fadeIn(tween(0)) togetherWith fadeOut(tween(0)))
    } else when (style) {
        ReaderPageTurn.FADE ->
            fadeIn(tween(220)) togetherWith fadeOut(tween(220))
        ReaderPageTurn.PUSH ->
            (slideInHorizontally(tween(260)) { if (forward) it else -it } togetherWith
                slideOutHorizontally(tween(260)) { if (forward) -it else it })
        else ->
            (slideInHorizontally(tween(260)) { if (forward) it else -it } togetherWith
                fadeOut(tween(260)))
    }

@Composable
private fun ReadingRuler(
    lineHeightDp: Dp,
    lines: Int,
    opacity: Float,
    dark: Boolean,
    topInset: Dp,
    height: Dp,
) {
    val density = LocalDensity.current
    val bandHeight = lineHeightDp * lines.coerceIn(1, 12)
    val maxOffset = with(density) { (height - bandHeight).coerceAtLeast(0.dp).toPx() }
    var offsetPx by remember { mutableStateOf(maxOffset / 3f) }
    val tint = if (dark) Color.White else Color.Black

    Box(
        Modifier
            .padding(top = topInset)
            .offset { IntOffset(0, offsetPx.roundToInt()) }
            .fillMaxWidth()
            .height(bandHeight)
            .zIndex(1f)
            .background(tint.copy(alpha = opacity.coerceIn(0f, 0.6f)))
            .pointerInput(maxOffset) {
                detectVerticalDragGestures { _, dy ->
                    offsetPx = (offsetPx + dy).coerceIn(0f, maxOffset)
                }
            }
    )
}
