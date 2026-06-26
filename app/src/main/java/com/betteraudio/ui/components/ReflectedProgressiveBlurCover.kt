package com.betteraudio.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * Renders a cover image with a vertically-flipped reflection directly below,
 * and applies a position-varying blur that grades smoothly from sharp at the
 * top to fully blurred at the bottom, which then dissolves into black.
 *
 *   ┌──────────────────────────────────┐ ← y = 0.0
 *   │  ORIGINAL  (top ~56% stays sharp)│
 *   │  very gradual blur onset         │
 *   ├──────────────────────────────────┤ ← y = 0.5  seam
 *   │  REFLECTION  (flipped)           │
 *   │  blur continues to build         │
 *   │  fades to black at very bottom   │
 *   └──────────────────────────────────┘ ← y = 1.0  (black)
 *
 * Technique — stacked-layer blend:
 *   Five copies of the (original + reflection) column are rendered at
 *   increasing blur radii, each masked to a vertical band via a gradient
 *   alpha (BlendMode.DstIn + CompositingStrategy.Offscreen).
 *   Adjacent bands crossfade over wide overlap zones so their alphas sum to
 *   ≈ 1.0 at every Y — no brightness doubling, no hard seams.
 *
 *   The container Box has a black background. The topmost layer (max blur)
 *   fades from fully opaque at y ≈ 0.94 to transparent at y = 1.0, letting
 *   the black background show through — this is the fade-to-black.
 *
 *   Y fractions refer to the combined image height (original = [0, 0.5],
 *   reflection = [0.5, 1.0]).
 *
 * API requirement: Modifier.blur() requires API 31 (Android 12 / S) because
 * it delegates to RenderEffect. On API 26–30, original + reflection render
 * without blur (still correct visually, just sharp).
 */
@Composable
fun ReflectedProgressiveBlurCover(
    coverPath: String?,
    modifier: Modifier = Modifier,
    maxBlurRadius: Dp = 30.dp
) {
    val file = remember(coverPath) { coverPath?.let { File(it) } }

    val veryLightBlur = (maxBlurRadius.value * 0.17f).dp  // ~5 dp
    val lightBlur     = (maxBlurRadius.value * 0.43f).dp  // ~13 dp
    val midBlur       = (maxBlurRadius.value * 0.73f).dp  // ~22 dp

    // Black background so the fade-to-transparent in the max-blur layer at
    // y = 1.0 resolves to black, not whatever is behind this composable.
    Box(modifier.fillMaxWidth().background(Color.Black)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ── Layer 0: Sharp ─────────────────────────────────────────────────
            // Full until y=28%, very wide crossfade out to y=44%.
            // This gives ~16% of combined height (≈32% of original image) as a
            // gradual lead-in zone, making the blur onset imperceptible at first.
            BlurLayer(
                file       = file,
                blurRadius = 0.dp,
                stops      = arrayOf(
                    0.00f to Color.Black,
                    0.28f to Color.Black,
                    0.44f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 1: Very light blur ────────────────────────────────────────
            // Rises with L0's crossfade (28–44%), briefly full (44–47%),
            // crossfades into L2 (47–55%).
            BlurLayer(
                file       = file,
                blurRadius = veryLightBlur,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.28f to Color.Transparent,
                    0.44f to Color.Black,
                    0.47f to Color.Black,
                    0.55f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 2: Light blur ─────────────────────────────────────────────
            // Rises 47–55% (seam at 50% is inside this zone), full 55–65%,
            // crossfades into L3 (65–76%).
            BlurLayer(
                file       = file,
                blurRadius = lightBlur,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.47f to Color.Transparent,
                    0.55f to Color.Black,
                    0.65f to Color.Black,
                    0.76f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 3: Mid blur ───────────────────────────────────────────────
            // Rises 65–76%, full 76–87%, crossfades into L4 (87–94%).
            BlurLayer(
                file       = file,
                blurRadius = midBlur,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.65f to Color.Transparent,
                    0.76f to Color.Black,
                    0.87f to Color.Black,
                    0.94f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 4: Max blur ───────────────────────────────────────────────
            // Rises 87–94%, peaks 94–97%, then fades to transparent at 100%.
            // The black container background shows through the fade → fade-to-black.
            BlurLayer(
                file       = file,
                blurRadius = maxBlurRadius,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.87f to Color.Transparent,
                    0.94f to Color.Black,
                    0.97f to Color.Black,
                    1.00f to Color.Transparent,
                )
            )
        } else {
            // API < 31: original + sharp reflection, fades to black via gradient.
            CoverColumn(file = file, blurRadius = 0.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.75f to Color.Transparent,
                            1.00f to Color.Black
                        )
                    )
            )
        }
    }
}

@Composable
private fun BlurLayer(
    file: File?,
    blurRadius: Dp,
    stops: Array<Pair<Float, Color>>,
) {
    val maskBrush = Brush.verticalGradient(colorStops = stops)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(brush = maskBrush, blendMode = BlendMode.DstIn)
            }
    ) {
        CoverColumn(file = file, blurRadius = blurRadius)
    }
}

@Composable
private fun CoverColumn(file: File?, blurRadius: Dp) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)
    ) {
        AsyncImage(
            model              = file,
            contentDescription = null,
            contentScale       = ContentScale.FillWidth,
            modifier           = Modifier.fillMaxWidth()
        )
        AsyncImage(
            model              = file,
            contentDescription = null,
            contentScale       = ContentScale.FillWidth,
            modifier           = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleY = -1f }
        )
    }
}
