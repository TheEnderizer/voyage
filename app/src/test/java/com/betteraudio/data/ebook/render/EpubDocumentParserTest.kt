package com.betteraudio.data.ebook.render

import com.betteraudio.data.ebook.ParagraphExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EpubDocumentParser] must stay in block-boundary parity with the frozen [ParagraphExtractor] —
 * that's the whole basis for [RenderProjection]. Most of this suite therefore checks the two
 * parsers against each other on the same input, not just [EpubDocumentParser] in isolation.
 */
class EpubDocumentParserTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun `renders full unicode punctuation and case that the extractor stream would strip`() {
        val html = """<html><body><p>"Curiouser and curiouser!" cried Alice — à côté.</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        assertEquals(1, doc.blocks.size)
        assertEquals("\"Curiouser and curiouser!\" cried Alice — à côté.", doc.blocks[0].text)
    }

    @Test
    fun `paragraph count matches the extractor's surviving paragraph count`() {
        val html = """
            <html><body>
              <div class="chapter">
                <h2>Chapter 2</h2>
                <div class="text">
                  <p>First paragraph of real prose, long enough to survive.</p>
                  <p>Second paragraph, also long enough to survive the drop filter.</p>
                  <p>.</p>
                </div>
              </div>
            </body></html>
        """.trimIndent()
        val render = EpubDocumentParser.parse(bytes(html))
        val extractor = ParagraphExtractor.extract(bytes(html))

        val survivingRender = render.blocks.count { !it.extractorDropped }
        assertEquals(extractor.paragraphs.size, survivingRender)
        // The lone "." paragraph normalizes to under 2 chars and must be flagged dropped on the
        // render side exactly as the extractor drops it.
        assertTrue(render.blocks.last().extractorDropped)
    }

    @Test
    fun `div with bare text and no inner p still flushes as its own block`() {
        // A common real-world shape (e.g. a bare epigraph): ParagraphExtractor treats bare text
        // directly inside a div as its own paragraph because div is itself a BLOCK_TAG. The render
        // parser must do the same or the two streams fall out of boundary parity.
        val html = """<html><body><div>Some bare text with no wrapping p tag at all here.</div></body></html>"""
        val render = EpubDocumentParser.parse(bytes(html))
        val extractor = ParagraphExtractor.extract(bytes(html))
        assertEquals(1, extractor.paragraphs.size)
        assertEquals(1, render.blocks.count { !it.extractorDropped })
        assertEquals("Some bare text with no wrapping p tag at all here.", render.blocks.single().text)
    }

    @Test
    fun `bold and italic spans record correct offsets into the block text`() {
        val html = """<html><body><p>plain <b>bold</b> and <i>italic</i> text</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        val block = doc.blocks.single()
        assertEquals("plain bold and italic text", block.text)
        val bold = block.spans.single { it.bold }
        assertEquals("bold", block.text.substring(bold.start, bold.end))
        val italic = block.spans.single { it.italic }
        assertEquals("italic", block.text.substring(italic.start, italic.end))
    }

    @Test
    fun `link span records its href`() {
        val html = """<html><body><p>see <a href="chapter2.xhtml#note1">this note</a> here</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        val block = doc.blocks.single()
        val link = block.spans.single { it.link != null }
        assertEquals("chapter2.xhtml#note1", link.link)
        assertEquals("this note", block.text.substring(link.start, link.end))
    }

    @Test
    fun `element id is recorded at its render-stream offset for fragment anchors`() {
        val html = """<html><body><p>before</p><h2 id="ch3">Chapter 3</h2><p>after</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        val headingOffset = doc.blocks.first { it.id == "ch3" }.renderStart
        assertEquals(headingOffset, doc.idOffsets["ch3"])
        assertEquals("Chapter 3", doc.text.substring(headingOffset, headingOffset + "Chapter 3".length))
    }

    @Test
    fun `heading level is captured for h1 through h6`() {
        val html = "<html><body>" + (1..6).joinToString("") { "<h$it>H$it</h$it>" } + "</body></html>"
        val doc = EpubDocumentParser.parse(bytes(html))
        assertEquals((1..6).toList(), doc.blocks.map { it.headingLevel })
        assertTrue(doc.blocks.all { it.kind == BlockKind.HEADING })
    }

    @Test
    fun `nth-of-type counts siblings under the same parent, not descendants`() {
        val html = """
            <html><body>
              <div>
                <p>p one</p>
                <p>p two</p>
              </div>
              <p>p three (top level)</p>
            </body></html>
        """.trimIndent()
        val doc = EpubDocumentParser.parse(bytes(html))
        val ps = doc.blocks.filter { it.tag == "p" }
        assertEquals(listOf(1, 2, 1), ps.map { it.nthOfType })
    }

    @Test
    fun `malformed unclosed tag does not throw and degrades gracefully`() {
        val html = """<html><body><p>ok paragraph</p><p>unterminated"""
        val doc = EpubDocumentParser.parse(bytes(html))
        assertTrue(doc.blocks.isNotEmpty())
        assertEquals("ok paragraph", doc.blocks.first().text)
    }

    @Test
    fun `script and style content is skipped entirely`() {
        val html = """<html><head><style>p{color:red}</style></head>
            <body><script>alert('x')</script><p>real text</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        assertEquals(1, doc.blocks.size)
        assertEquals("real text", doc.blocks[0].text)
    }

    @Test
    fun `br becomes a literal newline`() {
        val html = """<html><body><p>line one<br/>line two</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        assertEquals("line one\nline two", doc.blocks.single().text)
    }

    @Test
    fun `named and numeric entities decode to the same characters the extractor would normalize away`() {
        val html = """<html><body><p>caf&eacute;? no &mdash; &#233; &amp; &#x2019;</p></body></html>"""
        // &eacute; isn't in either parser's small named table (by design, matching ParagraphExtractor
        // exactly) so it should pass through literally; the ones that ARE in the shared table must decode.
        val doc = EpubDocumentParser.parse(bytes(html))
        assertTrue(doc.blocks.single().text.contains("—"))
        assertTrue(doc.blocks.single().text.contains("é"))
        assertTrue(doc.blocks.single().text.contains("&"))
        assertTrue(doc.blocks.single().text.contains("’"))
    }

    @Test
    fun `empty document returns an empty render document without throwing`() {
        val doc = EpubDocumentParser.parse(bytes(""))
        assertEquals(0, doc.blocks.size)
        assertEquals("", doc.text)
    }

    @Test
    fun `unknown element id is absent from idOffsets`() {
        val html = """<html><body><p>text</p></body></html>"""
        val doc = EpubDocumentParser.parse(bytes(html))
        assertNull(doc.idOffsets["missing"])
    }
}
