package com.betteraudio.ui.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp

/**
 * Drives the shared-element morph between the mini player and the full player.
 *
 * [progress] is the sheet expansion (0 = mini bar, 1 = full player). [miniCover]/[miniTitle]/
 * [miniControls] are the bounds a morphing element travels FROM, in root coordinates — normally
 * the mini bar's, but [PlayerSheet] repoints [miniCover] at a library grid card's bounds (via
 * [CoverBoundsRegistry]) when there's no live mini bar (e.g. opening the full player directly from
 * Book Info's Resume button, or a fresh unplayed book), so the grid → full player transition still
 * morphs from the tapped card. This is separate from Book Info's own grid → Book Info morph (its
 * own [morphFrom] usage in BookInfoScreen.kt).
 * [coverSourceRadius] is that source's corner radius, for [morphFrom]'s clip-radius animation.
 * All values are [State] so readers can defer reads into graphicsLayer/draw lambdas and avoid
 * per-frame recomposition.
 */
class PlayerExpandTransition(
    val progress: State<Float>,
    val miniCover: State<Rect>,
    val miniTitle: State<Rect>,
    val miniControls: State<Rect>,
    val coverSourceRadius: Dp = 12.dp,
    // Material You only: the mini bar's own surface bounds/radius, so the full player's
    // background can grow out of the pill instead of crossfading (see MaterialMotion.kt's
    // expandingContainer). Immersive ignores these (defaults keep it a no-op).
    val miniBar: State<Rect> = stateOfZeroRect,
    val miniBarRadius: Dp = 0.dp,
    // True when [miniCover] is a library grid card's bounds (Book Info opened from the grid)
    // rather than the live mini-player's cover slot — Material You uses this to pick the
    // aspect-aware coverCropMorph instead of the mini-bar's morphFrom (see MaterialMotion.kt).
    val sourceIsGridCard: Boolean = false,
    /** The mini bar's author line — the full player's author travels out of it, the way the title
     *  travels out of [miniTitle]. Zero when the mini bar has no author to show. */
    val miniAuthor: State<Rect> = stateOfZeroRect,
)

private val stateOfOne = mutableStateOf(1f)
private val stateOfZeroRect = mutableStateOf(Rect.Zero)

/** Defaults to a settled (fully expanded) transition, so the player renders normally. */
val LocalPlayerExpand = compositionLocalOf {
    PlayerExpandTransition(stateOfOne, stateOfZeroRect, stateOfZeroRect, stateOfZeroRect)
}

/**
 * Morphs this element from [source] (root coords) into its own natural layout position as
 * [progress] goes 0 → 1: uniform scale from the source's size plus a translation from the
 * source's position. [anchorTopLeft] pins the element's top-left to the source's (used for the
 * cover backdrop, whose image sits at its top); otherwise centers are matched. [byWidth] scales
 * by width ratio instead of height ratio. [fadeIn] starts the element fully transparent (instead
 * of opaque) at progress 0 and ramps to fully visible by progress ~0.5 — used for the cover,
 * which has no visible counterpart in the mini bar to morph *out of* (the mini bar's own cover
 * image keeps rendering on top of it throughout the drag), so popping in at full opacity would
 * look like a hard cut instead of a growth.
 *
 * When [sourceRadius]/[destRadius] are both given, the element's clip corner radius is animated
 * alongside the scale/translate — in the SAME graphicsLayer pass, via [androidx.compose.ui.graphics.GraphicsLayerScope]'s
 * `shape`/`clip`, so the mask stays glued to the image as one object instead of a separately
 * clipped, non-animated overlay. The interpolated radius is defined in ON-SCREEN dp, then divided
 * by the current layer scale before being handed to the shape (which is specified in the layer's
 * own pre-scale local space) so the visible rounding matches [sourceRadius] at progress 0 and
 * [destRadius] at progress 1 regardless of how much the layer is scaled down in between.
 *
 * AN-9 (Gate AN): `own` is written from [androidx.compose.ui.layout.onGloballyPositioned] — a
 * layout-phase callback writing composition state, the standard Compose feedback-loop hazard.
 * Benign at all current call sites (both themes' player/bookinfo/series screens): `own` is only
 * ever read back inside the [androidx.compose.ui.graphics.graphicsLayer] lambda below, and a
 * graphicsLayer-only transform doesn't trigger a new layout pass, so there's no loop. That's a
 * property of today's call sites, not a guarantee of the API — a future caller that reads `own`
 * anywhere layout-affecting (a `Modifier.layout {}`, a `size()` derived from it, etc.) would
 * reintroduce the hazard.
 *
 * Do NOT "harden" this to [androidx.compose.ui.layout.onPlaced]: that was tried and reverted. It
 * fires DURING the placement pass, so `boundsInRoot()` resolves the size but walks an ancestor
 * chain that hasn't been positioned yet and returns a rect pinned to the root origin — which for
 * a morph source means every transition growing out of the top-left corner of the display. The
 * two callbacks do not carry the same bounds data.
 */
@Composable
fun Modifier.morphFrom(
    source: State<Rect>,
    progress: State<Float>,
    anchorTopLeft: Boolean = false,
    byWidth: Boolean = false,
    fadeIn: Boolean = false,
    sourceRadius: Dp? = null,
    destRadius: Dp? = null
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { own = Rect(it.positionInRoot(), it.size.toSize()) }
        .graphicsLayer {
            val p = progress.value
            alpha = if (fadeIn) (p / 0.5f).coerceIn(0f, 1f) else 1f
            val src = source.value
            if (p >= 1f || src == Rect.Zero || own.width <= 0f || own.height <= 0f) {
                scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                if (destRadius != null) {
                    shape = RoundedCornerShape(destRadius)
                    clip = true
                }
                return@graphicsLayer
            }
            val ratio = if (byWidth) src.width / own.width else src.height / own.height
            val s = lerp(ratio, 1f, p)
            scaleX = s
            scaleY = s
            if (sourceRadius != null && destRadius != null) {
                val onScreenRadius = lerpDp(sourceRadius, destRadius, p.coerceIn(0f, 1f))
                shape = RoundedCornerShape(if (s > 0.001f) onScreenRadius / s else onScreenRadius)
                clip = true
            }
            if (anchorTopLeft) {
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = (src.left - own.left) * (1f - p)
                translationY = (src.top - own.top) * (1f - p)
            } else {
                translationX = (src.center.x - own.center.x) * (1f - p)
                translationY = (src.center.y - own.center.y) * (1f - p)
            }
        }
}

/**
 * Grows the VISIBLE WINDOW of a whole screen out of [source] (root coords, the tapped grid card's
 * cover) at progress 0 into this element's own full bounds at progress 1 — the other half of the
 * cover morph: [morphFrom] carries the cover itself from the card to its new slot, this carries
 * everything *around* it (background, header, info panel) so the page unfolds out of that same
 * image instead of popping in behind a flying cover.
 *
 * Deliberately a CLIP, not a scale: the page is laid out at its final full-screen size the whole
 * time and only the window into it interpolates, so nothing inside is ever drawn squashed or at a
 * transient size (which would re-wrap text and re-measure the synopsis every frame). Combined with
 * [expandReveal] on the individual elements — the window uncovers the page, the elements fade up
 * inside it — this is the standard container-transform pairing.
 *
 * At progress 0 the window is *exactly* the card's rect and radius, and the cover morphing inside
 * it is exactly the card's cover (same bounds, same 0.72 aspect crop, same Coil `memoryCacheKey`
 * → the same decoded bitmap). So the first frame of the transition is pixel-identical to the grid
 * the user just tapped, and the card underneath — hidden by [CoverBoundsRegistry.isMorphHidden]
 * the moment progress leaves 0 — never shows through as a second copy of the cover.
 *
 * Both interpolations are per-edge linear from the same starting rect, which is what keeps the
 * cover inside the window for the entire animation: for every edge, `lerp(card, coverSlot, p)` is
 * bounded by `lerp(card, fullScreen, p)` as long as the cover's final slot is on-screen. [morphFrom]
 * qualifies — its uniform scale reduces to the same per-edge lerp when source and destination share
 * an aspect ratio, as the grid card and both themes' Book Info covers do.
 *
 * Reads (progress/source/own) are all deferred into the [graphicsLayer] block, per the same
 * discipline as [morphFrom] — the clip re-evaluates per frame without recomposing anything.
 */
@Composable
fun Modifier.containerReveal(
    source: State<Rect>,
    progress: State<Float>,
    sourceRadius: Dp,
    destRadius: Dp = 0.dp,
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { own = Rect(it.positionInRoot(), it.size.toSize()) }
        .graphicsLayer {
            val raw = progress.value
            val src = source.value
            if (raw >= 1f || src == Rect.Zero || size.width <= 0f || size.height <= 0f) {
                clip = false
                shape = RectangleShape
                return@graphicsLayer
            }
            // Clamped low: the closing spring can undershoot below 0, which would extrapolate the
            // window to *smaller* than the card (and, far enough past 0, inside out).
            val p = raw.coerceAtLeast(0f)
            // source is in ROOT coords; the clip shape is in this layer's own local space.
            val srcLeft = src.left - own.left
            val srcTop = src.top - own.top
            val window = Rect(
                left   = lerp(srcLeft, 0f, p),
                top    = lerp(srcTop, 0f, p),
                right  = lerp(srcLeft + src.width, size.width, p),
                bottom = lerp(srcTop + src.height, size.height, p),
            )
            shape = RevealWindowShape(window, lerpDp(sourceRadius, destRadius, p).toPx())
            clip = true
        }
}

/** The interpolated window [containerReveal] clips to. A uniform-radius [RoundRect] is `isSimple`,
 *  so Compose's outline resolver hands it to the RenderNode as a plain `setRoundRect` clip rather
 *  than falling back to a per-frame Path clip on a full-screen layer — the exact cost the
 *  [com.betteraudio.ui.material.motion.morphingContainer] KDoc documents the old `expandingContainer`
 *  paying for its asymmetric radii. */
private class RevealWindowShape(private val window: Rect, private val radiusPx: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(window, CornerRadius(radiusPx, radiusPx)))
}

/**
 * Reveal for elements that have no mini-player counterpart: they start small and transparent
 * and expand/fade in over the second half of the opening gesture, sliding up from a downward
 * offset into their natural position as they fade — as if they too were hidden at their spot
 * near the mini bar, rather than just popping in place. [offset] is the on-screen downward
 * distance at progress 0; pass 0.dp (the default) to keep the old in-place reveal.
 */
fun Modifier.expandReveal(progress: State<Float>, offset: Dp = EXPAND_REVEAL_OFFSET): Modifier = graphicsLayer {
    val p = progress.value
    alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val s = 0.8f + 0.2f * p.coerceIn(0f, 1f)
    scaleX = s
    scaleY = s
    translationY = offset.toPx() * (1f - p.coerceIn(0f, 1f))
}

/** Default downward offset [expandReveal] elements start from. Previously a raw 40f px constant
 *  (this dp value, ~14dp, at a "typical" density) — AN-10 in the Gate AN plan: the reveal travelled
 *  a different on-screen distance per device density. Fixed in Dp here and converted with
 *  [Dp.toPx] inside [expandReveal]'s own graphicsLayer block (GraphicsLayerScope extends Density),
 *  so [expandReveal] still doesn't need to be @Composable to read LocalDensity. */
private val EXPAND_REVEAL_OFFSET = 14.dp
