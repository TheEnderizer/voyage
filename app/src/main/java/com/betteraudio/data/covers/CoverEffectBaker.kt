package com.betteraudio.data.covers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.betteraudio.util.AppLog
import com.betteraudio.util.BoxBlur
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Pre-renders the cover background effect — original image, a vertically-flipped
 * reflection below it, a **continuous** (per-row, stage-free) progressive blur that
 * grades from sharp at the top to fully blurred at the bottom, and a fade to black —
 * into a single flat bitmap saved to internal storage.
 *
 * The UI then draws that one cached bitmap instead of recomputing seven live blur
 * passes every frame. Baking is one-time (on first view of a cover, or via the manual
 * "refresh" action) so it can afford a genuinely continuous blur that the live path
 * could not.
 *
 * Continuous blur via progressive accumulation:
 *   A working buffer is blurred by a small box-blur increment repeatedly; after each
 *   increment, every row whose target blur "level" has just been reached is copied out
 *   into the result. Because each output row is a single coherent accumulated blur
 *   (never a linear mix of two differently-blurred copies), there is no banding and no
 *   double-imaging — the radius varies smoothly with Y. Repeated box blur converges to
 *   a Gaussian, so the look matches a true Gaussian progressive blur.
 */
@Singleton
class CoverEffectBaker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        /** Bump when the visual algorithm changes (baked files embed this in their name). */
        const val VERSION = 1

        // 720 is plenty for a backdrop that's always shown blurred behind other content —
        // it halves the pixel count (and so the per-iteration blur cost) versus the original
        // 1080, with no visible quality loss since sharpness is never the point of this bitmap.
        private const val MAX_WIDTH = 720
        private const val BLUR_ITERATIONS = 44

        // Fractions of the COMBINED (original + reflection) height = [0, 1].
        private const val SHARP_UNTIL = 0.30f  // fully sharp above this Y
        private const val RAMP_END    = 0.92f  // maximum blur reached at this Y
        private const val FADE_START  = 0.93f  // fade-to-black begins here
    }

    private val outDir: File get() = File(context.filesDir, "cover_fx").apply { mkdirs() }

    // Baking is CPU- and memory-heavy (several full-size IntArray buffers per call); running two
    // at once — e.g. a "refresh all" sweep racing a cover just opened in the player — only makes
    // both slower and doubles peak memory for no benefit, so serialize instead of parallelizing.
    private val bakeSemaphore = Semaphore(1)

    /**
     * Bakes the effect for [sourceCoverPath] and writes it to internal storage, replacing any
     * previous bake for [cacheKey]. Returns the new file's absolute path, or null if the source
     * image can't be decoded. [cacheKey] must be unique across every caller's id space — book ids
     * and series ids are separate autoincrement sequences and can collide, so callers other than
     * a plain book id must namespace their key (e.g. `"series_$seriesId"`).
     */
    suspend fun bake(sourceCoverPath: String, cacheKey: String): String? = bakeSemaphore.withPermit {
        bakeLocked(sourceCoverPath, cacheKey)
    }

    private suspend fun bakeLocked(sourceCoverPath: String, cacheKey: String): String? = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()
        val src = runCatching { BitmapFactory.decodeFile(sourceCoverPath) }.getOrNull()
        if (src == null) {
            AppLog.w(LogCat.UI, "CoverEffectBaker: could not decode '$sourceCoverPath' for cacheKey=$cacheKey")
            return@withContext null
        }

        var scaled: Bitmap? = null
        var outBmp: Bitmap? = null
        try {
            val w = minOf(src.width, MAX_WIDTH).coerceAtLeast(1)
            val hc = (src.height.toFloat() / src.width * w).roundToInt().coerceAtLeast(1)
            scaled = if (src.width == w && src.height == hc) src
                     else Bitmap.createScaledBitmap(src, w, hc, true)

            val h = hc * 2

            // Combined sharp pixels: original on top, mirror reflection below.
            val combined = IntArray(w * h)
            val rowBuf = IntArray(w)
            for (y in 0 until hc) {
                scaled.getPixels(rowBuf, 0, w, 0, y, w, 1)
                System.arraycopy(rowBuf, 0, combined, y * w, w)               // original row
                System.arraycopy(rowBuf, 0, combined, (h - 1 - y) * w, w)     // reflected row
            }

            val output = combined.copyOf()   // rows above the ramp stay sharp
            val working = combined           // blurred in place, progressively

            val inc = (w / 360).coerceAtLeast(2)   // box-blur radius per iteration (res-scaled)
            val rampLow = SHARP_UNTIL * h
            val rampSpan = (RAMP_END * h - rampLow).coerceAtLeast(1f)

            // Scratch rows/columns for the box blur passes, allocated once and reused across all
            // BLUR_ITERATIONS * 2 passes instead of once per pass — the loop below used to churn
            // through ~88 short-lived IntArrays per bake.
            val lineScratch = IntArray(w)
            val colScratch = IntArray(h)

            for (k in 1..BLUR_ITERATIONS) {
                BoxBlur.horizontal(working, w, h, inc, lineScratch)
                BoxBlur.vertical(working, w, h, inc, colScratch)
                val levelLow = (k - 1).toFloat() / BLUR_ITERATIONS
                val levelHigh = k.toFloat() / BLUR_ITERATIONS
                for (y in 0 until h) {
                    val f = smoothstep(((y - rampLow) / rampSpan).coerceIn(0f, 1f))
                    // Copy a row out the moment its target level is reached; the last
                    // iteration sweeps up anything remaining (f == 1.0 at the bottom).
                    if (f > levelLow && (f <= levelHigh || k == BLUR_ITERATIONS)) {
                        System.arraycopy(working, y * w, output, y * w, w)
                    }
                }
            }

            // Fade the bottom to solid black so the reflection dissolves out.
            val fadeStart = FADE_START * h
            val fadeSpan = (h - fadeStart).coerceAtLeast(1f)
            for (y in fadeStart.toInt() until h) {
                val keep = 1f - ((y - fadeStart) / fadeSpan).coerceIn(0f, 1f)
                val base = y * w
                for (x in 0 until w) {
                    val c = output[base + x]
                    val r = (((c shr 16) and 0xFF) * keep).toInt()
                    val g = (((c shr 8) and 0xFF) * keep).toInt()
                    val b = ((c and 0xFF) * keep).toInt()
                    output[base + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outBmp.setPixels(output, 0, w, 0, 0, w, h)

            // Replace any previous bake(s) for this key.
            outDir.listFiles { f -> f.name.startsWith("${cacheKey}_") }?.forEach { it.delete() }
            val dest = File(outDir, "${cacheKey}_v${VERSION}_${System.currentTimeMillis()}.webp")
            dest.outputStream().use { os ->
                val format = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                outBmp.compress(format, 82, os)
            }
            AppLog.d(LogCat.UI) { "CoverEffectBaker: baked cacheKey=$cacheKey (${w}x$h) in ${System.currentTimeMillis() - startMs}ms -> ${dest.name}" }
            dest.absolutePath
        } catch (e: Throwable) {
            AppLog.w(LogCat.UI, "CoverEffectBaker: baking failed for cacheKey=$cacheKey: ${e.message}")
            null
        } finally {
            outBmp?.recycle()
            if (scaled != null && scaled != src) scaled.recycle()
            src.recycle()
        }
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)
}
