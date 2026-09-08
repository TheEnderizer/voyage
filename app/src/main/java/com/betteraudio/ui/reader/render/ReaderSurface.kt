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
import com.betteraudio.data.ebook.render.RenderBlock
import com.betteraudio.ui.reader.LoadedChapter
import com.betteraudio.ui.reader.ScrollAnchor
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
    onBrightnessSet: (Float) -> Unit,
    /** Continuous mode's resident chapters, in spine order. */
    scrollWindow: List<LoadedChapter>,
    scrollAnchor: ScrollAnchor?,
    /** Continuous mode reporting its position: the chapter and render offset now at the top. */
    onScrolledTo: (spineIndex: Int, renderStart: Int) -> Unit,
    /** Which spine item [pages] belongs to — paged mode marks and highlights are scoped to it. */
    currentSpineIndex: Int,
    /** Reports the usable column size back so the caller can (re)paginate to it. */
    onViewportMeasured: (widthPx: Int, heightPx: Int, columns: Int) -> Unit,
    /** Anything else that invalidates the existing pagination — the chapter, and the typography
     *  that changes how tall each block measures. Without it, a settings change that doesn't alter
     *  the viewport's pixel size (line spacing, paragraph gap, weight, hyphenation) would restyle
     *  the text but keep the old page breaks, silently overflowing or under-filling every page. */
    paginationKey: Any,
    aidsRunning: Boolean,
    /** Paragraph tints per spine index — continuous mode has several chapters on screen. */
    highlightsBySpine: Map<Int, HighlightTints> = emptyMap(),
    /** Non-null arms highlight mode: paragraphs become tappable and page turns stand down. */
    onBlockTap: ((spineIndex: Int, block: RenderBlock) -> Unit)? = null,
    /** The paragraph to flash for a moment — where a listen↔read jump landed. See [ParagraphGlow].
     *  Whichever mode is showing has already been positioned on it by the caller (the page index
     *  in paged mode, the scroll anchor in continuous), so this only has to draw. */
    glow: ParagraphGlow? = null,
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
            ScrolledSurface(
                window = scrollWindow, anchor = scrollAnchor, prefs = prefs,
                typography = typography, contentPadding = contentPadding,
                aidsRunning = aidsRunning, onScrolledTo = onScrolledTo,
                onToggleChrome = onToggleChrome,
                highlightsBySpine = highlightsBySpine, onBlockTap = onBlockTap,
                glow = glow
            )
        } else {
            PagedSurface(
                pages = pages, pageIndex = pageIndex, prefs = prefs, typography = typography,
                columns = columns, columnWidth = columnWidth, gap = gap,
                contentPadding = contentPadding, aidsRunning = aidsRunning,
                onPageIndexChange = onPageIndexChange,
                onPrevChapter = onPrevChapter, onNextChapter = onNextChapter,
                onToggleChrome = onToggleChrome, onBrightnessSet = onBrightnessSet,
                highlights = highlightsBySpine[currentSpineIndex].orEmpty(),
                onBlockTap = onBlockTap?.let { tap -> { block: RenderBlock -> tap(currentSpineIndex, block) } },
                glowAt = glow?.takeIf { it.spineIndex == currentSpineIndex }?.renderStart,
                glowColor = glow?.color ?: Color.Unspecified,
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
    onBrightnessSet: (Float) -> Unit,
    highlights: HighlightTints,
    onBlockTap: ((RenderBlock) -> Unit)?,
    glowAt: Int?,
    glowColor: Color,
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
            // Highlight mode stands the page-turn gestures down entirely. Tapping a paragraph and
            // tapping to turn are the same press in the same place, and leaving both live would
            // mean every attempt to highlight the last paragraph on a page turned it instead.
            // The mode is explicit and visibly on, so there is no state where the reader is left
            // wondering why a tap did nothing.
            .then(
                if (onBlockTap != null) Modifier
                else Modifier.readerGestures(
                    prefs, onTurn = { turn(it) },
                    onToggleChrome = onToggleChrome, onBrightnessSet = onBrightnessSet
                )
            )
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
                        if (page != null) ReaderPageView(
                            page, typography, Modifier.fillMaxSize(),
                            highlights = highlights, onBlockTap = onBlockTap,
                            glowAt = glowAt, glowColor = glowColor
                        )
                    }
                }
            }
        }
    }
}

/**
 * Continuous (scrolled) mode — inventory #2.
 *
 * Renders a *window* of chapters end to end, not one chapter. The previous, current and next spine
 * items are parsed ahead of time by the ViewModel and stacked in one `LazyColumn`, so reading past
 * the end of a chapter is exactly reading past the end of a paragraph: the next one is already
 * there, below the last line, and nothing loads, jumps or flashes at the boundary.
 *
 * Before this, scrolled mode drew a single chapter and simply stopped at its end — the chapter
 * arrows in the footer were the only way on, which meant opening the chrome to press a button
 * every few thousand words. Loading the next chapter *on arrival* would have fixed the dead end
 * but not the seam: the reader would still hit a wall, wait, and be dropped at the top of
 * something new. Continuity is the feature; the preloading is what buys it.
 *
 * Position works the other way round from paged mode. Paged mode owns a page index and the
 * ViewModel follows it; here the list owns the position and reports the chapter and render offset
 * of whatever is at the top, and the ViewModel decides what that means — including that the
 * reader has crossed into another chapter and the window should rotate around them.
 */
@Composable
private fun ScrolledSurface(
    window: List<LoadedChapter>,
    anchor: ScrollAnchor?,
    prefs: ReaderPrefs,
    typography: ReaderTypography,
    contentPadding: PaddingValues,
    aidsRunning: Boolean,
    onScrolledTo: (spineIndex: Int, renderStart: Int) -> Unit,
    onToggleChrome: () -> Unit,
    highlightsBySpine: Map<Int, HighlightTints>,
    onBlockTap: ((spineIndex: Int, block: RenderBlock) -> Unit)?,
    glow: ParagraphGlow?,
) {
    val entries = remember(window) {
        window.flatMap { chapter -> chapter.blocks.map { ScrollEntry(chapter.spineIndex, it) } }
    }
    val listState = rememberLazyListState()

    // Placement, and ONLY on a new instruction. Keyed on the anchor's generation rather than on
    // the window or the entry list: both of those change every time the window rotates under a
    // reader who is simply scrolling, and re-running this then would throw them back to the top of
    // the chapter they had just flowed into. See ScrollAnchor.
    LaunchedEffect(anchor?.generation, entries) {
        val target = anchor ?: return@LaunchedEffect
        if (entries.isEmpty()) return@LaunchedEffect
        val index = entries.indexOfFirst {
            it.spineIndex == target.spineIndex && it.block.renderEnd > target.renderStart
        }
        if (index >= 0) listState.scrollToItem(index)
    }

    LaunchedEffect(listState, entries) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { i ->
                entries.getOrNull(i)?.let { onScrolledTo(it.spineIndex, it.block.renderStart) }
            }
    }

    // Auto-scroll (#126): a steady creep down the page, in lines per second. Driven off frame
    // timestamps rather than a fixed delay so the speed is the same on a 60 Hz and a 120 Hz panel.
    // It now runs straight through a chapter boundary too, which is the first time it has been
    // able to do a whole book unattended.
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

    // "…and make sure it's on the screen." The anchor above normally has this covered — a jump
    // lands on the very paragraph that is about to flash — but a flash can also be raised for a
    // paragraph without a jump ("Listen from here" flashes where the reader already is, reported
    // from the last scroll frame). Scrolls only when the block genuinely isn't on screen, so it
    // can never yank the page away from someone who is looking straight at it.
    LaunchedEffect(glow, entries) {
        val target = glow ?: return@LaunchedEffect
        val index = entries.indexOfFirst {
            it.spineIndex == target.spineIndex && it.block.renderStart == target.renderStart
        }
        if (index < 0) return@LaunchedEffect
        // Let the anchor effect above place the list first — on a jump both fire together, and
        // asking what is visible before that has settled would answer for the previous position.
        delay(120)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            listState.animateScrollToItem(index)
        }
    }

    ReaderScrollView(
        entries = entries,
        typography = typography,
        listState = listState,
        contentPadding = contentPadding,
        highlightsBySpine = highlightsBySpine,
        onBlockTap = onBlockTap,
        glow = glow,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (onBlockTap != null) Modifier
                else Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggleChrome() }) }
            ),
    )
}

/**
 * Tap zones (#64–67), swipe (#68) and the brightness edge-swipe (#47), arbitrated in one place so
 * they cannot fight each other: the vertical drag detector claims the left edge for brightness
 * before the horizontal one sees it, and every gesture is individually switchable off.
 *
 * **Every callback is read through [rememberUpdatedState], and that is load-bearing.** Each
 * `pointerInput` below is keyed on the settings it depends on, so its block starts once and keeps
 * running across every later recomposition — which means it also keeps whatever it captured
 * directly. `onTurn` closes over `PagedSurface.turn`, which closes over the **pages list and the
 * current page index**: capturing it directly froze both at their first-composition values, when
 * pagination has not run yet and `pages` is still empty. With `pages.lastIndex == -1`, every
 * forward turn was `target > lastIndex` → `onNextChapter()` and every backward turn was
 * `target < 0` → `onPrevChapter()`, so a tap or a swipe never turned a page at all — it skipped a
 * whole chapter, in both directions, for the life of the screen. Observed on device as "swipe
 * doesn't work" and "pages don't turn reliably". Re-keying the blocks on `pages` instead would
 * cancel an in-flight drag every time a chapter repaginates; reading the latest lambda keeps the
 * gesture coroutine alive. Same trap, same fix, as `VoyageScrubber`.
 */
@Composable
private fun Modifier.readerGestures(
    prefs: ReaderPrefs,
    onTurn: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onBrightnessSet: (Float) -> Unit,
): Modifier {
    val turn by rememberUpdatedState(onTurn)
    val toggleChrome by rememberUpdatedState(onToggleChrome)
    val setBrightness by rememberUpdatedState(onBrightnessSet)
    // Where the level stands right now, for seeding a drag. -1 means "system brightness"; a drag
    // has to start somewhere, and mid-scale is the only neutral guess.
    val brightnessNow by rememberUpdatedState(
        if (prefs.brightness < 0f) 0.5f else prefs.brightness.coerceIn(0.01f, 1f)
    )
    return this
    .pointerInput(prefs.tapToTurn, prefs.tapZonePct, prefs.swapTapSides, prefs.fullscreenTapTurns) {
        detectTapGestures(
            // The escape hatch, and the reason it is unconditional. The chrome carries the ONLY
            // routes out of the book, into Contents, and into Reading settings — and under
            // `fullscreenTapTurns` every tap turns a page, so the centre-tap branch below is never
            // reached and chrome that is hidden can never be shown again. That setting is itself
            // inside Reading settings, so switching it on used to lock the reader into a state
            // where no setting (including that one) could be changed back. A long press always
            // toggles the chrome, whatever the tap configuration says.
            onLongPress = { toggleChrome() },
            onTap = { offset ->
                if (!prefs.tapToTurn) { toggleChrome(); return@detectTapGestures }
                if (prefs.fullscreenTapTurns) { turn(1); return@detectTapGestures }
                val zone = size.width * (prefs.tapZonePct.coerceIn(5, 50) / 100f)
                val back = if (prefs.swapTapSides) 1 else -1
                when {
                    offset.x < zone -> turn(back)
                    offset.x > size.width - zone -> turn(-back)
                    else -> toggleChrome()
                }
            }
        )
    }
    .pointerInput(prefs.brightnessGesture) {
        if (!prefs.brightnessGesture) return@pointerInput
        var active = false
        // The running level for THIS drag, seeded once when it starts.
        //
        // This used to report a per-frame delta and let the caller add it to `prefs.brightness`,
        // which never worked: the caller's `prefs` is DataStore-backed and arrives asynchronously,
        // so every frame of a drag read the same pre-drag value and added its own single frame's
        // delta to it. A whole swipe collapsed into roughly one frame's worth of change — about
        // half a percent — and the screen visibly did nothing. Accumulating here and emitting an
        // absolute level makes the gesture independent of how fast the write round-trips.
        var level = 0f
        detectVerticalDragGestures(
            onDragStart = {
                active = it.x < size.width * 0.15f
                level = brightnessNow
            },
            onDragEnd = { active = false },
            onDragCancel = { active = false },
            onVerticalDrag = { _, dy ->
                // Up is brighter. One full screen height is the whole 0..1 range.
                if (!active) return@detectVerticalDragGestures
                level = (level - dy / size.height).coerceIn(0.01f, 1f)
                setBrightness(level)
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
                if (abs(total) > threshold) turn(if (total < 0) 1 else -1)
            },
            onHorizontalDrag = { _, dx -> total += dx }
        )
    }
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
