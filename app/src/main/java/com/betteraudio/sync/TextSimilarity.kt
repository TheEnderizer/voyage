package com.betteraudio.sync

import kotlin.math.max
import kotlin.math.min

/**
 * Shared text-normalization and fuzzy-similarity helpers. Used by chapter matching, paragraph
 * extraction, and (Phase 4+) audio-transcript alignment, so ebook text and ASR output are
 * normalized/compared identically.
 */
internal object TextSimilarity {

    private val PUNCT_REGEX = Regex("""[^a-z0-9\s]""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    /** Lowercase, strip punctuation to spaces, collapse whitespace. */
    fun normalize(s: String): String =
        PUNCT_REGEX.replace(s.lowercase(), " ").let { WHITESPACE_REGEX.replace(it, " ") }.trim()

    /** Split already-normalized (or raw) text into word tokens. */
    fun tokenize(s: String): List<String> =
        normalize(s).split(' ').filter { it.isNotEmpty() }

    /** Jaccard similarity of the two token SETS (order-insensitive). */
    fun tokenJaccard(a: String, b: String): Float {
        val ta = a.split(' ').filter { it.isNotEmpty() }.toSet()
        val tb = b.split(' ').filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        return ta.intersect(tb).size.toFloat() / ta.union(tb).size
    }

    /** 1 − normalized Levenshtein distance (0..1, 1 = identical). */
    fun levenshteinRatio(a: String, b: String): Float {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1f
        return 1f - (levenshtein(a, b).toFloat() / maxLen)
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev
                        else 1 + min(prev, min(dp[j], dp[j - 1]))
                prev = temp
            }
        }
        return dp[b.length]
    }

    /** Longest common subsequence length of two token lists — an order-aware overlap measure. */
    fun lcsLength(a: List<String>, b: List<String>): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        val dp = IntArray(b.size + 1)
        for (i in 1..a.size) {
            var prev = 0
            for (j in 1..b.size) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else max(dp[j], dp[j - 1])
                prev = temp
            }
        }
        return dp[b.size]
    }
}
