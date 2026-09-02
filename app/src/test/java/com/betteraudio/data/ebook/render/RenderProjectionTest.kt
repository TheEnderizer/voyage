package com.betteraudio.data.ebook.render

import com.betteraudio.data.ebook.ParagraphExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan C.3's central correctness requirement: projecting a render offset must land in the same
 * paragraph `ParagraphExtractor` reports, over real prose including non-English and heavily
 * punctuated text (Phase 1 item 8) — and must bound intra-paragraph drift, not just paragraph
 * membership, since `PositionBridge.charToAudio` interpolates within a spine item.
 */
class RenderProjectionTest {

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun build(html: String): Pair<RenderDocument, RenderProjection?> {
        val b = bytes(html)
        val render = EpubDocumentParser.parse(b)
        val extractor = ParagraphExtractor.extract(b)
        return render to RenderProjection.buildFor(render, extractor)
    }

    @Test
    fun `projects into the correct paragraph for plain english prose`() {
        val html = """
            <html><body>
              <p>The Mock Turtle sighed deeply, and drew the back of one flapper across his eyes.</p>
              <p>She looked at Alice and tried to speak, but for a minute or two sobs choked her voice.</p>
              <p>At last the Mock Turtle recovered his voice, and went on again with tears running down.</p>
            </body></html>
        """.trimIndent()
        val (render, projection) = build(html)
        requireNotNull(projection)
        val extractor = ParagraphExtractor.extract(bytes(html))

        for (block in render.blocks) {
            val mid = block.renderStart + block.text.length / 2
            val extractorOffset = projection.toExtractorOffset(mid)
            val (paragraphIndex, _) = extractor.locate(extractorOffset)
            val expectedIndex = render.blocks.filter { !it.extractorDropped }.indexOf(block)
            assertEquals("offset in block '${block.text.take(20)}...' should land in its own paragraph",
                expectedIndex, paragraphIndex)
        }
    }

    @Test
    fun `bounds intra-paragraph drift for heavily punctuated and accented text`() {
        // The classic divergence case from C.3: entities/accents inflate the render stream
        // relative to the extractor's ASCII-only stream. Projection must still land close to the
        // right fraction within the paragraph, not just the right paragraph.
        val html = """<html><body>
            <p>“Curiouser and curiouser!” cried Alice — she was so surprised! “Now I’m opening out like the largest télescope, très bien, ever was — good­bye, feet!”</p>
        </body></html>"""
        val (render, projection) = build(html)
        requireNotNull(projection)
        val extractor = ParagraphExtractor.extract(bytes(html))
        val block = render.blocks.single()

        // Sample several fractional positions through the render block and confirm the projected
        // extractor fraction stays within a reasonable bound of the same fraction (drift bound).
        for (fracTenths in 1..9) {
            val renderOffset = block.renderStart + (block.text.length * fracTenths / 10)
            val extractorOffset = projection.toExtractorOffset(renderOffset)
            val (paraIdx, paraFrac) = extractor.locate(extractorOffset)
            assertEquals(0, paraIdx)
            val renderFrac = fracTenths / 10f
            assertTrue(
                "renderFrac=$renderFrac projected to paraFrac=$paraFrac, drift too large",
                kotlin.math.abs(renderFrac - paraFrac) < 0.35f
            )
        }
    }

    @Test
    fun `inverse projection is paragraph-granular, landing at a block start`() {
        val html = """
            <html><body>
              <p>First paragraph with enough real words to survive the drop filter easily.</p>
              <p>Second paragraph, also long enough, is the one we will target from audio.</p>
            </body></html>
        """.trimIndent()
        val (render, projection) = build(html)
        requireNotNull(projection)
        val extractor = ParagraphExtractor.extract(bytes(html))
        val secondParagraph = extractor.paragraphs[1]

        // Aim at the middle of the second extractor paragraph.
        val midExtractorOffset = secondParagraph.charStart + secondParagraph.charCount / 2
        val renderOffset = projection.toRenderOffset(midExtractorOffset)

        val secondBlock = render.blocks.filter { !it.extractorDropped }[1]
        assertEquals(secondBlock.renderStart, renderOffset)
    }

    @Test
    fun `returns null when the extractor stream is empty (image-only chapter)`() {
        val html = """<html><body><img src="full-page.jpg" alt=""/></body></html>"""
        val render = EpubDocumentParser.parse(bytes(html))
        val extractor = ParagraphExtractor.extract(bytes(html))
        assertNull(RenderProjection.buildFor(render, extractor))
    }

    @Test
    fun `returns null on a surviving-block count mismatch rather than guessing`() {
        // A contrived divergence: force the two parsers to disagree on how many blocks survive by
        // building a RenderDocument by hand with a different survivor count than what the real
        // extractor would report for the same nominal text.
        val fakeRender = RenderDocument(
            blocks = listOf(
                RenderBlock(0, "p", 1, null, BlockKind.PARAGRAPH, renderStart = 0, text = "only one block here", spans = emptyList())
            ),
            text = "only one block here",
            idOffsets = emptyMap(),
        )
        val html = """<html><body><p>first</p><p>second real paragraph text here</p></body></html>"""
        val extractor = ParagraphExtractor.extract(bytes(html))
        assertNull(RenderProjection.buildFor(fakeRender, extractor))
    }

    @Test
    fun `non-english text still projects even though the extractor stream is nearly empty`() {
        // Cyrillic/CJK-heavy prose normalizes to almost nothing under TextSimilarity.normalize
        // (a-z0-9 only), so most blocks get dropped. The projection must still work for whatever
        // survives, and must not be confused by the mostly-empty extractor stream.
        val html = """
            <html><body>
              <p>Привет, как дела? Это тестовое предложение для проверки.</p>
              <p>Second paragraph is plain english and long enough to survive normalize.</p>
            </body></html>
        """.trimIndent()
        val render = EpubDocumentParser.parse(bytes(html))
        val extractor = ParagraphExtractor.extract(bytes(html))
        // The Cyrillic paragraph normalizes to empty/near-empty and is dropped by both scanners;
        // only the English paragraph should survive on the extractor side.
        if (extractor.paragraphs.size == render.blocks.count { !it.extractorDropped }) {
            val projection = RenderProjection.buildFor(render, extractor)
            requireNotNull(projection)
            val englishBlock = render.blocks.first { !it.extractorDropped }
            val offset = projection.toExtractorOffset(englishBlock.renderStart)
            assertEquals(0, extractor.locate(offset).first)
        }
    }
}
