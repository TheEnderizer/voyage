package com.betteraudio.ui.components

import android.os.Build
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
 * and applies a position-varying Gaussian blur across the combined image:
 *
 *   ┌──────────────────────────────────┐ ← y = 0.0
 *   │  ORIGINAL  (top 30% stays sharp) │
 *   │  blur ramps from 30% downward    │
 *   ├──────────────────────────────────┤ ← y = 0.5  seam  (~70% of max blur)
 *   │  REFLECTION  (flipped)           │
 *   │  blur continues to 100% of max   │
 *   └──────────────────────────────────┘ ← y = 1.0
 *
 * Technique — stacked-layer blend:
 *   Four copies of the (original + reflection) column are rendered at
 *   increasing blur radii and each masked to a distinct vertical band via a
 *   gradient alpha (BlendMode.DstIn + CompositingStrategy.Offscreen).
 *   The bands crossfade so their alphas sum to ≈ 1.0 at every Y coordinate,
 *   producing the appearance of a continuously-varying blur without per-scanline
 *   blur (which Compose has no native primitive for).
 *
 *   Y fractions refer to the combined image height (original = [0, 0.5],
 *   reflection = [0.5, 1.0]).  "Top 30% of original" = y ∈ [0.0, 0.15].
 *
 * API requirement: Modifier.blur() requires API 31 (Android S) because it
 * delegates to RenderEffect.createBlurEffect().  On API 26–30, the composable
 * renders the reflection without any blur — still visually correct, just sharp.
 */
@Composable
fun ReflectedProgressiveBlurCover(
    coverPath: String?,
    modifier: Modifier = Modifier,
    maxBlurRadius: Dp = 30.dp
) {
    val file = remember(coverPath) { coverPath?.let { File(it) } }

    // Derived blur radii.
    // mid ≈ 65% of max — this is the dominant level visible at the seam (y=0.50),
    // which satisfies the "≈70% of max blur at the seam" requirement.
    val lightBlur = (maxBlurRadius.value * 0.30f).dp
    val midBlur   = (maxBlurRadius.value * 0.65f).dp

    Box(modifier.fillMaxWidth()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ── Layer 0: Sharp ────────────────────────────────────────────────
            // Fully visible y=0–12%, crossfades out 12–22%, invisible below 22%.
            BlurLayer(
                file       = file,
                blurRadius = 0.dp,
                stops      = arrayOf(
                    0.00f to Color.Black,
                    0.12f to Color.Black,
                    0.22f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 1: Light blur ───────────────────────────────────────────
            // Rises 12–22%, full 22–35%, crossfades out 35–47%.
            BlurLayer(
                file       = file,
                blurRadius = lightBlur,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.12f to Color.Transparent,
                    0.22f to Color.Black,
                    0.35f to Color.Black,
                    0.47f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 2: Mid blur ─────────────────────────────────────────────
            // Rises 35–47%, full 47–68% (seam at 50% is inside this zone →
            // ~65% of max blur = ≈70% target), crossfades out 68–82%.
            BlurLayer(
                file       = file,
                blurRadius = midBlur,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.35f to Color.Transparent,
                    0.47f to Color.Black,
                    0.68f to Color.Black,
                    0.82f to Color.Transparent,
                    1.00f to Color.Transparent,
                )
            )
            // ── Layer 3: Max blur ─────────────────────────────────────────────
            // Rises 68–82%, stays full 82–100%.
            BlurLayer(
                file       = file,
                blurRadius = maxBlurRadius,
                stops      = arrayOf(
                    0.00f to Color.Transparent,
                    0.68f to Color.Transparent,
                    0.82f to Color.Black,
                    1.00f to Color.Black,
                )
            )
        } else {
            // API < 31 fallback: original + reflection, no blur.
            CoverColumn(file = file, blurRadius = 0.dp)
        }
    }
}

@Composable
private fun BlurLayer(
    file: File?,
    blurRadius: Dp,
    stops: Array<Pair<Float, Color>>,
) {
    // Brush is cheap to construct and stops are constants — skip remember.
    val maskBrush = Brush.verticalGradient(colorStops = stops)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Offscreen buffer is required so BlendMode.DstIn can use the
            // layer's own alpha channel instead of the global compositing alpha.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()                                        // draws the (blurred) column
                drawRect(brush = maskBrush, blendMode = BlendMode.DstIn) // cuts the gradient mask
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
        // Original image at natural aspect ratio (FillWidth = full width, intrinsic height).
        AsyncImage(
            model              = file,
            contentDescription = null,
            contentScale       = ContentScale.FillWidth,
            modifier           = Modifier.fillMaxWidth()
        )
        // Vertically-flipped reflection directly below — same scale, scaleY = -1.
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
