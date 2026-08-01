package com.betteraudio.ui.material.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.min

/**
 * Material You's morph engine — replaces [com.betteraudio.ui.material.expandingContainer]'s
 * clip-based container growth with a real [Morph] between two [RoundedPolygon]s (a rounded-rect
 * "pill" and a rounded-rect "full screen"), DRAWN directly rather than clipped.
 *
 * The old `expandingContainer` scaled/translated the actual layer and clipped it to an
 * asymmetric-radius `RoundRect`, which is never `isSimple` for most of the gesture — Compose
 * falls back to a Path-based clip on a full-screen layer for the whole animation
 * (`MaterialMotion.kt`'s own `expandingContainer` KDoc documents this as the measured cost). This
 * draws the interpolated shape's fill directly instead: no clip on anything, no scaled/translated
 * layer at all — just a plain `drawBehind` filling a path.
 */

private fun roundedRectPolygon(rect: Rect, cornerRadiusPx: Float): RoundedPolygon? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val radius = cornerRadiusPx.coerceIn(0f, min(rect.width, rect.height) / 2f)
    val vertices = floatArrayOf(
        rect.left, rect.top,
        rect.right, rect.top,
        rect.right, rect.bottom,
        rect.left, rect.bottom,
    )
    return RoundedPolygon(
        vertices = vertices,
        rounding = CornerRounding(radius),
        centerX = rect.left + rect.width / 2f,
        centerY = rect.top + rect.height / 2f,
    )
}

/** Rebuilt only when the source/destination geometry genuinely changes (a relayout of the mini
 *  bar, a rotation) — not per animation frame. [Morph] construction does real curve-matching
 *  work; [Morph.toPath] (called every frame, from the draw phase) is cheap pure interpolation, so
 *  this is what actually makes the per-frame cost small. Held in a draw-phase-only cache (not a
 *  composition-time `remember` keyed on the geometry) so reading `source`/`ownSize` here can't
 *  recompose the caller — see the AN-9 discipline documented on [com.betteraudio.ui.player.morphFrom]. */
private class ContainerMorphCache {
    val path: Path = Path()
    private var lastSrc: Rect? = null
    private var lastOwn: Size? = null
    private var lastSourceRadius = Float.NaN
    private var lastDestRadius = Float.NaN
    private var cached: Morph? = null

    fun morphFor(src: Rect, own: Size, sourceRadiusPx: Float, destRadiusPx: Float): Morph? {
        val hit = cached
        if (hit != null && src == lastSrc && own == lastOwn &&
            sourceRadiusPx == lastSourceRadius && destRadiusPx == lastDestRadius
        ) return hit

        val startPolygon = roundedRectPolygon(src, sourceRadiusPx)
        val endPolygon = roundedRectPolygon(Rect(Offset.Zero, own), destRadiusPx)
        val built = if (startPolygon != null && endPolygon != null) Morph(startPolygon, endPolygon) else null

        cached = built
        lastSrc = src; lastOwn = own; lastSourceRadius = sourceRadiusPx; lastDestRadius = destRadiusPx
        return built
    }
}

/**
 * Draws a container that grows from [source]'s bounds/radius (progress 0) to fill this element's
 * own natural size (progress 1), painted [color]. Replacement for
 * [com.betteraudio.ui.material.expandingContainer] — same inputs/semantics (used for the full
 * player's plain-color background growing out of the mini bar's pill), but drawn instead of
 * clipped; see the class doc above.
 *
 * [ownSizePx] is this element's TRUE (un-parked) size in px, tracked explicitly by the caller —
 * same reasoning as `expandingContainer`'s own doc: this element lives inside an ancestor that's
 * itself translated off-screen while collapsed, so a `boundsInRoot()` measurement here isn't
 * reliable for the un-parked size.
 */
@Composable
fun Modifier.morphingContainer(
    source: State<Rect>,
    ownSizePx: State<Size>,
    progress: State<Float>,
    sourceRadius: Dp,
    destRadius: Dp = 0.dp,
    color: State<Color>,
): Modifier {
    val cache = remember { ContainerMorphCache() }
    return this.drawBehind {
        val own = ownSizePx.value
        if (own.width <= 0f || own.height <= 0f) return@drawBehind
        val destRadiusPx = destRadius.toPx()
        val p = progress.value.coerceIn(0f, 1f)
        val src = source.value
        val fillColor = color.value
        if (p >= 1f || src == Rect.Zero) {
            drawRoundRect(color = fillColor, cornerRadius = CornerRadius(destRadiusPx))
            return@drawBehind
        }
        val sourceRadiusPx = sourceRadius.toPx()
        val morph = cache.morphFor(src, own, sourceRadiusPx, destRadiusPx)
        if (morph == null) {
            drawRoundRect(color = fillColor, cornerRadius = CornerRadius(destRadiusPx))
            return@drawBehind
        }
        morph.toPath(p, cache.path.asAndroidPath())
        drawPath(cache.path, color = fillColor)
    }
}
