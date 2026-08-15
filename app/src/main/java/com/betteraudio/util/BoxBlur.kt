package com.betteraudio.util

/**
 * Separable box blur (running-sum, radius-independent cost) over a packed ARGB_8888 pixel array.
 * Repeated box blur converges to a Gaussian, so a handful of iterations look like a true Gaussian
 * blur at a fraction of the cost — the same trick [com.betteraudio.data.covers.CoverEffectBaker]
 * uses for its progressive cover backdrop, factored out here so the widget blur can share it.
 *
 * Alpha is always forced to 0xFF by [horizontal]/[vertical] — callers that need to preserve
 * transparency must premultiply first and un-premultiply after (see `BlurUtil.blur`).
 */
object BoxBlur {

    /** Runs [iterations] rounds of horizontal+vertical box blur (radius [r]) over [px] in place. */
    fun blurArgb(px: IntArray, w: Int, h: Int, r: Int, iterations: Int = 3) {
        if (r < 1) return
        val lineScratch = IntArray(w)
        val colScratch = IntArray(h)
        repeat(iterations) {
            horizontal(px, w, h, r, lineScratch)
            vertical(px, w, h, r, colScratch)
        }
    }

    /** Separable box blur (running-sum, radius-independent cost), horizontal pass, in place.
     *  [line] is caller-owned scratch of size >= w, reused across calls to avoid reallocating. */
    fun horizontal(px: IntArray, w: Int, h: Int, r: Int, line: IntArray) {
        if (r < 1 || w < 2) return
        val window = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            System.arraycopy(px, base, line, 0, w)
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = line[i.coerceIn(0, w - 1)]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                px[base + x] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val cOut = line[(x - r).coerceIn(0, w - 1)]
                val cIn = line[(x + r + 1).coerceIn(0, w - 1)]
                sr += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                sg += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }

    /** Separable box blur, vertical pass, in place.
     *  [col] is caller-owned scratch of size >= h, reused across calls to avoid reallocating. */
    fun vertical(px: IntArray, w: Int, h: Int, r: Int, col: IntArray) {
        if (r < 1 || h < 2) return
        val window = 2 * r + 1
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = px[y * w + x]
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = col[i.coerceIn(0, h - 1)]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (0xFF shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val cOut = col[(y - r).coerceIn(0, h - 1)]
                val cIn = col[(y + r + 1).coerceIn(0, h - 1)]
                sr += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                sg += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }
}
