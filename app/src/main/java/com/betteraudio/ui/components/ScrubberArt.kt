package com.betteraudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How the player draws its chapter scrubber. Five takes on the same information — the choice is
 * how loudly the control states itself, not what it says. Offered in **both** app looks: the four
 * painted designs were born in Immersive but nothing in them is Immersive-specific (they take an
 * accent and a track colour and fill the width they are given), and [CLASSIC] is the Material
 * slider Material You has always used, kept as an option rather than replaced.
 *
 * The gesture, the swell spring, the haptics and the 28dp-plus hit target live in
 * [com.betteraudio.ui.components.VoyageScrubber] and are identical for all of them; only
 * [drawScrubber] differs. That split is what lets Settings preview each one with the real drawing
 * code instead of a mock-up that can drift away from it.
 *
 * [CLASSIC] is the one entry [drawScrubber] cannot paint: an M3 `Slider` is a composable with its
 * own thumb, track and interaction visuals, and hand-redrawing it on a Canvas would be a copy that
 * silently drifts from Material's every time the library updates. `VoyageScrubber` branches to the
 * real control for it instead, which is why that branch lives at the composable level and not here.
 */
enum class ScrubberStyle(val label: String, val blurb: String, val height: Dp) {
    CLASSIC(
        "Material slider",
        "The standard Material bar — a plain track with a round thumb. What Material You has always used.",
        28.dp
    ),
    EMBER(
        "Ember hairline",
        "A thin rail that gains colour as it fills, with a lit bead riding the playhead.",
        28.dp
    ),
    RIBS(
        "Tape ribs",
        "A field of ribs instead of a line — lit behind the playhead, dim ahead of it.",
        34.dp
    ),
    AURORA(
        "Aurora",
        "Almost no chrome: a hairline, a wash of colour behind you, and a bloom of light where you are.",
        32.dp
    ),
    HORIZON(
        "Horizon",
        "The rail bows into a shallow curve and the playhead rides it, throwing light up into the cover.",
        46.dp
    );

    companion object {
        fun from(raw: String): ScrubberStyle = entries.firstOrNull { it.name == raw } ?: EMBER
    }
}

/** Provided by MainActivity from whichever of the two per-theme preferences matches the active
 *  look (`scrubber_style` for Immersive, `scrubber_style_material` for Material You); read by both
 *  players through [VoyageScrubber]. One local, so no player has to know which pref fed it. */
val LocalScrubberStyle = staticCompositionLocalOf { ScrubberStyle.EMBER }

/**
 * Paints [style] across this whole draw scope.
 *
 * [fraction] is 0..1 through the chapter. [swell] is 0 at rest and 1 while a thumb is down — every
 * style uses it to thicken and brighten at once, so the swell reads as one object reacting rather
 * than several properties animating. [trackColor] is the unplayed side; [accent] is the cover's
 * colour. The playhead's own near-white is derived here rather than passed, so all four agree.
 */
fun DrawScope.drawScrubber(
    style: ScrubberStyle,
    fraction: Float,
    swell: Float,
    accent: Color,
    trackColor: Color
) {
    val f = fraction.coerceIn(0f, 1f)
    // Near-white pulled toward the book's colour, matching ImmersiveStyle.scrimText() — the
    // playhead is the brightest thing on the sheet and it should still be the book's brightest.
    val bead = lerp(Color.White, accent, 0.22f)
    when (style) {
        // Never reached: VoyageScrubber renders the real M3 Slider for CLASSIC and only calls
        // this for the painted designs. Drawn as Ember rather than as nothing so a future caller
        // that forgets the branch shows a working bar instead of an empty strip.
        ScrubberStyle.CLASSIC -> drawEmber(f, swell, accent, trackColor, bead)
        ScrubberStyle.EMBER -> drawEmber(f, swell, accent, trackColor, bead)
        ScrubberStyle.RIBS -> drawRibs(f, swell, accent, trackColor, bead)
        ScrubberStyle.AURORA -> drawAurora(f, swell, accent, trackColor, bead)
        ScrubberStyle.HORIZON -> drawHorizon(f, swell, accent, trackColor, bead)
    }
}

private fun DrawScope.drawEmber(
    f: Float, swell: Float, accent: Color, trackColor: Color, bead: Color
) {
    val railH = (6f + 6f * swell).dp.toPx()
    val r = railH / 2f
    val cy = size.height / 2f
    val top = cy - r

    drawRoundRect(
        color = trackColor,
        topLeft = Offset(0f, top),
        size = Size(size.width, railH),
        cornerRadius = CornerRadius(r, r)
    )
    // The gradient is anchored to the FULL width and then clipped to the played length, so the
    // colour at any point of the rail is a property of that point rather than of how far along
    // the fill currently happens to end.
    val filled = (size.width * f).coerceAtLeast(railH)
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(accent.copy(alpha = 0.45f), accent),
            startX = 0f,
            endX = size.width
        ),
        topLeft = Offset(0f, top),
        size = Size(filled, railH),
        cornerRadius = CornerRadius(r, r)
    )

    val beadW = (3.5f + 1.5f * swell).dp.toPx()
    val beadH = railH + (9f + 7f * swell).dp.toPx()
    val hx = (size.width * f).coerceIn(beadW / 2f, size.width - beadW / 2f)
    val glowR = beadH * (0.9f + 0.5f * swell)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.10f + 0.30f * swell), Color.Transparent),
            center = Offset(hx, cy),
            radius = glowR
        ),
        radius = glowR,
        center = Offset(hx, cy)
    )
    drawRoundRect(
        color = bead,
        topLeft = Offset(hx - beadW / 2f, cy - beadH / 2f),
        size = Size(beadW, beadH),
        cornerRadius = CornerRadius(beadW / 2f, beadW / 2f)
    )
}

private fun DrawScope.drawRibs(
    f: Float, swell: Float, accent: Color, trackColor: Color, bead: Color
) {
    val cy = size.height / 2f
    val ribW = (2f + 0.7f * swell).dp.toPx()
    val gap = 2.4f.dp.toPx()
    val count = ((size.width + gap) / (ribW + gap)).toInt().coerceIn(16, 120)
    val step = size.width / count
    val minH = (5f + 2f * swell).dp.toPx()
    val amp = (12f + 6f * swell).dp.toPx()
    val head = (f * (count - 1)).roundToInt().coerceIn(0, count - 1)

    // A fixed, deterministic profile from two incommensurate sines: the same book always looks
    // the same, and it never needs a random seed stored anywhere. Decorative — it is texture, not
    // a waveform, and nothing downstream should read meaning into a rib's height.
    val glowR = (14f + 10f * swell).dp.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.22f + 0.28f * swell), Color.Transparent),
            center = Offset(head * step + step / 2f, cy),
            radius = glowR
        ),
        radius = glowR,
        center = Offset(head * step + step / 2f, cy)
    )
    for (i in 0 until count) {
        val t = i.toFloat() / count
        val profile = abs(sin(t * 11.3f) * 0.6f + sin(t * 27.7f) * 0.4f)
        val isHead = i == head
        val h = minH + amp * (0.45f + 0.55f * profile) +
            if (isHead) (5f + 4f * swell).dp.toPx() else 0f
        val color = when {
            isHead -> bead
            i < head -> accent.copy(alpha = 0.92f)
            else -> trackColor
        }
        val w = if (isHead) ribW + 1f.dp.toPx() else ribW
        drawRoundRect(
            color = color,
            topLeft = Offset(i * step + (step - w) / 2f, cy - h / 2f),
            size = Size(w, h),
            cornerRadius = CornerRadius(w / 2f, w / 2f)
        )
    }
}

private fun DrawScope.drawAurora(
    f: Float, swell: Float, accent: Color, trackColor: Color, bead: Color
) {
    val cy = size.height / 2f
    val hair = (1f + 0.6f * swell).dp.toPx()
    drawRoundRect(
        color = trackColor.copy(alpha = trackColor.alpha * 0.65f),
        topLeft = Offset(0f, cy - hair / 2f),
        size = Size(size.width, hair),
        cornerRadius = CornerRadius(hair / 2f, hair / 2f)
    )

    val hx = size.width * f
    // The bloom is the whole point of this one, so it is drawn as a wide ellipse — a circular
    // radial gradient would read as a dot with a halo instead of light smeared along the track.
    val bloomR = (30f + 16f * swell).dp.toPx()
    scale(scaleX = 1.9f, scaleY = 0.62f, pivot = Offset(hx, cy)) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.55f + 0.25f * swell),
                    accent.copy(alpha = 0.16f),
                    Color.Transparent
                ),
                center = Offset(hx, cy),
                radius = bloomR
            ),
            radius = bloomR,
            center = Offset(hx, cy)
        )
    }

    if (hx > 0.5f) {
        val washH = (2.5f + 1.5f * swell).dp.toPx()
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, accent.copy(alpha = 0.38f), accent),
                startX = 0f,
                endX = size.width
            ),
            topLeft = Offset(0f, cy - washH / 2f),
            size = Size(hx, washH),
            cornerRadius = CornerRadius(washH / 2f, washH / 2f)
        )
    }
    drawCircle(
        color = bead,
        radius = (2.5f + 1.6f * swell).dp.toPx(),
        center = Offset(hx.coerceIn(2f, size.width - 2f), cy)
    )
}

private fun DrawScope.drawHorizon(
    f: Float, swell: Float, accent: Color, trackColor: Color, bead: Color
) {
    // For a quadratic with both ends at y0 and control at yc, the curve's lowest point is
    // (y0 + yc) / 2 — so the control sits twice the intended sag below the ends.
    val y0 = 13f.dp.toPx()
    val sag = 13f.dp.toPx()
    val path = Path().apply {
        moveTo(0f, y0)
        quadraticTo(size.width / 2f, y0 + 2f * sag, size.width, y0)
    }
    val strokeW = (2.5f + 2f * swell).dp.toPx()
    drawPath(path, color = trackColor, style = Stroke(strokeW, cap = StrokeCap.Round))

    val measure = PathMeasure().apply { setPath(path, false) }
    val len = measure.length
    if (len <= 0f) return
    val at = len * f
    if (at > 0.5f) {
        val played = Path()
        measure.getSegment(0f, at, played, true)
        drawPath(
            played,
            color = accent,
            style = Stroke(strokeW + 0.5f.dp.toPx(), cap = StrokeCap.Round)
        )
    }
    val pos = measure.getPosition(at)
    val glowR = (24f + 14f * swell).dp.toPx()
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = 0.40f + 0.25f * swell), Color.Transparent),
            center = pos,
            radius = glowR
        ),
        radius = glowR,
        center = pos
    )
    drawCircle(color = bead, radius = (4.5f + 2f * swell).dp.toPx(), center = pos)
}

/**
 * A still of [style] at a representative position, for the picker in Settings. Drawn by
 * [drawScrubber] itself, so what the option shows is literally what the player will draw — the
 * preview cannot go stale when a style is retuned.
 */
@Composable
fun ScrubberPreview(
    style: ScrubberStyle,
    accent: Color,
    modifier: Modifier = Modifier,
    fraction: Float = 0.42f
) {
    val track = Color.White.copy(alpha = 0.24f)
    if (style == ScrubberStyle.CLASSIC) {
        // The real control, not a drawing of one — same reason the painted styles preview through
        // drawScrubber. Disabled so the card's row keeps the tap, but coloured as if enabled.
        Slider(
            value = fraction,
            onValueChange = {},
            enabled = false,
            colors = SliderDefaults.colors(
                disabledThumbColor = accent,
                disabledActiveTrackColor = accent,
                disabledInactiveTrackColor = track
            ),
            modifier = modifier.fillMaxWidth().height(style.height)
        )
        return
    }
    Canvas(modifier.fillMaxWidth().height(style.height)) {
        drawScrubber(style, fraction, swell = 0f, accent = accent, trackColor = track)
    }
}
