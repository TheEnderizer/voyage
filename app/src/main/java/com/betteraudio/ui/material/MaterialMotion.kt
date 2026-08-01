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
// Moved to ui/material/motion/ContainerMorph.kt (Modifier.morphingContainer) — a real Morph
// between two RoundedPolygons, drawn directly instead of scaled/clipped, replacing this
// function's clip-based approach (which was never isSimple for most of the gesture, forcing a
// Path-based clip on a full-screen layer the whole animation — see the removed KDoc's AN-5 note,
// preserved here for context). Its only call site was PlayerSheet.kt's Material You growing
// background.

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
