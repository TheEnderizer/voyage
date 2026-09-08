package com.betteraudio.ui.reader.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.betteraudio.data.ebook.render.BlockMeasurer
import com.betteraudio.data.ebook.render.Page
import com.betteraudio.data.ebook.render.RenderBlock

/**
 * A [BlockMeasurer] backed by the real Compose `TextMeasurer` — the production seam
 * [com.betteraudio.data.ebook.render.Paginator] is designed around. Phase 0's spike measured
 * `TextMeasurer.measure()` running safely off the main thread; this is the same call, reused here
 * rather than re-proven.
 *
 * The measured height **includes the block's own bottom gap** ([blockSpacingSp]) because the
 * renderer draws that gap too. Measuring bare text height while rendering text + padding is what
 * makes a page quietly overflow by one paragraph's worth of gaps — the taller the paragraph
 * spacing setting, the worse it gets, so this matters much more now that the gap is user-set.
 */
fun textMeasurerBlockMeasurer(
    measurer: TextMeasurer,
    style: ReaderTypography,
    spacingPxFor: (RenderBlock) -> Int,
): BlockMeasurer =
    BlockMeasurer { block, widthPx ->
        measurer.measure(
            text = block.toAnnotatedString(style.wordSpacingEm),
            style = blockTextStyle(block, style),
            constraints = Constraints(maxWidth = widthPx)
        ).size.height + spacingPxFor(block)
    }

/**
 * A paragraph's highlight tint, keyed by the block's own `renderStart`.
 *
 * Blocks, not character ranges. A highlight anchored to arbitrary offsets would have to be
 * re-resolved against the layout every time the text reflows — and this renderer reflows on font,
 * size, spacing, margins, columns and rotation. A block's `renderStart` is a property of the
 * *document*, so it survives all of that untouched, and "the paragraph you tapped" is what the
 * highlight actually means to the reader anyway.
 */
typealias HighlightTints = Map<Int, Int>

/**
 * The one paragraph to flash right now, and the colour to flash it in.
 *
 * This is the answer to "where did that just put me?" for the two commands that cross between
 * listening and reading — the reader's "Listen from here" and the player's "Read from here". Both
 * used to land silently: the text moved, and the reader was left to work out for themselves which
 * paragraph the jump had actually chosen. A brief halo says it in the one place the answer means
 * anything, on the paragraph itself.
 *
 * Addressed exactly the way a highlight is — spine index plus the block's own `renderStart` — so
 * it survives reflow and means the same thing in paged and continuous mode. Transient state, held
 * by the ViewModel only long enough to play: see `EbookReaderViewModel.flashParagraph`.
 */
data class ParagraphGlow(val spineIndex: Int, val renderStart: Int, val color: Color)

/**
 * One column of text: [Page.blocks] stacked top-to-bottom via composable `BasicText` nodes inside
 * a plain custom [Layout] — plan C.4's chosen approach (composable blocks, not a bare `Canvas`).
 * Wrapped in [SelectionContainer] so text selection is framework-provided, not hand-built.
 *
 * With a multi-column page (#7) the screen composes one of these per column; a "page" in the
 * paginator's sense is a single column's worth of blocks either way.
 *
 * Known, documented gaps versus the full plan (not hidden): no clip-and-offset handling for a
 * block that would have split across a page boundary (C.4) — the paginator never splits a block,
 * so the gap doesn't yet manifest, but the mechanism isn't built. Cross-page selection is not
 * addressed (only this page's blocks are composed). Per-block `semantics`/`GetTextLayoutResult`
 * for accessibility (C.7, Phase 4) is not yet added.
 */
@Composable
fun ReaderPageView(
    page: Page,
    typography: ReaderTypography,
    modifier: Modifier = Modifier,
    /**
     * Whether this page should take all the height it is offered.
     *
     * True for the reader itself: a page IS the viewport, and text has to sit at the top of a
     * full-height page rather than a box that shrink-wraps the paragraph.
     *
     * False for the reading-settings preview, and it must be an explicit switch rather than
     * something inferred from the constraints. The old code inferred it — "bounded height means
     * fill it" — which is true of the reader and false of a preview sitting in a plain Column: a
     * Column hands its non-weighted children a bounded height, so the preview claimed the entire
     * screen and pushed the scope row, the six tabs and every control below it off the bottom. The
     * settings screen opened, looked like a page of sample text, and had no settings on it at all.
     * Bounded-ness says how much room there is, never what the caller wants done with it.
     */
    fillHeight: Boolean = true,
    highlights: HighlightTints = emptyMap(),
    /** Non-null puts the page in highlight mode: paragraphs become tappable. */
    onBlockTap: ((RenderBlock) -> Unit)? = null,
    /** `renderStart` of the paragraph to flash (see [ParagraphGlow]) — already scoped to this
     *  page's own spine item by the caller, since a page only ever holds one chapter. */
    glowAt: Int? = null,
    glowColor: Color = Color.Unspecified,
) {
    // Selection is suspended while highlighting. Both want a press on a paragraph, and a
    // SelectionContainer wrapped around tappable text swallows the tap into a selection handle
    // often enough that the mode feels broken — so only one of them is live at a time.
    SelectionOrPlain(enabled = onBlockTap == null) {
        Layout(
            modifier = modifier,
            content = {
                page.blocks.forEach {
                    BlockText(
                        it, typography, highlights[it.renderStart], onBlockTap,
                        glowing = glowAt != null && it.renderStart == glowAt,
                        glowColor = glowColor
                    )
                }
            }
        ) { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }
            val contentHeight = placeables.sumOf { it.height }
            // Wrapping is also forced when the incoming height is UNBOUNDED, whatever the caller
            // asked for: laying out at Constraints.Infinity crashes downstream ("Size(w x
            // 2147483647) is out of range"), found on-device, not in review.
            val height = when {
                fillHeight && constraints.hasBoundedHeight -> constraints.maxHeight
                constraints.hasBoundedHeight -> contentHeight.coerceAtMost(constraints.maxHeight)
                else -> contentHeight
            }
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeables.maxOfOrNull { it.width } ?: 0
            layout(width, height) {
                var y = 0
                placeables.forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height
                }
            }
        }
    }
}

/**
 * Scrolled (continuous) mode — inventory #2. The same blocks and the same per-block styles as the
 * paged path, just stacked in a `LazyColumn` instead of being cut into pages, so switching modes
 * is a render choice and never a re-parse.
 *
 * Pagination still runs underneath in this mode: the caller keeps using page indices for position
 * persistence and the progress scrubber, and maps the first visible block back onto its page. That
 * keeps one position model for both modes rather than a second, scroll-only one.
 */
@Composable
fun ReaderScrollView(
    /** Every block on screen, each tagged with the chapter it came from. */
    entries: List<ScrollEntry>,
    typography: ReaderTypography,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    /** Paragraph tints per spine index — several chapters are on screen at once here. */
    highlightsBySpine: Map<Int, HighlightTints> = emptyMap(),
    onBlockTap: ((spineIndex: Int, block: RenderBlock) -> Unit)? = null,
    /** The paragraph to flash — spine-qualified, since several chapters are resident here. */
    glow: ParagraphGlow? = null,
) {
    SelectionOrPlain(enabled = onBlockTap == null) {
        LazyColumn(modifier = modifier, state = listState, contentPadding = contentPadding) {
            // The key spans the chapter as well as the offset. `renderStart` is only unique within
            // one spine item, so keying on it alone would collide the moment two chapters are
            // resident — and a LazyColumn with duplicate keys throws. The composite key is also
            // what holds the scroll position still when the window rotates: Compose re-finds the
            // first visible item by key after the list changes, so dropping a chapter off the top
            // and adding one at the bottom moves nothing on screen.
            //
            // Built by concatenation, not string interpolation, and that is not a style choice:
            // this line previously read `"${'$'}{it.spineIndex}:..."`, which Kotlin compiles to a
            // literal `$` followed by braces — one constant key, identical for every paragraph in
            // the book. The LazyColumn threw `Key "..." was already used` on the first frame of
            // continuous mode, i.e. the crash was "opening an EPUB". Concatenation has no `$` in
            // it to be escaped, so the same slip cannot come back here.
            items(entries, key = { it.spineIndex.toString() + ":" + it.block.renderStart }) { entry ->
                BlockText(
                    entry.block, typography,
                    highlightsBySpine[entry.spineIndex]?.get(entry.block.renderStart),
                    onBlockTap?.let { tap -> { block: RenderBlock -> tap(entry.spineIndex, block) } },
                    glowing = glow != null &&
                        glow.spineIndex == entry.spineIndex &&
                        glow.renderStart == entry.block.renderStart,
                    glowColor = glow?.color ?: Color.Unspecified
                )
            }
        }
    }
}

/** One paragraph in the continuous reader, and the chapter it belongs to. */
data class ScrollEntry(val spineIndex: Int, val block: RenderBlock)

/** [SelectionContainer], or nothing at all — the wrapper cannot be toggled by a parameter, and
 *  wrapping conditionally at the call site would swap the subtree's identity and reset the scroll
 *  position every time highlight mode is entered or left. */
@Composable
private fun SelectionOrPlain(enabled: Boolean, content: @Composable () -> Unit) {
    if (enabled) SelectionContainer { content() } else content()
}

@Composable
private fun BlockText(
    block: RenderBlock,
    typography: ReaderTypography,
    tint: Int?,
    onTap: ((RenderBlock) -> Unit)?,
    glowing: Boolean = false,
    glowColor: Color = Color.Unspecified,
) {
    // The tint goes on an inner box, inside the block's bottom gap, so a highlight ends with the
    // paragraph instead of bleeding down into the white space before the next one.
    val spacing = blockSpacingSp(block, typography).dp

    // The sync flash (see [ParagraphGlow]): up fast, out over about a second, then gone. An
    // Animatable rather than `animateFloatAsState` because this is a one-shot *pulse* — there is
    // no steady "on" value to animate towards, and the fade has to start of its own accord rather
    // than wait for something to switch the flag back off.
    val glowAlpha = remember { Animatable(0f) }
    LaunchedEffect(glowing) {
        if (!glowing) { glowAlpha.snapTo(0f); return@LaunchedEffect }
        glowAlpha.snapTo(0f)
        glowAlpha.animateTo(1f, tween(200))
        glowAlpha.animateTo(0f, tween(750))
    }

    Box(Modifier.padding(bottom = spacing)) {
        BasicText(
            text = block.toAnnotatedString(typography.wordSpacingEm),
            style = blockTextStyle(block, typography),
            modifier = Modifier
                // Behind everything, and deliberately not a `background()`: a background stops at
                // the paragraph's own box, and the whole point of a glow is that it spills a
                // little past the text. A draw modifier isn't clipped to its bounds, so the halo
                // can sit outside them. Three inflated rounded rects with falling alpha stand in
                // for a real blur, which would want a RenderEffect (API 31+) and a layer per
                // paragraph to buy the same few pixels of softness.
                .then(
                    if (glowColor.isSpecified) Modifier.drawBehind {
                        val a = glowAlpha.value
                        if (a <= 0f) return@drawBehind
                        val radius = CornerRadius(8.dp.toPx())
                        listOf(10.dp to 0.05f, 6.dp to 0.09f, 2.dp to 0.15f).forEach { (inset, peak) ->
                            val o = inset.toPx()
                            drawRoundRect(
                                color = glowColor.copy(alpha = peak * a),
                                topLeft = Offset(-o, -o),
                                size = Size(size.width + o * 2, size.height + o * 2),
                                cornerRadius = radius
                            )
                        }
                    } else Modifier
                )
                .then(
                    if (tint != null) Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(tint))
                    else Modifier
                )
                .then(if (onTap != null) Modifier.clickable { onTap(block) } else Modifier)
                .padding(horizontal = if (tint != null) 3.dp else 0.dp)
        )
    }
}

/** Convenience for a host composable that wants a ready-to-use [BlockMeasurer] from the current
 *  composition's [TextMeasurer], with the same block spacing the renderer applies. */
@Composable
fun rememberBlockMeasurer(typography: ReaderTypography): BlockMeasurer {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return textMeasurerBlockMeasurer(measurer, typography) { block ->
        with(density) { blockSpacingSp(block, typography).dp.toPx() }.toInt()
    }
}
