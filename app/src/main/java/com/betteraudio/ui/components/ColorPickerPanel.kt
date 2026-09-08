package com.betteraudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.LocalHaptics

/**
 * A real colour picker: a saturation/value field you drag a cursor around, a hue rail under it, and
 * a swatch showing what you have. Shared by Material You's "Custom color" and Immersive's cover
 * accent, so the two ways of choosing an accent are the same act.
 *
 * Both used to be swatches-plus-a-hex-field. A hex field is an *encoding* of a colour, not a way of
 * choosing one — you cannot answer "a bit less orange" with it without doing arithmetic — so the
 * grid of presets was doing all the real work and the space between two presets was unreachable.
 *
 * **HSV is held here, not derived from [color] on every frame.** Value 0 is black for every hue and
 * saturation 0 is white for every hue, so a colour near either edge cannot say what hue it came
 * from: round-tripping through RGB would snap the hue rail to red the moment a drag reached the
 * bottom of the field, and the field would repaint under the user's finger. The state is seeded
 * from [color] once (and re-seeded only when a *different* colour arrives from outside, e.g. a
 * preset tap), which keeps a drag continuous through both degenerate edges.
 */
@Composable
fun ColorPickerPanel(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHaptics.current
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var value by remember { mutableFloatStateOf(1f) }
    // Guards the re-seed: without it every emission of our own onColorChange would come back in
    // and overwrite the hue we are deliberately holding through a black/white edge. Held as a
    // nullable Int, not a Float — an ARGB value does not survive a round trip through Float.
    var lastEmitted by remember { mutableStateOf<Int?>(null) }

    val incoming = color.toArgb()
    if (incoming != lastEmitted) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(incoming, hsv)
        // A fully desaturated or black colour carries no usable hue; keep the one on the rail so
        // the field the user is looking at does not jump.
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        lastEmitted = incoming
    }

    fun emit() {
        val argb = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
        lastEmitted = argb
        onColorChange(Color(argb))
    }

    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val current = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ── Saturation (x) × value (y) ──────────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.5f)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
        ) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        // Tap and drag write the same two numbers; a tap is just a drag of length
                        // zero as far as the field is concerned.
                        detectTapGestures { off ->
                            sat = (off.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (off.y / size.height).coerceIn(0f, 1f)
                            haptics.play(Feel.Select)
                            emit()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { haptics.play(Feel.Grab) },
                            onDragEnd = { haptics.play(Feel.Release) },
                            onDragCancel = { haptics.play(Feel.Release) }
                        ) { change, _ ->
                            change.consume()
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                            emit()
                        }
                    }
            ) {
                // White → the pure hue across, then transparent → black down. Two gradients rather
                // than a per-pixel shader: the same picture, and no RuntimeShader/API gate.
                drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

                // Kept a full ring's width inside the field. The field clips to its own rounded
                // rect, so a cursor drawn at the true position is sliced in half along every edge
                // — and the edges (pure white, pure black, full saturation) are exactly where a
                // deliberate pick lands. The clamp moves the drawn ring, never the chosen value.
                val r = 11.dp.toPx()
                val inset = r + 2.dp.toPx()
                val cx = (sat * size.width).coerceIn(inset, size.width - inset)
                val cy = ((1f - value) * size.height).coerceIn(inset, size.height - inset)
                // A ring, not a dot: the colour under the cursor is the thing being chosen, so the
                // cursor must not cover it. Two strokes so it stays visible over black and white.
                drawCircle(Color.Black.copy(alpha = 0.55f), radius = r,
                    center = Offset(cx, cy), style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, radius = r,
                    center = Offset(cx, cy), style = Stroke(1.5.dp.toPx()))
            }
        }

        // ── Hue ─────────────────────────────────────────────────────────────
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        hue = (off.x / size.width).coerceIn(0f, 1f) * 360f
                        haptics.play(Feel.Select)
                        emit()
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { haptics.play(Feel.Grab) },
                        onDragEnd = { haptics.play(Feel.Release) },
                        onDragCancel = { haptics.play(Feel.Release) }
                    ) { change, _ ->
                        change.consume()
                        hue = (change.position.x / size.width).coerceIn(0f, 1f) * 360f
                        emit()
                    }
                }
        ) {
            val stops = List(7) { i ->
                Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)))
            }
            val h = 14.dp.toPx()
            val top = (size.height - h) / 2f
            drawRoundRect(
                brush = Brush.horizontalGradient(stops),
                topLeft = Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f)
            )
            val x = (hue / 360f) * size.width
            val cy = size.height / 2f
            drawCircle(Color.White, radius = 10.dp.toPx(), center = Offset(x, cy))
            drawCircle(pure, radius = 7.dp.toPx(), center = Offset(x, cy))
        }

        // ── What you have ───────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(current)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
            Text(
                "#%06X".format(current.toArgb() and 0xFFFFFF),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
