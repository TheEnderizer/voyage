package com.betteraudio.ui.immersive.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.player.LocalCoverBoundsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover paths [com.betteraudio.ui.components.AppBlurredBackdrop] is currently showing at the
 * app root -- provided once from MainActivity so any Immersive-theme surface (mini player pill,
 * floating nav pill) can render a "liquid glass" look derived from the same cover.
 *
 * There is no real backdrop-filter API in Compose (no way to sample already-rendered content behind
 * a layer), so this draws its OWN copy of the cover art: a directional vertical smear done on the
 * CPU (see [verticalSmudge]) with a smooth GPU `Modifier.blur()` layered on top of the DISPLAYED
 * image (not baked into the bitmap) -- "a vertical smudging effect and a blur over it". This
 * intentionally does NOT try to pixel-align with the real backdrop behind the pill (an earlier
 * version did, by rendering a full-screen-sized copy offset by the pill's own position -- fragile,
 * and abandoned) -- since the result is heavily smudged anyway, and the Home grid behind is built
 * from the same cover art, an approximate (non-aligned) sample reads as "glass over this content"
 * just as well while being far simpler.
 *
 * Two things went wrong before landing on this split: a directional (asymmetric X/Y radius)
 * `BlurEffect` applied via `graphicsLayer { scaleX; scaleY; renderEffect }` in one block rendered
 * completely sharp on-device (no blur at all) -- but [AppBlurredBackdrop]'s plain SYMMETRIC
 * `Modifier.blur()`, applied as its own separate modifier node chained after a `graphicsLayer`
 * scale (not combined into one block), works fine on the same device. So the directional smear
 * stays CPU-side (cheap, always works, no GPU dependency), and only the final smoothing pass uses
 * the proven-working `Modifier.blur()` idiom, on its own node.
 */
data class ImmersiveBackdropPaths(
    val coverPath: String?,
    val bakedPath: String?,
    // User's default widget cover (Settings → Widget) — last-resort fallback so a fresh install
    // (nothing ever played, coverPath/bakedPath both null) still has SOME image to smudge instead
    // of falling through to the flat, see-through fill (the "clear pill" bug).
    val defaultCoverPath: String? = null
)

val LocalImmersiveBackdrop = compositionLocalOf { ImmersiveBackdropPaths(null, null) }

/** Settings → Theme → "Dynamic pills": when true, the glass samples whichever book cover is
 *  currently scrolled underneath the pill (via [com.betteraudio.ui.player.CoverBoundsRegistry])
 *  instead of the fixed now-playing/last-played backdrop. Provided once from MainActivity. */
val LocalDynamicPillsEnabled = compositionLocalOf { false }

private const val SMUDGE_CACHE_LIMIT = 8

/** Bounded path->bitmap cache so re-showing a recently-seen cover (e.g. scrolling back and forth
 *  with Dynamic pills on) doesn't re-decode/re-scale it. Evicted entries are simply dropped (not
 *  recycled) — a Crossfade frame in flight may still hold a reference to one, and recycling while
 *  still on-screen would crash; letting GC reclaim it is the safe tradeoff for a handful of small
 *  bitmaps. */
private val smudgeCache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > SMUDGE_CACHE_LIMIT
}

/**
 * Produces (and caches) a heavily vertically-smeared, softly horizontally-blurred version of the
 * cover at [path] via a cheap downscale/upscale trick — decode small, shrink drastically (much more
 * on Y than X), then scale back up with bilinear filtering. See the file-level doc for why this
 * replaces a live RenderEffect blur.
 */
private suspend fun verticalSmudge(path: String): Bitmap? = withContext(Dispatchers.Default) {
    synchronized(smudgeCache) { smudgeCache[path] }?.let { return@withContext it }
    val result = runCatching {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val src = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
        try {
            // Produce a TINY bitmap and stop there — the caller's Image upsamples it to pill size
            // with bilinear filtering, and that upscale IS the blur. This deliberately does NOT use
            // Modifier.blur()/RenderEffect (which renders see-through-or-sharp on this device — see
            // the file doc) nor pre-upscale back to full size; a ~40px source scaled up to a wide
            // pill turns into big soft blobs of the cover's colors with no recognizable shape, which
            // is exactly "the colors of what's behind without the detail". A little more crushing on
            // Y keeps a hint of the original vertical "smudge" character.
            val tinyW = (src.width / 6).coerceAtLeast(4)
            val tinyH = (src.height / 10).coerceAtLeast(3)
            Bitmap.createScaledBitmap(src, tinyW, tinyH, true)
        } finally {
            src.recycle()
        }
    }.getOrNull()
    if (result != null) synchronized(smudgeCache) { smudgeCache[path] = result }
    result
}

/**
 * Drop-in replacement for a frosted `Surface` on the Immersive theme: same shape/shadow, but the
 * fill is a vertically-smudged, darkened sample of a cover instead of a flat translucent color.
 * Falls back to a plain translucent fill when there's no cover art yet (fresh install) or the
 * smear fails to decode.
 */
@Composable
fun GlassPillSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.White,
    shadowElevation: Dp = 10.dp,
    content: @Composable () -> Unit
) {
    val backdrop = LocalImmersiveBackdrop.current
    // Last resort: the user's default widget cover, so a fresh install (nothing ever played) still
    // has an image to smudge instead of falling through to the flat, see-through fallback fill.
    val backdropPath = backdrop.coverPath ?: backdrop.bakedPath ?: backdrop.defaultCoverPath
    val dynamicPillsEnabled = LocalDynamicPillsEnabled.current
    val registry = LocalCoverBoundsRegistry.current
    var ownCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // Dynamic pills: sample whatever book cover is currently scrolled under this pill's own
    // center point. derivedStateOf is essential here, not decorative — coverPathUnder reads every
    // published rect's live State (so this recalculates on every scroll frame across the whole
    // grid), but recomposition should only actually happen when the RESULT (which book, if any,
    // is under the pill) changes — otherwise this pill would recompose on every scroll pixel
    // instead of only when the book underneath actually changes.
    val imagePath by remember(dynamicPillsEnabled, backdropPath) {
        derivedStateOf {
            if (dynamicPillsEnabled) registry.coverPathUnder(ownCenter) ?: backdropPath else backdropPath
        }
    }

    var smudged by remember { mutableStateOf<Bitmap?>(null) }
    // Local val so the null-check below smart-casts inside the LaunchedEffect/Crossfade lambdas —
    // Kotlin smart-casts don't reliably propagate through a `by remember { derivedStateOf {} }`
    // delegated property.
    val currentImagePath = imagePath
    LaunchedEffect(currentImagePath) {
        // Don't reset to null first — keep showing the previous smear until the new one is ready,
        // so switching covers crossfades instead of flashing the fallback fill in between.
        if (currentImagePath != null) smudged = verticalSmudge(currentImagePath)
    }

    Box(
        modifier
            .shadow(shadowElevation, shape, clip = false)
            .clip(shape)
            .onGloballyPositioned { ownCenter = it.boundsInRoot().center }
    ) {
        // Opaque base drawn UNCONDITIONALLY, first — the pill is never see-through even if the
        // smear failed to decode or (as happened on this device) the smudge Image doesn't paint.
        // This is the same fill that already renders correctly on a fresh install with no cover.
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        val currentSmudge = smudged
        if (currentSmudge != null) {
            // The tiny smudge bitmap (see verticalSmudge) upscaled to fill the pill: ContentScale.Crop
            // + the default bilinear FilterQuality turns a ~40px source into big soft blobs of the
            // cover's colors — the blur is the upscale itself, no Modifier.blur()/RenderEffect (which
            // is unreliable here). Crossfades so a new/scrolled cover changes smoothly, not a pop.
            Crossfade(targetState = currentSmudge, animationSpec = tween(450), label = "glassCover") { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
            }
        }
        // Darken so pill content stays legible over bright parts of the smudged cover.
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.30f)))
        // Subtle top-lit edge for the "glass" read.
        Box(
            Modifier
                .matchParentSize()
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0f))
                    ),
                    shape = shape
                )
        )
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
