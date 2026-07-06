package com.betteraudio.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.util.lerp

/**
 * Drives the shared-element morph between the mini player and the full player.
 *
 * [progress] is the sheet expansion (0 = mini bar, 1 = full player). The rects are the mini
 * bar's cover / title / controls bounds in root coordinates. All values are [State] so readers
 * can defer reads into graphicsLayer/draw lambdas and avoid per-frame recomposition.
 */
class PlayerExpandTransition(
    val progress: State<Float>,
    val miniCover: State<Rect>,
    val miniTitle: State<Rect>,
    val miniControls: State<Rect>,
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
 */
@Composable
fun Modifier.morphFrom(
    source: State<Rect>,
    progress: State<Float>,
    anchorTopLeft: Boolean = false,
    byWidth: Boolean = false,
    fadeIn: Boolean = false
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { own = it.boundsInRoot() }
        .graphicsLayer {
            val p = progress.value
            alpha = if (fadeIn) (p / 0.5f).coerceIn(0f, 1f) else 1f
            val src = source.value
            if (p >= 1f || src == Rect.Zero || own.width <= 0f || own.height <= 0f) {
                scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                return@graphicsLayer
            }
            val ratio = if (byWidth) src.width / own.width else src.height / own.height
            val s = lerp(ratio, 1f, p)
            scaleX = s
            scaleY = s
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
 * Reveal for elements that have no mini-player counterpart: they start small and transparent
 * and expand/fade in over the second half of the opening gesture.
 */
fun Modifier.expandReveal(progress: State<Float>): Modifier = graphicsLayer {
    val p = progress.value
    alpha = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val s = 0.8f + 0.2f * p.coerceIn(0f, 1f)
    scaleX = s
    scaleY = s
}
