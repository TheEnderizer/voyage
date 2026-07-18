package com.betteraudio.ui.material

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavBackStackEntry
import com.betteraudio.ui.theme.MotionTokens
import kotlin.math.roundToInt

/**
 * Material You-specific motion — the animation-layer half of the theme split (see CLAUDE.md's
 * theming section; Immersive's half is ui/immersive/ImmersiveMotion.kt). Reuses the shared
 * mechanics in ui/player/PlayerMorph.kt (deferred-read graphicsLayer transforms) but with
 * container-transform behavior instead of Immersive's crossfade/reveal look.
 */

// ── NavHost screen transitions ────────────────────────────────────────────────

fun AnimatedContentTransitionScope<NavBackStackEntry>.materialEnter(): EnterTransition =
    fadeIn(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleIn(initialScale = 0.92f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

fun AnimatedContentTransitionScope<NavBackStackEntry>.materialExit(): ExitTransition =
    fadeOut(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleOut(targetScale = 0.96f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

/** Pop-enter for the screen being revealed underneath — mimics the system predictive-back "peek":
 *  it's already slightly shrunk and fades up to full size/opacity as the gesture (or a plain back
 *  press) completes. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.materialPopEnter(): EnterTransition =
    fadeIn(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleIn(initialScale = 0.94f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

/** Pop-exit for the screen being dismissed — scales down and fades, matching the predictive-back
 *  "shrink away" feel navigation-compose 2.8's gesture-seeking already drives frame-by-frame. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.materialPopExit(): ExitTransition =
    fadeOut(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleOut(targetScale = 0.90f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

// ── Mini player → full player: container growth ───────────────────────────────

/**
 * Grows this element from [source]'s bounds/radius (progress 0) to its own natural full-size
 * layout (progress 1) — independent X/Y scale (unlike [com.betteraudio.ui.player.morphFrom],
 * which is uniform and made for images). Used for the full player's plain-color background, which
 * has no aspect-ratio/content-scale concerns, so a wide-short pill can freely grow into a
 * full-screen rect without any "distortion" the way a stretched photo would show.
 *
 * [ownSizePx] is this element's TRUE (un-parked) size in px, tracked explicitly by the caller
 * (e.g. from the outer sheet's own `onSizeChanged`) rather than measured here via
 * `onGloballyPositioned` — this element lives inside an ancestor that's itself translated
 * off-screen while collapsed (see PlayerSheet's parking `graphicsLayer`), and `boundsInRoot()`
 * on a descendant of a transformed-but-not-yet-recomposed ancestor was not reliably reporting the
 * un-parked size/position, which made this background render as an opaque box sitting on top of
 * (and hiding) the mini bar even while fully collapsed. [source] is assumed to already be in the
 * same root coordinate space this element would occupy if it weren't parked (i.e. root (0,0) to
 * (ownSizePx.width, ownSizePx.height)).
 */
@Composable
fun Modifier.expandingContainer(
    source: State<Rect>,
    ownSizePx: State<androidx.compose.ui.geometry.Size>,
    progress: State<Float>,
    sourceRadius: Dp,
    destRadius: Dp = 0.dp,
): Modifier {
    return this.graphicsLayer {
        val p = progress.value
        val src = source.value
        val own = ownSizePx.value
        if (p >= 1f || src == Rect.Zero || own.width <= 0f || own.height <= 0f) {
            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
            shape = RoundedCornerShape(destRadius)
            clip = true
            return@graphicsLayer
        }
        transformOrigin = TransformOrigin(0f, 0f)
        val sx = lerp(src.width / own.width, 1f, p)
        val sy = lerp(src.height / own.height, 1f, p)
        scaleX = sx
        scaleY = sy
        // Own natural (unparked) position is always root (0,0) — this element fills the
        // full-player container, which itself starts at the app's root origin.
        translationX = src.left * (1f - p)
        translationY = src.top * (1f - p)
        val onScreenRadius = lerpDp(sourceRadius, destRadius, p.coerceIn(0f, 1f))
        // The mini bar is nearly full-width but very short, so sx and sy diverge hugely (sx stays
        // near 1, sy starts near 0) — a SINGLE divisor (e.g. max(sx,sy)) underscales one axis,
        // making that axis's on-screen radius collapse toward 0 almost immediately (corners look
        // "square" from the start instead of smoothly shrinking). Compensating each axis with its
        // OWN scale factor keeps the on-screen radius equal to onScreenRadius on BOTH axes
        // throughout, at the cost of an elliptical (not circular) corner while sx != sy — visually
        // still round, unlike the collapsed-to-square look this replaces.
        val radiusXPx = (onScreenRadius / sx.coerceAtLeast(0.001f)).toPx()
        val radiusYPx = (onScreenRadius / sy.coerceAtLeast(0.001f)).toPx()
        shape = object : Shape {
            override fun createOutline(
                size: androidx.compose.ui.geometry.Size,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                density: androidx.compose.ui.unit.Density
            ): androidx.compose.ui.graphics.Outline = androidx.compose.ui.graphics.Outline.Rounded(
                androidx.compose.ui.geometry.RoundRect(
                    rect = androidx.compose.ui.geometry.Rect(androidx.compose.ui.geometry.Offset.Zero, size),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusXPx, radiusYPx)
                )
            )
        }
        clip = true
    }
}

// ── Grid card → Book Info: true crop morph ────────────────────────────────────

/**
 * Morphs a cover image from a library grid card's exact on-screen crop (any aspect ratio, e.g.
 * the grid's 0.72 portrait cards) into this element's own natural square crop, with NO reload and
 * no aspect "pop" partway through. Unlike [com.betteraudio.ui.player.morphFrom] (which scales an
 * already-cropped image as a rigid box — fine when source/destination share an aspect, but wrong
 * here since a square crop can never reveal a portrait crop's edges through pure scaling), this
 * drives the actual *layout size* of the image element from an [androidx.compose.ui.layout]
 * measurement each frame, so Coil's own `ContentScale.Crop` recomputes the correct crop window for
 * the current size — using the SAME decoded/cached bitmap (see the shared Coil `memoryCacheKey` on
 * both the grid card and this cover), so nothing is redecoded, only redrawn.
 *
 * [parentBounds] is the available-space rect (root coords) the cover would naturally occupy at
 * progress 1 — must come from a STABLE ancestor measurement (one that doesn't change size as this
 * modifier's own size animates), so the natural end position is known without a layout feedback
 * loop. Deliberately trades this composable's per-frame-recomposition-freedom (unlike morphFrom's
 * pure graphicsLayer approach) for correctness: only this one small leaf composable recomposes
 * while the sheet is expanding/collapsing, which is a low, bounded cost.
 */
@Composable
fun Modifier.coverCropMorph(
    parentBounds: State<Rect>,
    source: State<Rect>,
    progress: State<Float>,
    sourceRadius: Dp,
    destRadius: Dp,
): Modifier {
    val density = LocalDensity.current
    val p = progress.value.coerceIn(0f, 1f)
    val parent = parentBounds.value

    // `parentBounds` is populated by the cover Box's onGloballyPositioned callback, which fires
    // during LAYOUT — one phase after this composable function's own body (COMPOSITION) reads
    // `parentBounds.value` for the very first frame the Box exists. That first read is
    // unavoidably the initial Rect.Zero, but curLeft/curTop below are computed as an OFFSET
    // relative to `parent.left`/`parent.top` — with parent.left wrongly 0 (not the Box's real
    // root position), the offset would place the cover at `src.left` pixels from whatever the
    // Box's TRUE root position turns out to be, not at `src.left` in ROOT space — a visible
    // one-frame misplacement (the reported "doesn't start at the same dimensions/position" bug)
    // that then snaps to the correct spot once `parentBounds` catches up next frame. Render
    // invisible for these few frames instead: the grid card's own cover is still showing under it
    // (isMorphHidden's p > 0.02f threshold isn't reached this early in the spring), so nothing is
    // visibly missing, and the very next real frame starts already glued to the right position.
    if (parent.width <= 0f || parent.height <= 0f) {
        return this@coverCropMorph
            .size(0.dp)
            .graphicsLayer { alpha = 0f }
    }

    val side = maxOf(minOf(parent.width, parent.height), 1f)
    val destLeft = parent.left + (parent.width - side) / 2f
    val destTop = parent.top + (parent.height - side) / 2f

    val src = source.value.takeIf { it != Rect.Zero && it.width > 0f && it.height > 0f && p < 0.999f }
    val curLeft: Float
    val curTop: Float
    val curW: Float
    val curH: Float
    if (src == null) {
        curLeft = destLeft; curTop = destTop; curW = side; curH = side
    } else {
        curLeft = lerp(src.left, destLeft, p)
        curTop = lerp(src.top, destTop, p)
        curW = lerp(src.width, side, p)
        curH = lerp(src.height, side, p)
    }
    val radius = lerpDp(sourceRadius, destRadius, p)

    return with(density) {
        this@coverCropMorph
            .size(curW.toDp(), curH.toDp())
            .offset { IntOffset((curLeft - parent.left).roundToInt(), (curTop - parent.top).roundToInt()) }
            .clip(RoundedCornerShape(radius))
    }
}
