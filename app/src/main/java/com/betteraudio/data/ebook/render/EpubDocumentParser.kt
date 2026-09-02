package com.betteraudio.data.ebook.render

import com.betteraudio.sync.TextSimilarity

/**
 * XHTML → [RenderDocument]. Hand-rolled tolerant tag scanner, same reasoning as
 * `ParagraphExtractor`: real-world EPUB XHTML is frequently malformed, so a strict XML parser is
 * the wrong tool. Never throws — worst case degrades to a single block covering the whole item.
 *
 * **Block-boundary parity with `ParagraphExtractor` is deliberate**, not incidental: this scanner
 * flushes a block at exactly the tags in [BLOCK_TAGS] (`ParagraphExtractor.BLOCK_TAGS` verbatim),
 * and computes the same `< 2 normalized chars → dropped` decision on the *same raw text* via
 * [TextSimilarity.normalize]. That means this scanner's Nth surviving (non-dropped) block and
 * `ParagraphExtractor`'s Nth paragraph are the same document position whenever both scanners agree
 * on where the text came from — which is what makes [RenderProjection] a lockstep walk instead of
 * a fuzzy search. Where the two disagree (an entity-table gap, an encoding difference — see C.3 in
 * the plan), the projection falls back rather than emitting a wrong offset.
 *
 * Scope for Phase 1 (novel-grade): paragraph, h1–h6, blockquote, li, pre, div (structural only —
 * bare text directly inside a div still flushes as its own block, matching `ParagraphExtractor`),
 * block image. Inline: bold, italic, underline, strike, sub, sup, code, link, inline image (as
 * alt-text only — a real inline `<img>` box is Phase 6 territory), br, span (no-op passthrough).
 * `ul`/`ol`/`table`/`section`/`article`/`figure` are Phase 6 scope, same as `ParagraphExtractor`.
 */
object EpubDocumentParser {

    // Verbatim copy of ParagraphExtractor.BLOCK_TAGS — see the class doc above for why this must
    // stay in lockstep. ParagraphExtractor itself is frozen (plan C.3) and cannot be modified to
    // share this constant, so it is duplicated deliberately; keep the two in sync by hand.
    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "div",
        "td", "dd", "dt", "figcaption", "pre"
    )
    private val SKIP_CONTENT = setOf("script", "style", "head", "title")
    private val INLINE_BOLD = setOf("b", "strong")
    private val INLINE_ITALIC = setOf("i", "em", "cite", "dfn")
    private val INLINE_UNDERLINE = setOf("u", "ins")
    private val INLINE_STRIKE = setOf("s", "strike", "del")

    fun parse(xhtml: ByteArray, spineDir: String = ""): RenderDocument = runCatching {
        scan(String(xhtml, Charsets.UTF_8), spineDir)
    }.getOrElse { RenderDocument(emptyList(), "", emptyMap()) }

    private class OpenSpan(val kind: Int, val startInBlock: Int, val link: String? = null)
    // kind bits
    private const val K_BOLD = 1
    private const val K_ITALIC = 2
    private const val K_UNDERLINE = 4
    private const val K_STRIKE = 8
    private const val K_SUB = 16
    private const val K_SUP = 32
    private const val K_CODE = 64
    private const val K_LINK = 128

    private fun scan(html: String, spineDir: String): RenderDocument {
        val blocks = ArrayList<RenderBlock>()
        val text = StringBuilder()          // whole-item render stream
        val buf = StringBuilder()           // current block's decoded text
        val idOffsets = LinkedHashMap<String, Int>()
        val spanStack = ArrayList<OpenSpan>()
        val closedSpans = ArrayList<InlineSpan>()   // for the block currently in buf
        var skipDepth = 0
        var docIndex = 0
        var blockDocIndex = 0
        var blockTag = "p"
        var blockKind = BlockKind.PARAGRAPH
        var blockHeadingLevel = 0
        var blockAlign: BlockAlign? = null
        var pendingId: String? = null
        var blockId: String? = null
        var blockNthOfType = 1
        // nth-of-type approximation: a stack of open container frames, each a per-tag counter.
        // Real nesting/xpointer fidelity is Phase 10 scope (C.6) — this is "keep the door open
        // cheaply", not a full DOM. Counted in the PARENT frame at open time (before that block's
        // own child frame is pushed) — counting in the block's own child frame instead would count
        // its nested descendants, not its siblings, which is a different (wrong) number.
        val frameStack = ArrayList<HashMap<String, Int>>().apply { add(HashMap()) }
        fun nextNthOfType(tag: String): Int {
            val frame = frameStack.last()
            val n = (frame[tag] ?: 0) + 1
            frame[tag] = n
            return n
        }
        // Whether the block currently under construction will get a leading separator when it's
        // eventually flushed (see flush()'s `if (text.isNotEmpty()) text.append(' ')`) — stable
        // for the whole lifetime of one block, since `text` only changes at a flush boundary.
        // An id captured mid-construction (block-open id, or an inline id hit while its buffer is
        // still accumulating) must account for this same pending character, or it disagrees with
        // the block's own `renderStart`/`RenderProjection`'s offsets by exactly one position.
        fun pendingSeparatorLength(): Int = if (text.isNotEmpty()) 1 else 0

        fun flush() {
            val raw = buf.toString()
            buf.setLength(0)
            val extractorNormalized = TextSimilarity.normalize(raw)
            val dropped = extractorNormalized.length < 2
            // Trailing open spans at a block boundary close implicitly (malformed markup).
            for (s in spanStack) {
                closedSpans.add(
                    InlineSpan(
                        s.startInBlock, raw.length,
                        bold = s.kind and K_BOLD != 0, italic = s.kind and K_ITALIC != 0,
                        underline = s.kind and K_UNDERLINE != 0, strike = s.kind and K_STRIKE != 0,
                        sub = s.kind and K_SUB != 0, sup = s.kind and K_SUP != 0,
                        code = s.kind and K_CODE != 0, link = s.link
                    )
                )
            }
            // Real EPUB markup is pretty-printed — the text between two adjacent block tags is
            // routinely pure indentation whitespace with no wrapping tag of its own. That text
            // still runs through this same flush() (there is nothing else to trigger a flush for
            // it), but it isn't a real element and must not become a block: it would show up
            // tagged with whatever block tag last happened to be current (a stale, meaningless
            // label), and — worse — it would consume nth-of-type slots that belong to genuine
            // sibling elements.
            if (raw.isNotBlank() || closedSpans.isNotEmpty()) {
                // A single-space separator between blocks in the whole-item stream — found on
                // device: without one, "...Rabbit Hole" immediately followed by "Alice was..."
                // concatenates to "...Rabbit HoleAlice was..." with nothing between them (visible
                // in a search snippet spanning the boundary). This only affects `text` (the
                // whole-item concatenation used for search and RenderProjection's block-boundary
                // bookkeeping); [RenderBlock.text] — what each block actually renders — is
                // unaffected, since it's the raw per-block string, not a slice of `text`.
                if (text.isNotEmpty()) text.append(' ')
                val renderStart = text.length
                text.append(raw)
                blocks.add(
                    RenderBlock(
                        docIndex = blockDocIndex, tag = blockTag, nthOfType = blockNthOfType,
                        id = blockId, kind = blockKind, headingLevel = blockHeadingLevel,
                        align = blockAlign, renderStart = renderStart, text = raw,
                        spans = closedSpans.toList(), extractorDropped = dropped
                    )
                )
            }
            closedSpans.clear()
            blockAlign = null
            blockId = null
        }

        var i = 0
        val n = html.length
        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val close = html.indexOf('>', i + 1)
                if (close < 0) break   // malformed tail — stop, current buffer flushed below
                val raw = html.substring(i + 1, close)
                i = close + 1
                val selfClosing = raw.trimEnd().endsWith("/")
                val isEnd = raw.startsWith("/")
                val nameEnd = raw.removePrefix("/").indexOfFirst { it.isWhitespace() || it == '/' }
                    .let { if (it < 0) raw.removePrefix("/").length else it }
                val name = raw.removePrefix("/").substring(0, nameEnd).lowercase()
                val attrs = if (!isEnd) raw.substring(if (raw.startsWith("/")) 1 else 0) else ""

                when {
                    name in SKIP_CONTENT -> {
                        if (isEnd) { if (skipDepth > 0) skipDepth-- }
                        else if (!selfClosing) skipDepth++
                    }
                    skipDepth > 0 -> { /* swallow */ }
                    name == "br" -> buf.append('\n')
                    name == "img" && !isEnd -> {
                        // Inline <img>: represented as alt text only for Phase 1 (no inline image
                        // box yet — Phase 6). A standalone block image (not inside a text block)
                        // is out of scope for this pass too; Phase 1 item 4 lists it but the
                        // common novel case is inline/cover images handled by EpubParser already.
                        val alt = attrValue(attrs, "alt")
                        if (!alt.isNullOrBlank()) buf.append(alt)
                    }
                    name in BLOCK_TAGS -> {
                        if (!isEnd) {
                            docIndex++
                            flush()
                            blockDocIndex = docIndex
                            blockTag = name
                            blockKind = when (name) {
                                "h1", "h2", "h3", "h4", "h5", "h6" -> BlockKind.HEADING
                                "li" -> BlockKind.LIST_ITEM
                                "blockquote" -> BlockKind.BLOCKQUOTE
                                "pre" -> BlockKind.PREFORMATTED
                                "td", "dd", "dt" -> BlockKind.TABLE_CELL
                                else -> BlockKind.PARAGRAPH
                            }
                            blockHeadingLevel = name.getOrNull(1)?.digitToIntOrNull() ?: 0
                            blockAlign = presentationalAlign(attrs)
                            pendingId = attrValue(attrs, "id")
                            blockId = pendingId
                            if (pendingId != null) idOffsets.putIfAbsent(pendingId!!, text.length + pendingSeparatorLength())
                            // Counted in the parent frame — still current; the child frame for
                            // this block's own descendants is pushed on the next line.
                            blockNthOfType = nextNthOfType(name)
                            frameStack.add(HashMap())
                        } else {
                            flush()
                            if (frameStack.size > 1) frameStack.removeAt(frameStack.lastIndex)
                            blockTag = "p"; blockKind = BlockKind.PARAGRAPH; blockHeadingLevel = 0
                        }
                    }
                    else -> {
                        // Inline formatting spans, tracked against the CURRENT block's buffer.
                        val kindBit = when {
                            name in INLINE_BOLD -> K_BOLD
                            name in INLINE_ITALIC -> K_ITALIC
                            name in INLINE_UNDERLINE -> K_UNDERLINE
                            name in INLINE_STRIKE -> K_STRIKE
                            name == "sub" -> K_SUB
                            name == "sup" -> K_SUP
                            name == "code" || name == "tt" -> K_CODE
                            name == "a" -> K_LINK
                            else -> 0
                        }
                        val id = attrValue(attrs, "id")
                        if (id != null) idOffsets.putIfAbsent(id, text.length + pendingSeparatorLength() + buf.length)
                        if (kindBit != 0 && !isEnd) {
                            val link = if (kindBit == K_LINK) attrValue(attrs, "href") else null
                            docIndex++
                            spanStack.add(OpenSpan(kindBit, buf.length, link))
                        } else if (kindBit != 0 && isEnd) {
                            val openIdx = spanStack.indexOfLast { it.kind == kindBit }
                            if (openIdx >= 0) {
                                val s = spanStack.removeAt(openIdx)
                                closedSpans.add(
                                    InlineSpan(
                                        s.startInBlock, buf.length,
                                        bold = kindBit == K_BOLD, italic = kindBit == K_ITALIC,
                                        underline = kindBit == K_UNDERLINE, strike = kindBit == K_STRIKE,
                                        sub = kindBit == K_SUB, sup = kindBit == K_SUP,
                                        code = kindBit == K_CODE, link = s.link
                                    )
                                )
                            }
                        }
                        // span/small and anything unrecognized: transparent passthrough.
                    }
                }
            } else if (c == '&') {
                val semi = html.indexOf(';', i + 1)
                val decoded = if (semi in (i + 1)..(i + 12)) decodeEntity(html.substring(i + 1, semi)) else null
                if (decoded != null && skipDepth == 0) { buf.append(decoded); i = semi + 1 }
                else { if (skipDepth == 0) buf.append(c); i++ }
            } else {
                if (skipDepth == 0) buf.append(c)
                i++
            }
        }
        flush()
        return RenderDocument(blocks, text.toString(), idOffsets)
    }

    private fun presentationalAlign(attrs: String): BlockAlign? {
        val align = attrValue(attrs, "align")?.lowercase()
        val style = attrValue(attrs, "style")?.lowercase()
        val styleAlign = style?.let { Regex("""text-align\s*:\s*([a-z]+)""").find(it)?.groupValues?.get(1) }
        return when (styleAlign ?: align) {
            "center" -> BlockAlign.CENTER
            "right", "end" -> BlockAlign.END
            "justify" -> BlockAlign.JUSTIFY
            "left", "start" -> BlockAlign.START
            else -> null
        }
    }

    private fun attrValue(attrs: String, name: String): String? {
        val m = Regex("""(?i)\b$name\s*=\s*"([^"]*)"""").find(attrs)
            ?: Regex("""(?i)\b$name\s*=\s*'([^']*)'""").find(attrs)
        return m?.groupValues?.get(1)
    }

    // Deliberately duplicated from ParagraphExtractor (frozen, cannot be shared) — see the class
    // doc. Must stay byte-for-byte identical or RenderProjection's lockstep assumption breaks.
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”"
    )

    private fun decodeEntity(ent: String): String? = when {
        ent.startsWith("#x") || ent.startsWith("#X") ->
            ent.substring(2).toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
        ent.startsWith("#") ->
            ent.substring(1).toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
        else -> NAMED[ent]
    }
}
