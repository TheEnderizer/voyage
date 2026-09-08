package com.betteraudio.ui.components

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/**
 * Softens the top edge of a scrolling list so content leaves the screen instead of being guillotined
 * by it.
 *
 * A `LazyColumn`/`LazyVerticalGrid` clips at its own bounds, which is fine for text and wrong for
 * artwork: half a book cover with a razor-straight line across it reads as a rendering fault, not as
 * a scroll position. This draws the top [height] of the content twice — once blurred, ramping in as
 * it approaches the edge, and once with its alpha ramping to nothing — so a cover dissolves into the
 * background over the last few dp rather than ending.
 *
 * Two passes rather than one, because they are two different ramps and the shape of each matters:
 *
 *  - **Blur** starts at zero a little way down and reaches full strength at the edge. Compose has no
 *    per-pixel blur radius, so this stacks a handful of bands with increasing radius (an approximate
 *    progressive blur) rather than a single flat one — a flat blur has its own visible boundary and
 *    just replaces one hard line with another.
 *  - **Fade** is a straight alpha ramp over the top [fade], punched out with `BlendMode.DstIn`. That
 *    needs an offscreen layer, which is what [CompositingStrategy.Offscreen] is for — without it the
 *    mask paints as a dark gradient instead of erasing (same constraint as `fadeTrailingEdge`).
 *
 * Below API 31 there is no [BlurEffect] at all, so the blur pass is skipped and the fade carries the
 * effect on its own. That is a real degrade rather than a fallback hack: the fade is the half that
 * removes the hard line, and the blur is the half that makes it look intentional.
 */
@Composable
fun Modifier.topEdgeFade(
    /** How far down the blur reaches. Zero at this depth, full strength at the top edge. */
    height: Dp = 88.dp,
    /** The alpha ramp's depth — shorter than [height], so content softens before it dissolves.
     *  Kept at or under the list's own top contentPadding where there is one, so a list sitting at
     *  rest at position zero is not already fading its first row. */
    fade: Dp = 32.dp
): Modifier {
    val layer = rememberGraphicsLayer()
    val blurLayer = rememberGraphicsLayer()
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    return this
        // Every draw below happens inside one offscreen layer, so the DstIn ramp at the end erases
        // rather than paints, and the erasure covers the blurred bands too.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val heightPx = height.toPx()
            val fadePx = fade.toPx()
            // Bands of increasing radius, stacked bottom-up. Four is enough that the steps are not
            // individually visible at these radii and cheap enough to run on a scrolling grid.
            val bands = 4
            onDrawWithContent {
                layer.record { this@onDrawWithContent.drawContent() }
                drawLayer(layer)

                if (canBlur && heightPx > 1f) {
                    for (i in 0 until bands) {
                        // Band i covers the top (1 - i/bands) of the strip, so later bands are
                        // both narrower and blurrier — the radius climbs toward the edge.
                        val bandBottom = heightPx * (1f - i.toFloat() / bands)
                        val radius = (2f + i * 5f).dp.toPx()
                        // Re-recorded FROM the already-captured layer, never from drawContent():
                        // replaying one display list per band costs a fraction of running the
                        // grid's whole draw pass four more times on every scrolled frame.
                        blurLayer.record { drawLayer(layer) }
                        blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                        // Each band is drawn at a modest alpha; overlapping them is what makes the
                        // radius ramp read as continuous instead of as four steps.
                        blurLayer.alpha = 0.55f
                        clipRect(0f, 0f, size.width, bandBottom) {
                            drawLayer(blurLayer)
                        }
                    }
                }

                if (fadePx > 1f) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black,
                            startY = 0f,
                            endY = fadePx
                        ),
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, fadePx),
                        blendMode = BlendMode.DstIn
                    )
                }
            }
        }
}
