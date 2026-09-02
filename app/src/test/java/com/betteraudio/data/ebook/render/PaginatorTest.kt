package com.betteraudio.data.ebook.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {

    private fun block(text: String, index: Int = 0) =
        RenderBlock(index, "p", 1, null, BlockKind.PARAGRAPH, renderStart = 0, text = text, spans = emptyList())

    /** Fake measurer: height = text length (independent of width), for deterministic arithmetic. */
    private val fakeMeasurer = BlockMeasurer { b, _ -> b.text.length }

    @Test
    fun `empty input yields no pages`() {
        assertEquals(emptyList<Page>(), Paginator.paginate(emptyList(), fakeMeasurer, 100, 100))
    }

    @Test
    fun `blocks that fit stay on one page`() {
        val blocks = listOf(block("a".repeat(10)), block("b".repeat(10)))
        val pages = Paginator.paginate(blocks, fakeMeasurer, 100, 100)
        assertEquals(1, pages.size)
        assertEquals(2, pages[0].blocks.size)
    }

    @Test
    fun `overflow starts a new page`() {
        val blocks = listOf(block("a".repeat(60)), block("b".repeat(60)))
        val pages = Paginator.paginate(blocks, fakeMeasurer, 100, 100)
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].blocks.size)
        assertEquals(1, pages[1].blocks.size)
    }

    @Test
    fun `a single block taller than the viewport gets a page to itself rather than an infinite loop`() {
        val blocks = listOf(block("x".repeat(500)))
        val pages = Paginator.paginate(blocks, fakeMeasurer, 100, 100)
        assertEquals(1, pages.size)
        assertEquals(1, pages[0].blocks.size)
    }

    @Test
    fun `every block appears exactly once across all pages, in order`() {
        val blocks = (1..20).map { block("x".repeat(it * 7), it) }
        val pages = Paginator.paginate(blocks, fakeMeasurer, 100, 50)
        val flattened = pages.flatMap { it.blocks }
        assertEquals(blocks.map { it.docIndex }, flattened.map { it.docIndex })
    }

    @Test
    fun `no page exceeds the viewport height unless a single block alone already does`() {
        val blocks = (1..15).map { block("x".repeat(15), it) }
        val pages = Paginator.paginate(blocks, fakeMeasurer, 100, 50)
        for (page in pages) {
            val total = page.blocks.sumOf { fakeMeasurer.measure(it, 100) }
            assertTrue(total <= 50 || page.blocks.size == 1)
        }
    }
}
