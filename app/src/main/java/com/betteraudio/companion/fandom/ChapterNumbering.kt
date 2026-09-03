package com.betteraudio.companion.fandom

import com.betteraudio.playback.ChapterMark

/**
 * Maps *story* chapter numbers (what a wiki cites) to *audio* positions (what a
 * [com.betteraudio.companion.model.FactAnchor] needs), and back.
 *
 * A wiki says "Chapter 172"; a [ChapterMark] says "Ep 172 - Memory Market" at 41,203,118 ms. The
 * bridge between them is simply the number written in the chapter's own title, which is how nearly
 * every serialised-fiction audiobook names its marks.
 *
 * Lives under `companion/fandom` rather than next to [com.betteraudio.playback.ChapterTimeline]
 * because "chapter titles contain parseable story numbers" is an assumption this one feature makes
 * about *external* citations — not a fact about the app's own chapter model, which is deliberately
 * position-based and never numbers anything.
 *
 * ### Why this refuses rather than guesses
 *
 * Falling back to "close enough" is the tempting failure mode and the wrong one: a mis-mapped
 * anchor puts a genuine spoiler in front of the listener at the wrong moment, which is the exact
 * harm the whole reveal cursor exists to prevent. So [Kind.NONE] is a real, expected outcome, and
 * the caller's answer to it is to *ask the listener* which chapter they are on rather than to
 * invent one — see [FandomSeedService].
 */
object ChapterNumbering {

    enum class Kind {
        /** Chapter titles carry story numbers — exact, and the common case. */
        NUMBERED,
        /** No parseable numbers, but enough marks that the Nth mark is plausibly chapter N. */
        ORDINAL,
        /** One mark per file (or none): nothing here can locate a chapter. */
        NONE
    }

    /**
     * Below this many marks, a "chapter list" is really a *file* list (8 volumes of an audiobook),
     * and both numbering strategies would be nonsense — volume 3 of 8 is not chapter 3 of 2900.
     */
    private const val MIN_MARKS_FOR_CHAPTERS = 20

    /** Fraction of titles that must yield a number before the numbering is trusted. */
    private const val MIN_NUMBERED_FRACTION = 0.6

    private val FIRST_NUMBER = Regex("""\d{1,4}""")

    /** The first integer in a chapter title: "Ep 1296 - Fake It" → 1296, "Fake It" → null. */
    fun numberOf(title: String): Int? = FIRST_NUMBER.find(title)?.value?.toIntOrNull()

    data class Mapping(
        val kind: Kind,
        /** Story chapter → its start, book-global ms. Empty unless [kind] is [Kind.NUMBERED]. */
        val byNumber: Map<Int, Long>,
        private val marks: List<ChapterMark>
    ) {
        val usable: Boolean get() = kind != Kind.NONE

        /** Highest chapter this mapping can place; null when it can place none. */
        val maxChapter: Int? get() = when (kind) {
            Kind.NUMBERED -> byNumber.keys.maxOrNull()
            Kind.ORDINAL -> marks.size
            Kind.NONE -> null
        }

        /**
         * Book-global ms where [chapter] starts, or null when it falls outside what this mapping
         * covers. Interpolates between known neighbours for a chapter the audiobook does not mark
         * individually — linear within the gap, which is as good as the data allows and never
         * extrapolates past the ends.
         */
        fun positionOf(chapter: Int): Long? {
            when (kind) {
                Kind.NONE -> return null
                Kind.ORDINAL -> return marks.getOrNull(chapter - 1)?.startMs
                Kind.NUMBERED -> {
                    byNumber[chapter]?.let { return it }
                    val below = byNumber.keys.filter { it < chapter }.maxOrNull() ?: return null
                    val above = byNumber.keys.filter { it > chapter }.minOrNull() ?: return null
                    val lowMs = byNumber.getValue(below)
                    val highMs = byNumber.getValue(above)
                    val progress = (chapter - below).toDouble() / (above - below).toDouble()
                    return lowMs + ((highMs - lowMs) * progress).toLong()
                }
            }
        }

        /** Which story chapter [globalMs] lands in — the listener's cutoff. */
        fun chapterAt(globalMs: Long): Int? {
            if (kind == Kind.NONE || marks.isEmpty()) return null
            val index = marks.indexOfLast { it.startMs <= globalMs }.coerceAtLeast(0)
            return when (kind) {
                Kind.NUMBERED -> numberOf(marks[index].title)
                    ?: byNumber.keys.filter { (byNumber[it] ?: 0L) <= globalMs }.maxOrNull()
                Kind.ORDINAL -> index + 1
                Kind.NONE -> null
            }
        }
    }

    /**
     * Works out how (or whether) [marks] can be addressed by story chapter number.
     *
     * The numbers must come out **strictly increasing** to be trusted. That check is what stops a
     * title format the parser half-understands — years, part numbers, a stray "Volume 2" — from
     * producing a plausible-looking map that is silently wrong everywhere.
     */
    fun of(marks: List<ChapterMark>): Mapping {
        if (marks.size < MIN_MARKS_FOR_CHAPTERS) return Mapping(Kind.NONE, emptyMap(), marks)

        val numbers = marks.map { numberOf(it.title) }
        val present = numbers.filterNotNull()
        val enough = present.size >= (marks.size * MIN_NUMBERED_FRACTION)
        val increasing = present.zipWithNext().all { (a, b) -> b > a }

        if (enough && increasing) {
            val byNumber = LinkedHashMap<Int, Long>()
            marks.forEachIndexed { i, mark ->
                numbers[i]?.let { byNumber.putIfAbsent(it, mark.startMs) }
            }
            return Mapping(Kind.NUMBERED, byNumber, marks)
        }
        return Mapping(Kind.ORDINAL, emptyMap(), marks)
    }
}
