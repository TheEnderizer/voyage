package com.betteraudio.widget.render

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Cheap background blur: downscale then upscale with bilinear filtering. Avoids RenderScript
 *  (deprecated) and RenderEffect (API 31+, below this app's minSdk 26) while still giving a soft,
 *  GPU-free blur suitable for a background behind text — not a precise Gaussian, but visually
 *  equivalent at widget sizes and cheap enough to run on every render. */
object BlurUtil {
    fun blur(src: Bitmap, radius: Float): Bitmap {
        if (radius <= 0.5f) return src
        // Larger radius → smaller downscale factor → blurrier result. Clamped so tiny bitmaps
        // (small widgets) never downscale to 0px.
        val factor = (1f / (1f + radius / 8f)).coerceIn(0.05f, 1f)
        val smallW = max(1, (src.width * factor).toInt())
        val smallH = max(1, (src.height * factor).toInt())
        val down = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        val up = Bitmap.createScaledBitmap(down, src.width, src.height, true)
        if (down !== src) down.recycle()
        return up
    }

    fun clampRadius(radius: Float, maxEdge: Int): Float = min(radius, maxEdge / 2f)
}
