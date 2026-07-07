package com.betteraudio.data.ebook

import com.betteraudio.sync.TextSimilarity

/**
 * One block-level text run inside a spine item. [charStart]/[charCount] index into the concatenated
 * normalized text of the whole spine item — the "char stream" both sync tiers use as the text-side
 * coordinate.
 */
data class Paragraph(
    val index: Int,
    val charStart: Int,
    val charCount: Int,
    val normalizedText: String   // lowercased, entity-decoded, whitespace-collapsed
) {
    val charEnd: Int get() = charStart + charCount
}

/** All paragraphs of one spine item plus the total char count of its normalized text. */
data class SpineParagraphs(val paragraphs: List<Paragraph>, val totalChars: Int) {

    /** charOffset → (paragraphIndex, fractionInParagraph). Binary search on charStart. */
    fun locate(charOffset: Int): Pair<Int, Float> {
        if (paragraphs.isEmpty()) return 0 to 0f
        val clamped = charOffset.coerceIn(0, totalChars)
        var lo = 0; var hi = paragraphs.lastIndex; var idx = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val p = paragraphs[mid]
            when {
                clamped < p.charStart -> hi = mid - 1
                clamped >= p.charEnd && mid < paragraphs.lastIndex -> lo = mid + 1
                else -> { idx = mid; break }
            }
            idx = mid
        }
        val p = paragraphs[idx]
        val frac = if (p.charCount > 0) ((clamped - p.charStart).toFloat() / p.charCount).coerceIn(0f, 1f) else 0f
        return idx to frac
    }

    /** Documented tier-1 approximation: scroll fraction ≈ char fraction (near-uniform text density
     *  in a reflowed column; the reader keeps JS disabled so true pixel offsets are unknowable). */
    fun charOffsetForFraction(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * totalChars).toInt().coerceIn(0, totalChars)

    fun fractionForCharOffset(offset: Int): Float =
        if (totalChars == 0) 0f else (offset.toFloat() / totalChars).coerceIn(0f, 1f)

    /** charStart of a paragraph (for anchor lookups); clamps out-of-range indices. */
    fun charStartOf(paragraphIndex: Int): Int =
        paragraphs.getOrNull(paragraphIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0)))?.charStart ?: 0
}

/**
 * Splits a spine item's XHTML into paragraphs. Deliberately a **tolerant, hand-rolled tag scanner**
 * rather than XmlPullParser: real-world EPUB XHTML is frequently malformed (unclosed tags, stray
 * `&`), and the reader already treats spine XHTML as a byte-rewritten string. This never throws —
 * worst case it degrades to a single paragraph covering the whole item.
 */
object ParagraphExtractor {

    private val BLOCK_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "div",
        "td", "dd", "dt", "figcaption", "pre"
    )
    private val SKIP_CONTENT = setOf("script", "style", "head", "title")

    fun extract(xhtml: ByteArray): SpineParagraphs = runCatching {
        scan(String(xhtml, Charsets.UTF_8))
    }.getOrElse { SpineParagraphs(emptyList(), 0) }

    private fun scan(html: String): SpineParagraphs {
        val paragraphs = ArrayList<Paragraph>()
        val text = StringBuilder()       // running normalized char stream for the whole item
        val buf = StringBuilder()        // current paragraph's raw text
        var skipDepth = 0                // >0 while inside script/style/head/title
        var i = 0
        val n = html.length

        fun flush() {
            val normalized = TextSimilarity.normalize(decodeEntities(buf.toString()))
            buf.setLength(0)
            if (normalized.length < 2) return
            if (text.isNotEmpty()) text.append(' ')   // single-space separator between paragraphs
            val charStart = text.length
            text.append(normalized)
            paragraphs.add(Paragraph(paragraphs.size, charStart, normalized.length, normalized))
        }

        while (i < n) {
            val c = html[i]
            if (c == '<') {
                val close = html.indexOf('>', i + 1)
                if (close < 0) { break }  // malformed tail — stop; buffer flushed below
                val raw = html.substring(i + 1, close)
                i = close + 1
                val isEnd = raw.startsWith("/")
                val name = raw.removePrefix("/").trimStart()
                    .takeWhile { !it.isWhitespace() && it != '/' }
                    .lowercase()
                when {
                    name in SKIP_CONTENT -> {
                        if (isEnd) { if (skipDepth > 0) skipDepth-- }
                        else if (!raw.trimEnd().endsWith("/")) skipDepth++
                    }
                    skipDepth > 0 -> { /* swallow tag inside skipped content */ }
                    name == "br" -> buf.append(' ')
                    name in BLOCK_TAGS -> flush()   // opening OR closing a block boundary flushes
                }
            } else {
                if (skipDepth == 0) buf.append(c)
                i++
            }
        }
        flush()
        return SpineParagraphs(paragraphs, text.length)
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”"
    )

    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 12)) {
                    val ent = s.substring(i + 1, semi)
                    val decoded = when {
                        ent.startsWith("#x") || ent.startsWith("#X") ->
                            ent.substring(2).toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                        ent.startsWith("#") ->
                            ent.substring(1).toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                        else -> NAMED[ent]
                    }
                    if (decoded != null) { out.append(decoded); i = semi + 1; continue }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
