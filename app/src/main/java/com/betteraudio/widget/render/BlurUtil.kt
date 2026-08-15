package com.betteraudio.widget.render

import android.graphics.Bitmap
import com.betteraudio.util.BoxBlur
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Background blur for widget elements: a real separable box blur (3 iterations, converges to a
 * Gaussian) at a size-bounded working resolution — not the resample-only "shrink and blow back
 * up" the widget used before, which produced visible blocks at any real blur radius (bilinear
 * resampling is a tent filter, not a blur; see git history for the pre-rewrite version and the
 * measurements that motivated this).
 */
object BlurUtil {

    /** Working-buffer cap. [WidgetPainter] callers differ wildly in how big [src] already is:
     *  the placed widget is pre-capped by `WidgetUpdater.capSize` (≤190k px), but the editor's
     *  live preview paints at the real on-screen frame size (~1M px on a 1080p phone, re-blurred
     *  on every slider tick) and thumbnails are uncapped too. Blurring above this budget instead
     *  works on an area-downscaled copy with a proportionally smaller radius, then upscales the
     *  (already blurred, so alias-free) result back — cheap, and indistinguishable from a
     *  full-resolution blur once the image is this soft. */
    private const val PIXEL_BUDGET = 250_000
    private const val ITERATIONS = 3

    fun blur(src: Bitmap, radius: Float): Bitmap {
        val r = radius.roundToInt()
        if (r < 1) return src

        val srcW = src.width
        val srcH = src.height
        val n = srcW.toLong() * srcH
        val f = if (n > PIXEL_BUDGET) sqrt(PIXEL_BUDGET.toDouble() / n).toFloat() else 1f

        // src may be a shared WidgetBitmapCache entry (WidgetPainter.fitBitmap can return it
        // unchanged) — never recycle or mutate it. Everything below only reads it.
        val work = if (f < 1f) {
            val ww = max(1, (srcW * f).roundToInt())
            val wh = max(1, (srcH * f).roundToInt())
            Bitmap.createScaledBitmap(src, ww, wh, true)
        } else {
            src
        }
        val workRadius = max(1, (r * f).roundToInt())

        val w = work.width
        val h = work.height
        val px = IntArray(w * h)
        work.getPixels(px, 0, w, 0, 0, w, h)
        if (work !== src) work.recycle()

        if (src.hasAlpha()) {
            // Bitmap.getPixels returns non-premultiplied (straight) ARGB. Blurring R/G/B
            // independently of A on straight alpha bleeds a transparent pixel's (usually
            // black/garbage) RGB into its opaque neighbours — dark halos at every transparency
            // edge. Premultiplying first makes a fully-transparent pixel's RGB genuinely 0, so it
            // contributes nothing to the average. Widget backgrounds can be transparent PNGs
            // (custom images, the default cover), so this path is real, not theoretical.
            premultiplyInPlace(px)
            blurPremultipliedArgb(px, w, h, workRadius, ITERATIONS)
            unpremultiplyInPlace(px)
        } else {
            // No transparency to protect — the shared box blur (which forces output alpha to
            // opaque) is exact and reuses the same passes CoverEffectBaker's backdrop bake does.
            BoxBlur.blurArgb(px, w, h, workRadius, ITERATIONS)
        }

        val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        if (f >= 1f) return blurred

        val up = Bitmap.createScaledBitmap(blurred, srcW, srcH, true)
        if (up !== blurred) blurred.recycle()
        return up
    }

    fun clampRadius(radius: Float, maxEdge: Int): Float = min(radius, maxEdge / 4f)

    private fun premultiplyInPlace(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0xFF) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            px[i] = (a shl 24) or ((r * a / 255) shl 16) or ((g * a / 255) shl 8) or (b * a / 255)
        }
    }

    private fun unpremultiplyInPlace(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0 || a == 0xFF) continue
            val r = ((c shr 16) and 0xFF) * 255 / a
            val g = ((c shr 8) and 0xFF) * 255 / a
            val b = (c and 0xFF) * 255 / a
            px[i] = (a shl 24) or (r.coerceAtMost(255) shl 16) or (g.coerceAtMost(255) shl 8) or b.coerceAtMost(255)
        }
    }

    /** Separable box blur over all four premultiplied ARGB channels, alpha included — unlike
     *  [BoxBlur], which forces alpha opaque (correct for CoverEffectBaker's always-opaque input,
     *  wrong here where a soft alpha edge must stay soft after blurring). */
    private fun blurPremultipliedArgb(px: IntArray, w: Int, h: Int, r: Int, iterations: Int) {
        val line = IntArray(w)
        val col = IntArray(h)
        repeat(iterations) {
            horizontalPremultiplied(px, w, h, r, line)
            verticalPremultiplied(px, w, h, r, col)
        }
    }

    private fun horizontalPremultiplied(px: IntArray, w: Int, h: Int, r: Int, line: IntArray) {
        if (r < 1 || w < 2) return
        val window = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            System.arraycopy(px, base, line, 0, w)
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = line[i.coerceIn(0, w - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (x in 0 until w) {
                px[base + x] = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val cOut = line[(x - r).coerceIn(0, w - 1)]
                val cIn = line[(x + r + 1).coerceIn(0, w - 1)]
                sa += (cIn ushr 24) - (cOut ushr 24)
                sr += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                sg += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }

    private fun verticalPremultiplied(px: IntArray, w: Int, h: Int, r: Int, col: IntArray) {
        if (r < 1 || h < 2) return
        val window = 2 * r + 1
        for (x in 0 until w) {
            for (y in 0 until h) col[y] = px[y * w + x]
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val c = col[i.coerceIn(0, h - 1)]
                sa += c ushr 24; sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = ((sa / window) shl 24) or ((sr / window) shl 16) or ((sg / window) shl 8) or (sb / window)
                val cOut = col[(y - r).coerceIn(0, h - 1)]
                val cIn = col[(y + r + 1).coerceIn(0, h - 1)]
                sa += (cIn ushr 24) - (cOut ushr 24)
                sr += ((cIn shr 16) and 0xFF) - ((cOut shr 16) and 0xFF)
                sg += ((cIn shr 8) and 0xFF) - ((cOut shr 8) and 0xFF)
                sb += (cIn and 0xFF) - (cOut and 0xFF)
            }
        }
    }
}
