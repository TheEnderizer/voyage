package com.betteraudio.data.ebook.render

import com.betteraudio.data.ebook.SpineParagraphs

/**
 * The projection between the two char streams (plan C.3):
 *  - **render stream** — [RenderDocument.text], full Unicode, owned by the reader.
 *  - **extractor stream** — `ParagraphExtractor`'s normalized ASCII stream, the frozen sync path's
 *    only input.
 *
 * Built by walking [RenderDocument.blocks] and `SpineParagraphs.paragraphs` **in lockstep**, not by
 * re-deriving `ParagraphExtractor`'s normalization. [EpubDocumentParser] flushes a block at exactly
 * the same tag boundaries `ParagraphExtractor` does and tags each block [RenderBlock.extractorDropped]
 * using the identical drop decision on the identical raw text — so the Nth render block that is
 * *not* extractor-dropped and the Nth surviving [SpineParagraphs] paragraph are the same document
 * position, in order, whenever the two scanners agree on where the text came from. Where they
 * disagree (an entity-table gap, an encoding difference), correctness is not assumed — see
 * [buildFor]'s validation and the fallback it leaves for the caller.
 */
class RenderProjection private constructor(
    // Parallel arrays, one entry per surviving (non-dropped) render block, in document order.
    private val renderStarts: IntArray,
    private val renderEnds: IntArray,
    private val extractorStarts: IntArray,
    private val extractorEnds: IntArray,
    private val renderTotalChars: Int,
) {
    /** render offset → extractor offset. Monotone, many-to-one (C.3): several render characters
     *  can map to one extractor character, and a render offset that falls inside a block the
     *  extractor dropped maps to that block's nearest surviving boundary. */
    fun toExtractorOffset(renderOffset: Int): Int {
        if (renderStarts.isEmpty()) return 0
        val clamped = renderOffset.coerceIn(0, renderTotalChars)
        val i = blockIndexForRenderOffset(clamped)
        val block = i.coerceIn(0, renderStarts.lastIndex)
        val rs = renderStarts[block]; val re = renderEnds[block]
        val es = extractorStarts[block]; val ee = extractorEnds[block]
        if (clamped < rs) return es // in the gap before this block — nearest surviving boundary, forward
        val span = (re - rs).coerceAtLeast(1)
        // frac > 1 here means clamped is in the gap *after* this block (inside a dropped block's
        // own range, or trailing whitespace) — coerced to 1f, which snaps to `ee`: nearest
        // surviving boundary, backward. Both directions are "nearest surviving boundary" per C.3;
        // neither is more correct than the other for content that was, definitionally, dropped.
        val frac = ((clamped - rs).toFloat() / span).coerceIn(0f, 1f)
        return (es + frac * (ee - es)).toInt().coerceIn(es, ee)
    }

    /** extractor offset → render offset. **Deliberately paragraph-granular** (C.3): returns the
     *  render-stream start of the block whose extractor range contains (or is nearest to)
     *  [extractorOffset], not an interpolated mid-paragraph point. Opening from audio therefore
     *  lands at a paragraph start — an intentional, documented trade, not an approximation. */
    fun toRenderOffset(extractorOffset: Int): Int {
        if (extractorStarts.isEmpty()) return 0
        var lo = 0; var hi = extractorStarts.lastIndex; var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            when {
                extractorOffset < extractorStarts[mid] -> hi = mid - 1
                extractorOffset >= extractorEnds[mid] && mid < extractorStarts.lastIndex -> lo = mid + 1
                else -> { idx = mid; break }
            }
            idx = mid
        }
        return renderStarts[idx]
    }

    private fun blockIndexForRenderOffset(offset: Int): Int {
        var lo = 0; var hi = renderStarts.lastIndex; var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            when {
                offset < renderStarts[mid] -> hi = mid - 1
                offset >= renderEnds[mid] && mid < renderStarts.lastIndex -> lo = mid + 1
                else -> { idx = mid; break }
            }
            idx = mid
        }
        return idx
    }

    companion object {
        /**
         * Builds the projection for one spine item. Returns null when alignment cannot be trusted
         * — an empty extractor stream (image-only chapter, or a non-English chapter whose blocks
         * are mostly dropped by the `< 2` rule — C.3's "empty-extractor case"), or a surviving-block
         * count mismatch between the two scanners (the drift the plan warns about: an entity-table
         * gap or encoding divergence). Callers must fall back to `charOffsetForFraction` rather
         * than trust a confidently wrong offset — this class does not guess.
         */
        fun buildFor(render: RenderDocument, extractor: SpineParagraphs): RenderProjection? {
            if (extractor.totalChars == 0 || extractor.paragraphs.isEmpty()) return null
            val surviving = render.blocks.filter { !it.extractorDropped }
            if (surviving.size != extractor.paragraphs.size) return null

            val n = surviving.size
            val renderStarts = IntArray(n); val renderEnds = IntArray(n)
            val extractorStarts = IntArray(n); val extractorEnds = IntArray(n)
            for (i in 0 until n) {
                val b = surviving[i]
                renderStarts[i] = b.renderStart
                renderEnds[i] = b.renderEnd
                val p = extractor.paragraphs[i]
                extractorStarts[i] = p.charStart
                extractorEnds[i] = p.charEnd
            }
            return RenderProjection(renderStarts, renderEnds, extractorStarts, extractorEnds, render.text.length)
        }
    }
}
