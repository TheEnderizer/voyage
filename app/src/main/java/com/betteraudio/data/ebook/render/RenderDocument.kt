package com.betteraudio.data.ebook.render

/**
 * The **render stream** model (plan `docs/reader-features-and-plan.md`, Part C.3 / C.4 / Phase 1
 * item 4): full-Unicode, un-normalized text, laid out by the real renderer. This is deliberately
 * NOT the same model as [com.betteraudio.data.ebook.ParagraphExtractor]'s output — that stream is
 * frozen and feeds only the sync path (see [com.betteraudio.data.ebook.render.RenderProjection]).
 *
 * A **block-boundary-parity** design, not a full box tree: [EpubDocumentParser] flushes a block at
 * exactly the same tag boundaries `ParagraphExtractor` does (the same `p/h1-h6/li/blockquote/div/
 * td/dd/dt/figcaption/pre` set), which is what makes [RenderProjection] tractable — see that file
 * for why. A true nested box tree (needed for Phase 6 floats, and for exact KOSync xpointer nesting)
 * is deferred; [docIndex]/[tag]/[nthOfType]/[id] are tracked now because Phase 1 item 9 (fragment
 * anchors) and C.6 (keeping the KOSync door open) need them regardless of tree shape.
 */

/** One inline formatting span over `[start, end)` of a [RenderBlock.text]. */
data class InlineSpan(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sub: Boolean = false,
    val sup: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

enum class BlockKind { PARAGRAPH, HEADING, LIST_ITEM, BLOCKQUOTE, PREFORMATTED, TABLE_CELL, OTHER, IMAGE, PAGE_BREAK }

/** Text alignment as declared by presentational HTML or the minimal CSS subset (C-Phase 1 item 5
 *  covers only reading `text-align` / `align=`; the rest of that item's properties come later). */
enum class BlockAlign { START, CENTER, END, JUSTIFY }

/**
 * One leaf block of the render stream. [renderStart]/[text].length index into the *whole spine
 * item's* concatenated render text ([RenderDocument.text]) — the render-stream coordinate every
 * reader-owned position (C.3) is expressed in.
 */
data class RenderBlock(
    val docIndex: Int,
    val tag: String,
    val nthOfType: Int,
    val id: String?,
    val kind: BlockKind,
    val headingLevel: Int = 0,
    val align: BlockAlign? = null,
    val renderStart: Int,
    val text: String,
    val spans: List<InlineSpan>,
    /** Populated only for [BlockKind.IMAGE]: the resolved href (fragment/query already stripped,
     *  relative-path already resolved against the spine item's own directory). */
    val imageHref: String? = null,
    /** Whether extractor-side comparison would have dropped this block (< 2 normalized chars) —
     *  set by [EpubDocumentParser] using the exact same decision `ParagraphExtractor.flush()`
     *  makes, on the exact same raw text, so [RenderProjection] can tell "no counterpart" apart
     *  from "counterpart exists but content diverged". */
    val extractorDropped: Boolean = false,
) {
    val renderEnd: Int get() = renderStart + text.length
}

data class RenderDocument(
    val blocks: List<RenderBlock>,
    val text: String,
    /** Element `id` → render-stream offset where that element's content begins. Fragment anchors
     *  (Phase 1 item 9) resolve `href#id` through this map. */
    val idOffsets: Map<String, Int>,
)
