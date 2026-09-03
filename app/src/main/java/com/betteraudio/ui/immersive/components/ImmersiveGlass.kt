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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.player.LocalCoverBoundsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The cover paths [com.betteraudio.ui.components.AppBlurredBackdrop] is currently showing at the
 * app root -- provided once from MainActivity so any Immersive-theme surface (mini player pill,
 * floating nav pill) can render a "liquid glass" look derived from the same cover.
 *
 * **This is now the FALLBACK path.** With Settings → Theme → "Dynamic pills" on (and API 31+) the
 * pills sample the real rendered content beneath them via a recorded `GraphicsLayer` — see
 * [BackdropGlass] — which is a genuine backdrop blur that tracks whatever scrolls underneath.
 * Everything below applies when that is switched off or unavailable.
 *
 * Compose has no `backdrop-filter` modifier, so this draws its OWN copy of the cover art: a
 * directional vertical smear done on the CPU (see [verticalSmudge]) with a smooth GPU
 * `Modifier.blur()` layered on top of the DISPLAYED image (not baked into the bitmap) -- "a
 * vertical smudging effect and a blur over it". It intentionally does NOT try to pixel-align with
 * the real backdrop behind the pill (an earlier version did, by rendering a full-screen-sized copy
 * offset by the pill's own position -- fragile, and abandoned) -- since the result is heavily
 * smudged anyway, and the Home grid behind is built from the same cover art, an approximate
 * (non-aligned) sample reads as "glass over this content" just as well while being far simpler.
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
 *  instead of the fixed now-playing/last-played backdrop. Provided once from MainActivity.
 *
 *  As of the cover-first pass this ALSO gates the real backdrop blur (see [BackdropGlass]): with
 *  it on, a capable device samples the actual pixels under each pill rather than any cover file at
 *  all, and this cover-hit-testing path is what remains for everything else. Turning the toggle
 *  off is the user's way to switch the whole effect back to a static smudge. */
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
 * Progress drawn along a pill's own rounded outline instead of as a straight bar beneath it.
 *
 * A `LinearProgressIndicator` pinned to the bottom of a `RoundedCornerShape(percent = 50)` is
 * geometrically wrong — the bar runs the full width while the shape it belongs to curves away
 * from it at both ends, so the last stretch of progress reads as "done" while the pill's caps sit
 * empty. Tracing the outline puts the progress *on* the object.
 *
 * This is the same recipe as `WidgetPainter.drawPerimeterProgressBar` (which does it for the
 * widget's RING / SQUARE / ROUNDED_SQUARE progress shapes): build the outline as a `Path`, then
 * walk `fraction * length` of it with `PathMeasure`. Compose's `drawBehind` here rather than a
 * `Canvas`, and [progress] is a lambda read only inside that draw lambda — the value ticks twice
 * a second, and reading it in composition would recompose the whole pill on every tick.
 */
@Composable
fun PillPerimeterProgress(
    progress: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.5.dp
) {
    Box(
        modifier.drawBehind {
            val w = strokeWidth.toPx()
            val inset = w / 2f
            val radius = ((size.height - w) / 2f).coerceAtLeast(0f)
            val outline = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = inset,
                        top = inset,
                        right = size.width - inset,
                        bottom = size.height - inset,
                        cornerRadius = CornerRadius(radius, radius)
                    )
                )
            }
            val measure = PathMeasure().apply { setPath(outline, false) }
            val length = measure.length
            val fraction = progress().coerceIn(0f, 1f)
            if (length <= 0f || fraction <= 0f) return@drawBehind
            val travelled = Path()
            measure.getSegment(0f, length * fraction, travelled, true)
            drawPath(travelled, color, style = Stroke(width = w, cap = StrokeCap.Round))
        }
    )
}

/**
 * Dissolves a child's trailing edge over [fade] instead of ending it on a cut.
 *
 * The Immersive mini player's cover is the pill's own leading cap, so its right-hand edge is the
 * one place two different materials meet — artwork against glass. A hard vertical line there (or
 * a small corner radius trying to soften one) reads as a photo pasted onto a pill. Ramping the
 * cover's alpha to nothing over the last [fade] lets the pill's own fill take over, and because
 * the fill *is* a smudge of that same cover, the two sides meet in the same colours.
 *
 * `CompositingStrategy.Offscreen` is required: `BlendMode.DstIn` needs a real layer to punch alpha
 * out of, and without it the mask paints as a black gradient instead of erasing (same constraint
 * as [com.betteraudio.ui.immersive.heroWindowMask]).
 */
fun Modifier.fadeTrailingEdge(fade: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (size.width <= 0f) return@drawWithContent
        val start = ((size.width - fade.toPx()) / size.width).coerceIn(0f, 1f)
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Black,
                start to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
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
    /**
     * Whether this surface may sample the live backdrop capture when Dynamic pills is on.
     *
     * True for surfaces that genuinely float over the app (the nav pill, the mini player). False
     * for glass that lives INSIDE the player sheet — the sheet is drawn outside the recorded
     * subtree on purpose, so such a surface would sample the Home grid sitting behind the sheet
     * rather than the player's own cover, and show the wrong thing entirely.
     */
    sampleBackdrop: Boolean = true,
    /**
     * Whether to stroke the 1dp top-lit rim that gives a pill its "cut glass" read.
     *
     * On by default, and right for the nav pill: it is a small object that needs an edge to be
     * read as an object at all. The mini player turned it off — at 64dp tall and full-bleed wide
     * it is the largest floating surface in the app, and at that size the rim stops reading as
     * light catching an edge and starts reading as a drawn outline around a card. Without it the
     * pill is defined by its own shadow and by the darkening of the glass against the backdrop,
     * which is how the rest of the theme separates surfaces, and the only line left on the shape
     * is the progress arc — which is the one line that means something.
     */
    edgeLight: Boolean = true,
    content: @Composable () -> Unit
) {
    val backdrop = LocalImmersiveBackdrop.current
    // Last resort: the user's default widget cover, so a fresh install (nothing ever played) still
    // has an image to smudge instead of falling through to the flat, see-through fallback fill.
    val backdropPath = backdrop.coverPath ?: backdrop.bakedPath ?: backdrop.defaultCoverPath
    val dynamicPillsEnabled = LocalDynamicPillsEnabled.current
    val registry = LocalCoverBoundsRegistry.current
    var ownCenter by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // ── Real backdrop sampling ────────────────────────────────────────────────────────────
    // With "Dynamic pills" on (and API 31+), the pill shows a blurred, vertically-smudged copy of
    // the pixels ACTUALLY beneath it, re-recorded every frame — so it changes continuously as the
    // library scrolls under it. See BackdropGlass.kt. Everything below this is the fallback for
    // when that is switched off or unavailable: a smudge of a cover FILE, picked by hit-testing
    // card bounds, which only ever approximated what was really behind.
    val capture = LocalBackdropCapture.current
    val useRealBackdrop = sampleBackdrop && dynamicPillsEnabled && capture != null
    var ownTopLeft by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    // Dynamic pills (fallback path): sample whatever book cover is currently scrolled under this
    // pill's own center point. derivedStateOf is essential here, not decorative — coverPathUnder
    // reads every published rect's live State (so this recalculates on every scroll frame across
    // the whole grid), but recomposition should only actually happen when the RESULT (which book,
    // if any, is under the pill) changes — otherwise this pill would recompose on every scroll
    // pixel instead of only when the book underneath actually changes.
    val imagePath by remember(dynamicPillsEnabled, backdropPath, useRealBackdrop) {
        derivedStateOf {
            when {
                useRealBackdrop -> null   // nothing to decode; the live capture is the fill
                dynamicPillsEnabled -> registry.coverPathUnder(ownCenter) ?: backdropPath
                else -> backdropPath
            }
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
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                ownCenter = bounds.center
                ownTopLeft = bounds.topLeft
            }
    ) {
        // Opaque base drawn UNCONDITIONALLY, first — the pill is never see-through even if the
        // smear failed to decode or (as happened on this device) the smudge Image doesn't paint.
        // This is the same fill that already renders correctly on a fresh install with no cover.
        Box(
            Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        if (useRealBackdrop && capture != null) {
            // The sampler is deliberately LARGER than the pill (see overdraw): the blur needs real
            // content past the visible edge or it invents it, and invented edges change as the
            // content scrolls. It tracks its own bounds rather than reusing the pill's, since
            // overdraw shifts it up and left by the bleed.
            var sampleTopLeft by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            Box(
                Modifier
                    .matchParentSize()
                    .overdraw(BLUR_X, BLUR_Y)
                    .onGloballyPositioned { sampleTopLeft = it.boundsInRoot().topLeft }
                    .backdropGlass(capture, rootOffset = { sampleTopLeft })
            )
        }
        val currentSmudge = smudged
        if (!useRealBackdrop && currentSmudge != null) {
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
        // Darken so pill content stays legible over bright parts of the smudged cover. This used
        // to be a flat `Color.Black @ 0.30`, which threw away the whole point of the smear above:
        // every pill landed the same neutral grey no matter which book was playing. It is now a
        // bottom-weighted veil in the cover's OWN dark (ImmersiveStyle.glassVeil), averaging to
        // roughly the same darkness — so contrast is unchanged while the pill finally keeps the
        // artwork's hue, and its top edge reads lighter than its base like real glass.
        Box(Modifier.matchParentSize().background(ImmersiveStyle.glassVeil()))
        // Subtle top-lit edge for the "glass" read. See [edgeLight] for why the mini player
        // opts out.
        if (edgeLight) {
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
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
