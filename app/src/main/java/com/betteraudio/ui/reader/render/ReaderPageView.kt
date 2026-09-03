package com.betteraudio.ui.reader.render

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
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
fun ReaderPageView(page: Page, typography: ReaderTypography, modifier: Modifier = Modifier) {
    SelectionContainer {
        Layout(
            modifier = modifier,
            content = { page.blocks.forEach { BlockText(it, typography) } }
        ) { measurables, constraints ->
            val loose = constraints.copy(minHeight = 0)
            val placeables = measurables.map { it.measure(loose) }
            val contentHeight = placeables.sumOf { it.height }
            // Real reader screen: constraints.maxHeight is the bounded page viewport, and content
            // is expected to fill it. A caller measuring this with an UNBOUNDED height instead
            // (the reading-settings live preview, inside a scrollable Column) hands
            // constraints.maxHeight = Constraints.Infinity — laying out at that height crashes
            // downstream ("Size(w x 2147483647) is out of range"), found on-device, not in review.
            // Wrap to the actual content height whenever the incoming height isn't bounded.
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else contentHeight
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
    blocks: List<RenderBlock>,
    typography: ReaderTypography,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        LazyColumn(modifier = modifier, state = listState, contentPadding = contentPadding) {
            items(blocks, key = { it.renderStart }) { block -> BlockText(block, typography) }
        }
    }
}

@Composable
private fun BlockText(block: RenderBlock, typography: ReaderTypography) {
    BasicText(
        text = block.toAnnotatedString(typography.wordSpacingEm),
        style = blockTextStyle(block, typography),
        modifier = Modifier.padding(bottom = blockSpacingSp(block, typography).dp)
    )
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
