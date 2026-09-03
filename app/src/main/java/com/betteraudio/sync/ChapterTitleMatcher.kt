package com.betteraudio.sync

import kotlin.math.abs

/**
 * Locates an audio chapter's **title** inside the ebook's own token stream, so
 * [com.betteraudio.data.transcribe.SyncAligner]'s pass 1 can find where a span starts in the text
 * without decoding and transcribing audio for it. On the common layout — one xhtml per chapter,
 * opening with `<h1>Chapter Twelve</h1>` — this replaces a 15-second Vosk transcription per
 * chapter with a string comparison. Headings reach the token stream because `h1`–`h6` are block
 * tags in [com.betteraudio.data.ebook.ParagraphExtractor], so each becomes its own paragraph.
 *
 * **Everything here is tuned to prefer a miss over a wrong hit.** A false title match is more
 * expensive than no match at all: pass 1's result seeds that span's *search range* for pass 2, so
 * one bad index makes every later probe in that chapter search the wrong part of the book. A miss
 * just falls through to transcription, which is what the aligner did for every span before. Hence
 * the guards, each of which kills a specific real failure:
 *
 *  - **Paragraph starts only.** A chapter heading is its own block. Without this, the phrase
 *    "chapter twelve" occurring mid-sentence in the prose is a candidate.
 *  - **Short paragraphs only** ([PARA_SLACK]). A heading is a few words; a body paragraph that
 *    happens to open with the title's words is not a heading.
 *  - **Near the head of its spine item** ([ChapterSlice.indexInSpine] vs [MAX_INDEX_IN_SPINE]).
 *    This is what rejects a **table of contents**, whose entries ("Chapter 1", "Chapter 2", …) are
 *    otherwise perfect paragraph-start matches sitting at the very front of the book. Only the
 *    TOC's first entry survives that check, and monotonic forward search then pushes every later
 *    chapter past it.
 *  - **Monotonic** ([searchFromToken]) and **drift-bounded** ([expectedToken]/[maxDriftTokens]).
 *    Chapters occur in order, and chapter *k* of an audiobook lands roughly *k/n* of the way
 *    through the text. The drift bound is deliberately loose — front/back matter shifts the ratio —
 *    it exists only to reject a match that is grossly out of place, which is what would otherwise
 *    cascade through the monotonic search and poison every subsequent chapter.
 *
 * Matching is **recall of the title within the paragraph**, not symmetric similarity: an epub
 * heading of "Chapter 12: The Forgotten Shore" should match an audio title of "Chapter 12". The
 * reverse (audio carrying more words than the heading) is handled by the same measure scoring
 * lower, which is correct — that is a weaker correspondence.
 */
internal object ChapterTitleMatcher {

    /** One paragraph's slice of the whole-book token stream, precomputed once per run. */
    data class ChapterSlice(
        val startToken: Int,
        val endTokenExclusive: Int,
        val spineIndex: Int,
        /** 0 = this is the first paragraph of its spine item. */
        val indexInSpine: Int
    )

    data class Match(val tokenIndex: Int, val score: Float)

    /** Fraction of the title's tokens that must appear, in order, in the paragraph. High on
     *  purpose: a heading either is the title or is not, unlike a noisy ASR transcript. */
    const val ACCEPT_SCORE = 0.85f

    /** How far into a spine item a heading may sit and still count as that item's chapter opener.
     *  Above 0 because epubs commonly precede the heading with a part label, an epigraph, or an
     *  image caption. */
    private const val MAX_INDEX_IN_SPINE = 3

    /** A paragraph longer than the title by more than this is prose, not a heading. */
    private const val PARA_SLACK = 10

    /** Extra tokens past the title's length that may be considered when scoring, so a heading with
     *  a subtitle ("Chapter 12: The Forgotten Shore") still matches "Chapter 12". */
    private const val HEAD_SLACK = 6

    /**
     * Best match for [title] at or after [searchFromToken], or null when nothing clears the bar.
     *
     * @param tokenAt        normalized token accessor over the whole-book stream
     * @param slices         paragraph slices, ascending by [ChapterSlice.startToken]
     * @param searchFromToken monotonicity floor — the previous chapter's matched token index + 1
     * @param expectedToken  where this chapter is expected proportionally; null disables the guard
     * @param maxDriftTokens how far from [expectedToken] a match may land
     */
    fun find(
        title: String,
        tokenAt: (Int) -> String,
        slices: List<ChapterSlice>,
        searchFromToken: Int = 0,
        expectedToken: Int? = null,
        maxDriftTokens: Int = Int.MAX_VALUE
    ): Match? {
        val variants = titleVariants(title)
        if (variants.isEmpty()) return null

        var best: Match? = null
        for (slice in slices) {
            if (slice.startToken < searchFromToken) continue
            if (slice.indexInSpine > MAX_INDEX_IN_SPINE) continue
            if (expectedToken != null && abs(slice.startToken - expectedToken) > maxDriftTokens) continue

            val paraLen = slice.endTokenExclusive - slice.startToken
            if (paraLen <= 0) continue

            for (variant in variants) {
                if (paraLen > variant.size + PARA_SLACK) continue
                val headLen = minOf(paraLen, variant.size + HEAD_SLACK)
                val head = ArrayList<String>(headLen)
                for (k in slice.startToken until slice.startToken + headLen) head.add(tokenAt(k))

                val recall = TextSimilarity.lcsLength(variant, head).toFloat() / variant.size
                if (recall < ACCEPT_SCORE) continue
                if (best == null || recall > best.score) best = Match(slice.startToken, recall)
            }
        }
        return best
    }

    // ── title normalization + number variants ────────────────────────────────

    /**
     * The title as token lists to try, in preference order. Audio metadata and epub headings
     * disagree about number format constantly ("Chapter 12" vs "Chapter Twelve"), and that single
     * difference is enough to drop recall below the bar on a two-token title, so both spellings
     * are tried. Returns empty for a title with no usable tokens.
     */
    fun titleVariants(title: String): List<List<String>> {
        val base = TextSimilarity.tokenize(title)
        if (base.isEmpty()) return emptyList()
        val out = LinkedHashSet<List<String>>()
        out.add(base)
        digitsToWords(base)?.let { out.add(it) }
        wordsToDigits(base)?.let { out.add(it) }
        return out.toList()
    }

    private val UNITS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** 0..99 as words, e.g. 12 -> ["twelve"], 23 -> ["twenty", "three"]. Null outside that range —
     *  chapter numbers past 99 exist but spelling them out ("one hundred and seven") varies too
     *  much between editions to be worth guessing at. */
    fun numberToWords(n: Int): List<String>? = when {
        n < 0 || n > 99 -> null
        n < 20 -> listOf(UNITS[n])
        n % 10 == 0 -> listOf(TENS[n / 10])
        else -> listOf(TENS[n / 10], UNITS[n % 10])
    }

    private fun digitsToWords(tokens: List<String>): List<String>? {
        var changed = false
        val out = ArrayList<String>(tokens.size + 1)
        for (t in tokens) {
            val n = t.toIntOrNull()
            val words = n?.let { numberToWords(it) }
            if (words != null) { out.addAll(words); changed = true } else out.add(t)
        }
        return if (changed) out else null
    }

    private fun wordsToDigits(tokens: List<String>): List<String>? {
        var changed = false
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val unit = UNITS.indexOf(t)
            val ten = TENS.indexOf(t)
            when {
                unit >= 0 -> { out.add(unit.toString()); changed = true; i++ }
                ten >= 2 -> {
                    // "twenty three" is one number; "twenty" alone is still twenty.
                    val nextUnit = tokens.getOrNull(i + 1)?.let { u -> UNITS.indexOf(u).takeIf { it in 1..9 } }
                    if (nextUnit != null) { out.add((ten * 10 + nextUnit).toString()); i += 2 }
                    else { out.add((ten * 10).toString()); i++ }
                    changed = true
                }
                else -> { out.add(t); i++ }
            }
        }
        return if (changed) out else null
    }
}
