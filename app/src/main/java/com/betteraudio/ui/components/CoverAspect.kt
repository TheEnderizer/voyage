package com.betteraudio.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A cover's true width/height ratio, decoded from its header only.
 *
 * This exists so the mini player and the full player can agree on the shape of the artwork they
 * are morphing between. `CoverEffectBaker` builds its composite as `hc = height/width * w` with
 * the sharp original on top and its mirror below — so the sharp region of the baked image the full
 * player shows keeps the **cover's own aspect ratio**, not a square.
 *
 * The travelling sharp copy used to be hardcoded to `aspectRatio(1f)` with `ContentScale.Crop`,
 * which matched the bake only for perfectly square art. For anything else the two disagreed on
 * both size and framing, so at the end of the morph the travelling square dissolved into a
 * differently-shaped, differently-cropped image sitting somewhere else — the "crooked" landing.
 * Feeding this ratio to both ends makes the transition a single uniform scale of one consistent
 * image.
 *
 * Decoding is bounds-only (`inJustDecodeBounds`), so it reads a few bytes of header and never
 * allocates the bitmap. Defaults to 1f until the real value arrives, which is also the right
 * answer for the overwhelmingly common square cover.
 */
@Composable
fun rememberCoverAspect(coverPath: String?): Float {
    var aspect by remember(coverPath) { mutableFloatStateOf(1f) }
    LaunchedEffect(coverPath) {
        aspect = withContext(Dispatchers.IO) { coverAspect(coverPath) }
    }
    return aspect
}

private fun coverAspect(path: String?): Float {
    if (path.isNullOrBlank()) return 1f
    return runCatching {
        if (!File(path).exists()) return 1f
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        // Guard against a failed decode (-1) and against absurd ratios that would make the mini
        // player's cover cap a sliver or a banner.
        if (w <= 0 || h <= 0) 1f else (w.toFloat() / h).coerceIn(0.5f, 2f)
    }.getOrDefault(1f)
}
