package com.betteraudio.data.ebook.render

/**
 * Block stacking + page breaking (plan C.2 `BlockLayout` + `Paginator`, Phase 1 item 6). A pure
 * function of its input plus a measurer and a viewport, which is what makes it unit-testable
 * without Compose — [BlockMeasurer] is the seam: production code backs it with `TextMeasurer`
 * (Phase 0's spike proved that's safe off the main thread), tests back it with a fake.
 *
 * Scope for Phase 1 (novel-grade): vertical block stacking only, one page = the fixed rectangle
 * `viewportHeightPx`. Widow/orphan avoidance and the clip-and-offset split mechanism for a block
 * that doesn't fit a page (C.4) are follow-up work on top of this pass, not yet implemented here —
 * this version never splits a block: a block taller than the viewport gets a page to itself.
 */
fun interface BlockMeasurer {
    /** Measured height in px of [block] laid out at [widthPx]. */
    fun measure(block: RenderBlock, widthPx: Int): Int
}

data class Page(val blocks: List<RenderBlock>)

object Paginator {

    fun paginate(blocks: List<RenderBlock>, measurer: BlockMeasurer, viewportWidthPx: Int, viewportHeightPx: Int): List<Page> {
        if (blocks.isEmpty()) return emptyList()
        val pages = ArrayList<Page>()
        var current = ArrayList<RenderBlock>()
        var heightUsed = 0
        for (block in blocks) {
            val h = measurer.measure(block, viewportWidthPx)
            if (heightUsed + h > viewportHeightPx && current.isNotEmpty()) {
                pages.add(Page(current))
                current = ArrayList()
                heightUsed = 0
            }
            current.add(block)
            heightUsed += h
        }
        if (current.isNotEmpty()) pages.add(Page(current))
        return pages
    }
}
