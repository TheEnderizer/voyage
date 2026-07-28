package com.betteraudio.ui.material

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
        // AN-5 (Gate AN): this anonymous Shape is rebuilt every frame, but hoisting it to a data
        // class for value equality was evaluated and buys nothing — radiusXPx/radiusYPx derive
        // from sx/sy above, which change every frame by construction while the gesture is active,
        // so a value-equality cache would still miss every frame. The real per-frame cost is that
        // radiusXPx != radiusYPx for most of the gesture, so this RoundRect is never isSimple and
        // Compose can't use a hardware render-node outline — it falls back to a Path-based clip on
        // a full-screen layer regardless of how this Shape is constructed. Left as measured/
        // documented rather than "fixed": the alternative (accepting circular corners so the
        // outline stays isSimple) is a visual trade a maintainer should choose deliberately, not a
        // silent behavior change.
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

// ── Grid card → full player (opened directly, no live mini bar): true crop morph ──────────────
// AN-12 (Gate AN): this section and coverCropMorph's call site (PlayerScreen.kt) used to say
// "Book Info" here. Book Info is a separate overlay with its own morphFrom-based cover (see
// BookInfoScreen.kt) — it never calls coverCropMorph. That stale label previously misled AN-3's
// own verify step into reproducing via Book Info, a path that never touches this code.

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
 * loop. Every per-frame value (`progress`/`parentBounds`/`source`) is read inside [layout] (size +
 * position) or [graphicsLayer] (the clip radius) — both are deferred, measure/draw-phase reads,
 * so per-frame remeasure and redraw are the only per-frame cost; nothing here recomposes (an
 * earlier version read these directly in the function body, which is composition-time and
 * invalidated the entire calling Scaffold every frame — see AN-3 in the Gate AN plan).
 */
@Composable
fun Modifier.coverCropMorph(
    parentBounds: State<Rect>,
    source: State<Rect>,
    progress: State<Float>,
    sourceRadius: Dp,
    destRadius: Dp,
): Modifier {
    return this
        .layout { measurable, _ ->
            val parent = parentBounds.value
            // `parentBounds` is populated by the cover Box's onGloballyPositioned callback, which
            // fires during LAYOUT — one phase after this element's own first measurement, so the
            // very first pass unavoidably sees the initial Rect.Zero. curLeft/curTop below are
            // computed as an OFFSET relative to parent.left/parent.top — with parent.left wrongly
            // 0 (not the Box's real root position), the offset would place the cover at src.left
            // pixels from whatever the Box's TRUE root position turns out to be, not at src.left
            // in ROOT space — a visible one-frame misplacement that then snaps to the correct spot
            // once parentBounds catches up next frame. Measure zero-size for these few frames
            // instead (graphicsLayer below also renders it invisible): the grid card's own cover is
            // still showing under it this early in the spring, so nothing is visibly missing.
            if (parent.width <= 0f || parent.height <= 0f) {
                measurable.measure(Constraints.fixed(0, 0))
                return@layout layout(0, 0) {}
            }
            val p = progress.value.coerceIn(0f, 1f)
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
            val wPx = curW.roundToInt().coerceAtLeast(0)
            val hPx = curH.roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(wPx, hPx))
            layout(wPx, hPx) {
                placeable.placeRelative(
                    (curLeft - parent.left).roundToInt(),
                    (curTop - parent.top).roundToInt()
                )
            }
        }
        .graphicsLayer {
            val parent = parentBounds.value
            if (parent.width <= 0f || parent.height <= 0f) {
                alpha = 0f
                return@graphicsLayer
            }
            val p = progress.value.coerceIn(0f, 1f)
            shape = RoundedCornerShape(lerpDp(sourceRadius, destRadius, p))
            clip = true
        }
}
