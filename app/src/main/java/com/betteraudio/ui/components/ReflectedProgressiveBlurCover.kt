package com.betteraudio.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
 *   │  ORIGINAL  (top ~60% stays sharp)│
 *   │  very gradual blur onset         │
 *   ├──────────────────────────────────┤ ← y = 0.5  seam (light blur)
 *   │  REFLECTION  (flipped)           │
 *   │  blur continues to build         │
 *   │  fades to black at very bottom   │
 *   └──────────────────────────────────┘ ← y = 1.0  (black)
 *
 * Technique — stacked-layer blend (partition-of-unity tents):
 *   Compose has no native variable-radius blur (Modifier.blur applies one
 *   uniform radius), so a continuous gradient is approximated by stacking N
 *   copies of the (original + reflection) column at increasing blur radii,
 *   each masked by a triangular ("tent") gradient alpha via BlendMode.DstIn.
 *   The tents peak at successive node positions and their alphas sum to 1.0
 *   at every Y — a linear-interpolation basis. With many layers at SMALL
 *   radius steps, neighbouring copies are nearly identical, so the crossfades
 *   carry no visible double-image and the result reads as a continuous blur
 *   rather than discrete bands. (A truly per-pixel variable blur would need an
 *   AGSL RuntimeShader, API 33+; this layered form is the cross-version
 *   idiomatic approximation.)
 *
 *   The container Box has a black background. The bottom-most layer fades from
 *   opaque to transparent at y → 1.0, letting the black show through — the
 *   reflection dissolves into black with no separate overlay.
 *
 * API requirement: Modifier.blur() requires API 31 (Android 12 / S). On
 * API 26–30, original + reflection render sharp with a plain fade-to-black.
 */
@Composable
fun ReflectedProgressiveBlurCover(
    coverPath: String?,
    modifier: Modifier = Modifier,
    bakedPath: String? = null,
    maxBlurRadius: Dp = 22.dp
) {
    // Fast path: a pre-baked composite already contains the reflection + progressive blur
    // + fade-to-black, so just draw it (one image, no live blur passes). Falls back to the
    // live layered render below while the bake is missing/in-progress.
    val baked = remember(bakedPath) { bakedPath?.let { File(it) }?.takeIf { it.exists() } }
    if (baked != null) {
        Box(modifier.fillMaxWidth().background(Color.Black)) {
            AsyncImage(
                model = baked,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                // Anchor to the top: when the composite is taller than the box it's
                // clamped to, the top of the artwork stays put and only the bottom is
                // clipped (default Center alignment would crop the top off too).
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    val file = remember(coverPath) { coverPath?.let { File(it) } }
    val m = maxBlurRadius.value

    Box(modifier.fillMaxWidth().background(Color.Black)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Tent nodes (combined-height fractions) and their blur radii.
            // Small radius steps (max ~5 dp between neighbours) keep crossfades
            // free of visible double-imaging. Sharp holds to 0.30; max at 0.92.

            // ── Layer 0: Sharp ── holds 0→0.30, ramps out by 0.45.
            BlurLayer(file, 0.dp, arrayOf(
                0.00f to Color.Black,
                0.30f to Color.Black,
                0.45f to Color.Transparent,
                1.00f to Color.Transparent,
            ))
            // ── Layer 1 ── peak 0.45
            BlurLayer(file, (m * 0.18f).dp, arrayOf(
                0.00f to Color.Transparent,
                0.30f to Color.Transparent,
                0.45f to Color.Black,
                0.56f to Color.Transparent,
                1.00f to Color.Transparent,
            ))
            // ── Layer 2 ── peak 0.56  (seam at 0.50 sits in 0.45–0.56 → light blur)
            BlurLayer(file, (m * 0.32f).dp, arrayOf(
                0.00f to Color.Transparent,
                0.45f to Color.Transparent,
                0.56f to Color.Black,
                0.66f to Color.Transparent,
                1.00f to Color.Transparent,
            ))
            // ── Layer 3 ── peak 0.66
            BlurLayer(file, (m * 0.45f).dp, arrayOf(
                0.00f to Color.Transparent,
                0.56f to Color.Transparent,
                0.66f to Color.Black,
                0.75f to Color.Transparent,
                1.00f to Color.Transparent,
            ))
            // ── Layer 4 ── peak 0.75
            BlurLayer(file, (m * 0.59f).dp, arrayOf(
                0.00f to Color.Transparent,
                0.66f to Color.Transparent,
                0.75f to Color.Black,
                0.83f to Color.Transparent,
                1.00f to Color.Transparent,
            ))
            // ── Layer 5 ── peak 0.83
            BlurLayer(file, (m * 0.77f).dp, arrayOf(
                0.00f to Color.Transparent,
                0.75f to Color.Transparent,
                0.83f to Color.Black,
                0.92f to Color.Transparent,
                1.00f to Color.Transparent,
            ))
            // ── Layer 6: Max ── peak 0.92, hold to 0.96, fade to black at 1.0.
            BlurLayer(file, maxBlurRadius, arrayOf(
                0.00f to Color.Transparent,
                0.83f to Color.Transparent,
                0.92f to Color.Black,
                0.96f to Color.Black,
                1.00f to Color.Transparent,
            ))
        } else {
            // API < 31: sharp original + reflection, plain fade-to-black.
            CoverColumn(file = file, blurRadius = 0.dp)
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0.80f to Color.Transparent,
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
