package com.betteraudio.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

/**
 * Softens the top edge of a scrolling list so content leaves the screen instead of being guillotined
 * by it.
 *
 * A `LazyColumn`/`LazyVerticalGrid` clips at its own bounds, which is fine for text and wrong for
 * artwork: half a book cover with a razor-straight line across it reads as a rendering fault, not as
 * a scroll position. This ramps the top [fade] of the content's alpha to nothing, punched out with
 * `BlendMode.DstIn`, so a cover dissolves into whatever is behind the list — the wallpaper, on the
 * library screen — over the last few dp rather than ending.
 *
 * That erasure needs an offscreen layer, which is what [CompositingStrategy.Offscreen] is for:
 * without it the mask paints as a dark gradient instead of erasing (same constraint as
 * `fadeTrailingEdge`).
 *
 * This used to blur as well as fade — a stack of bands of increasing radius, approximating a
 * progressive blur toward the edge, since Compose has no per-pixel blur radius. It was removed by
 * request. A blur is something happening *to* the covers; the fade is the covers getting out of the
 * way, and the wallpaper showing through is the point. Dropping it also drops four extra layer
 * replays per frame on a scrolling grid, and with them the API 31 split that had the blur present
 * above it and absent below.
 */
@Composable
fun Modifier.topEdgeFade(
    /** The alpha ramp's depth. Kept at or under the list's own top contentPadding where there is
     *  one, so a list sitting at rest at position zero is not already fading its first row. */
    fade: Dp = 32.dp
): Modifier {
    return this
        // The draw below happens inside one offscreen layer, so the DstIn ramp at the end erases
        // rather than paints.
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fadePx = fade.toPx()
            onDrawWithContent {
                drawContent()

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
