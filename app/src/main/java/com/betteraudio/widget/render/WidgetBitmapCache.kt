package com.betteraudio.widget.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.betteraudio.util.AppLog
import java.io.File

/** Decodes source images (book/series covers, custom element images) downsampled to roughly the
 *  size they'll actually be drawn at, and caches the result — avoids re-decoding a full-resolution
 *  cover on every 1 Hz sleep-timer tick re-render. Keyed by (path, size bucket) so the same cover
 *  decoded for a small icon and a full background don't collide or waste memory holding the
 *  larger one when only the small one is needed. */
object WidgetBitmapCache {
    private const val MAX_ENTRIES = 24
    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    fun decodeFile(path: String?, reqW: Int, reqH: Int): Bitmap? {
        if (path.isNullOrBlank()) return null
        val bucketedW = bucket(reqW)
        val bucketedH = bucket(reqH)
        val key = "$path:$bucketedW:$bucketedH"
        cache.get(key)?.let { return it }

        val file = File(path)
        if (!file.exists()) return null
        val bmp = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, bucketedW, bucketedH)
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            AppLog.e("Widget", "failed to decode $path", e)
            null
        } ?: return null

        cache.put(key, bmp)
        return bmp
    }

    fun clear() = cache.evictAll()

    /** Rounds up to the nearest 64px so near-identical requested sizes (e.g. during a launcher
     *  resize drag) reuse the same cache entry instead of thrashing. */
    private fun bucket(v: Int): Int = ((v.coerceAtLeast(1) + 63) / 64) * 64

    private fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (srcW <= 0 || srcH <= 0) return sample
        while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) sample *= 2
        return sample
    }
}
