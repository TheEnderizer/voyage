package com.betteraudio.sync

import com.betteraudio.data.ebook.SpineItem
import kotlin.math.round

/**
 * Auto-aligns an audiobook's chapter list to an epub's spine items when they don't correspond
 * 1:1 (front/back matter, differently-split "Part" divisions, etc). Runs a cascade of
 * progressively looser strategies and returns whichever produces the highest-confidence
 * [ChapterMap]; falls back to a proportional spread if nothing matches well.
 */
object ChapterMatcher {

    /** Minimum fraction of audio chapters that must be confidently matched before trusting the
     *  result over the proportional fallback. */
    private const val MIN_MATCH_QUALITY = 0.30f

    fun autoMatch(audio: List<AudioChapterSpan>, spine: List<SpineItem>): ChapterMap {
        if (audio.isEmpty() || spine.isEmpty()) return ChapterMap(List(audio.size) { -1 })

        // 1) Fast path: equal counts -> identity map (the common case — one xhtml per chapter).
        if (audio.size == spine.size) {
            return ChapterMap(audio.indices.toList())
        }

        // 2) Number strategy: if most items on both sides carry an extractable chapter number,
        //    match on equal numbers (monotonic only — a later audio chapter can't map earlier).
        val audioNums = audio.map { extractNumber(it.title) }
        val spineNums = spine.map { it.title?.let { t -> extractNumber(t) } }
        val audioNumRatio = audioNums.count { it != null }.toFloat() / audio.size
        val spineNumRatio = spineNums.count { it != null }.toFloat() / spine.size
        if (audioNumRatio >= 0.6f && spineNumRatio >= 0.6f) {
            val byNumber = matchByNumber(audioNums, spineNums)
            if (quality(byNumber) >= MIN_MATCH_QUALITY) return byNumber
        }

        // 3) Title strategy: monotonic greedy alignment by normalized-title similarity.
        val byTitle = matchByTitle(audio, spine)
        if (quality(byTitle) >= MIN_MATCH_QUALITY) return byTitle

        // 4) Fallback: spread audio chapters proportionally across the spine.
        return proportionalMap(audio.size, spine.size)
    }

    private fun quality(map: ChapterMap): Float =
        if (map.audioToSpine.isEmpty()) 0f
        else map.audioToSpine.count { it >= 0 }.toFloat() / map.audioToSpine.size

    private fun proportionalMap(audioCount: Int, spineCount: Int): ChapterMap =
        ChapterMap((0 until audioCount).map { i -> round(i.toFloat() * spineCount / audioCount).toInt().coerceIn(0, spineCount - 1) })

    private fun matchByNumber(audioNums: List<Int?>, spineNums: List<Int?>): ChapterMap {
        val result = MutableList(audioNums.size) { -1 }
        var lastSpine = -1
        for (i in audioNums.indices) {
            val n = audioNums[i] ?: continue
            // Search forward-only from the last match so the result stays monotonic.
            val match = spineNums.withIndex().firstOrNull { (idx, sn) -> idx > lastSpine - 1 && sn == n }
            if (match != null) {
                result[i] = match.index
                lastSpine = match.index
            }
        }
        return ChapterMap(result)
    }

    private fun matchByTitle(audio: List<AudioChapterSpan>, spine: List<SpineItem>): ChapterMap {
        val normAudio = audio.map { normalize(it.title) }
        val normSpine = spine.map { normalize(it.title ?: "") }
        val result = MutableList(audio.size) { -1 }
        var searchFrom = 0
        for (i in normAudio.indices) {
            val a = normAudio[i]
            if (a.isEmpty()) continue
            var bestIdx = -1
            var bestScore = 0f
            for (j in searchFrom until normSpine.size) {
                val s = normSpine[j]
                if (s.isEmpty()) continue
                val jaccard = tokenJaccard(a, s)
                if (jaccard < 0.5f) continue
                val lev = levenshteinRatio(a, s)
                if (lev < 0.75f) continue
                val score = jaccard + lev
                if (score > bestScore) { bestScore = score; bestIdx = j }
            }
            if (bestIdx >= 0) {
                result[i] = bestIdx
                searchFrom = bestIdx + 1
            }
        }
        return ChapterMap(result)
    }

    // ── text helpers (normalization + similarity live in the shared TextSimilarity) ──

    private val NUMBER_REGEX = Regex("""\d+""")

    private fun extractNumber(title: String): Int? =
        NUMBER_REGEX.find(title)?.value?.toIntOrNull()

    private fun normalize(title: String): String = TextSimilarity.normalize(title)
    private fun tokenJaccard(a: String, b: String): Float = TextSimilarity.tokenJaccard(a, b)
    private fun levenshteinRatio(a: String, b: String): Float = TextSimilarity.levenshteinRatio(a, b)
}
