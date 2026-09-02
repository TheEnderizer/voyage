package com.betteraudio.ui.reader.render

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
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
 */
fun textMeasurerBlockMeasurer(measurer: TextMeasurer, style: ReaderTypography): BlockMeasurer =
    BlockMeasurer { block, widthPx ->
        measurer.measure(
            text = block.toAnnotatedString(),
            style = blockTextStyle(block, style),
            constraints = Constraints(maxWidth = widthPx)
        ).size.height
    }

/**
 * One page: [Page.blocks] stacked top-to-bottom via composable `BasicText` nodes inside a plain
 * custom [Layout] — plan C.4's chosen approach (composable blocks, not a bare `Canvas`). Wrapped in
 * [SelectionContainer] using its public constructor overload (verified against this project's
 * Compose BOM in C.4) so text selection is framework-provided, not hand-built.
 *
 * Known, documented gaps versus the full plan (not hidden): no clip-and-offset handling for a block
 * that would have split across a page boundary (C.4) — this vertical slice's [Paginator][com.
 * betteraudio.data.ebook.render.Paginator] never splits a block, so the gap doesn't yet manifest,
 * but the mechanism itself isn't built. Cross-page selection is not addressed (only this page's
 * blocks are composed). Per-block `semantics`/`GetTextLayoutResult` for accessibility (C.7,
 * Phase 4) is not yet added.
 */
@Composable
fun ReaderPageView(page: Page, typography: ReaderTypography, modifier: Modifier = Modifier) {
    SelectionContainer {
        Layout(
            modifier = modifier,
            content = {
                page.blocks.forEach { block ->
                    BasicText(
                        text = block.toAnnotatedString(),
                        style = blockTextStyle(block, typography),
                        modifier = Modifier.padding(bottom = blockSpacing(block))
                    )
                }
            }
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

private fun blockSpacing(block: RenderBlock) =
    if (block.kind == com.betteraudio.data.ebook.render.BlockKind.HEADING) 16.dp else 12.dp

/** Convenience for a host composable that wants a ready-to-use [BlockMeasurer] from the current
 *  composition's [TextMeasurer], mirroring how [Paginator][com.betteraudio.data.ebook.render.
 *  Paginator] is meant to be driven from a real screen. */
@Composable
fun rememberBlockMeasurer(typography: ReaderTypography): BlockMeasurer {
    val measurer = rememberTextMeasurer()
    return textMeasurerBlockMeasurer(measurer, typography)
}
