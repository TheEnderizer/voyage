package com.betteraudio.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.betteraudio.ui.haptics.*

private val PRESET_SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFA552, 0xFFEF5350, 0xFFAB47BC,
    0xFF5C6BC0, 0xFF29B6F6, 0xFF66BB6A, 0xFFFFCA28, 0xFF8D6E63,
)

/**
 * A reusable color-selection row: optional "Accent" chip (recolor from the cover palette), recent
 * custom colors (if any — prepended so a color picked once on any element is immediately reusable
 * elsewhere), a strip of preset swatches, and a custom-color section with a visual HSV picker plus
 * hex + alpha entry — used by every style panel that exposes a color (icon glyph/container, text,
 * image border, shape fill, background fill).
 */
@Composable
fun ColorPickerRow(
    color: Long,
    onColorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    usesAccent: Boolean? = null,
    onAccentToggle: ((Boolean) -> Unit)? = null,
    recentColors: List<Long> = emptyList(),
    onCustomColorCommitted: ((Long) -> Unit)? = null,
) {
    var showCustom by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (usesAccent != null && onAccentToggle != null) {
                HapticFilterChip(
                    selected = usesAccent,
                    onClick = { onAccentToggle(!usesAccent) },
                    label = { Text("Accent") }
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(recentColors) { swatch ->
                    Swatch(
                        color = Color(swatch),
                        selected = usesAccent != true && color == swatch,
                        onClick = {
                            onAccentToggle?.invoke(false)
                            onColorChange(swatch)
                        }
                    )
                }
                items(PRESET_SWATCHES) { swatch ->
                    Swatch(
                        color = Color(swatch),
                        selected = usesAccent != true && color == swatch,
                        onClick = {
                            onAccentToggle?.invoke(false)
                            onColorChange(swatch)
                        }
                    )
                }
                item {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showCustom = !showCustom },
                        contentAlignment = Alignment.Center
                    ) { Text("+", color = MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
        if (showCustom) {
            CustomColorEditor(
                initialColor = color,
                onColorChange = { onAccentToggle?.invoke(false); onColorChange(it) },
                onCommit = { onCustomColorCommitted?.invoke(it) },
            )
        }
    }
}

/** Visual HSV picker (hue slider + saturation/value square) two-way bound with a hex field and an
 *  alpha slider. [onColorChange] fires continuously for live preview; [onCommit] fires only when a
 *  drag/slider gesture ends or the hex field parses, so every intermediate drag frame doesn't spam
 *  the recent-colors list. */
@Composable
private fun CustomColorEditor(
    initialColor: Long,
    onColorChange: (Long) -> Unit,
    onCommit: (Long) -> Unit,
) {
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var brightness by remember { mutableStateOf(0f) }
    var alpha by remember { mutableStateOf(255) }
    var hex by remember { mutableStateOf(String.format("%08X", initialColor)) }

    LaunchedEffect(Unit) {
        val argb = initialColor.toInt()
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            android.graphics.Color.red(argb), android.graphics.Color.green(argb), android.graphics.Color.blue(argb), hsv
        )
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
        alpha = android.graphics.Color.alpha(argb)
    }

    fun composeColor(h: Float = hue, s: Float = saturation, v: Float = brightness, a: Int = alpha): Long {
        val argb = android.graphics.Color.HSVToColor(a, floatArrayOf(h, s, v))
        return argb.toLong() and 0xFFFFFFFFL
    }

    fun applyLive(h: Float = hue, s: Float = saturation, v: Float = brightness, a: Int = alpha) {
        val c = composeColor(h, s, v, a)
        hex = String.format("%08X", c)
        onColorChange(c)
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SaturationValueBox(
            hue = hue, saturation = saturation, brightness = brightness,
            onChange = { s, v -> saturation = s; brightness = v; applyLive(s = s, v = v) },
            onCommit = { onCommit(composeColor()) },
        )
        HueSlider(
            hue = hue,
            onHueChange = { h -> hue = h; applyLive(h = h) },
            onCommit = { onCommit(composeColor()) },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Alpha", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
            HapticSlider(
                value = alpha / 255f,
                onValueChange = { alpha = (it * 255).roundToInt(); applyLive(a = alpha) },
                onValueChangeFinished = { onCommit(composeColor()) },
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = hex,
            onValueChange = { text ->
                hex = text
                val cleaned = text.trim().removePrefix("#")
                val parsed = cleaned.toLongOrNull(16)
                if (parsed != null && cleaned.length == 8) {
                    val argb = parsed.toInt()
                    val hsv = FloatArray(3)
                    android.graphics.Color.RGBToHSV(
                        android.graphics.Color.red(argb), android.graphics.Color.green(argb), android.graphics.Color.blue(argb), hsv
                    )
                    hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]
                    alpha = android.graphics.Color.alpha(argb)
                    onColorChange(parsed)
                    onCommit(parsed)
                }
            },
            label = { Text("AARRGGBB hex") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HueSlider(hue: Float, onHueChange: (Float) -> Unit, onCommit: () -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val hueGradient = remember {
        Brush.horizontalGradient(listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) })
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { boxSize = it }
            .clip(RoundedCornerShape(14.dp))
            .background(hueGradient)
            .pointerInput(Unit) {
                fun update(x: Float) = onHueChange((x / size.width.toFloat()).coerceIn(0f, 1f) * 360f)
                detectDragGestures(
                    onDragStart = { update(it.x) },
                    onDrag = { change, _ -> update(change.position.x) },
                    onDragEnd = { onCommit() },
                )
            }
    ) {
        if (boxSize.width > 0) {
            val thumbX = with(density) { ((hue / 360f).coerceIn(0f, 1f) * boxSize.width).toDp() }
            Box(
                Modifier
                    .offset(x = thumbX - 2.dp)
                    .width(4.dp)
                    .height(28.dp)
                    .background(Color.White, RoundedCornerShape(2.dp))
                    .border(1.dp, Color.Black.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (saturation: Float, brightness: Float) -> Unit,
    onCommit: () -> Unit,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .onSizeChanged { boxSize = it }
            .clip(RoundedCornerShape(10.dp))
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Color.hsv(hue, 1f, 1f)))
        Box(Modifier.fillMaxWidth().aspectRatio(1.6f).background(Brush.horizontalGradient(listOf(Color.White, Color.Transparent))))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                .pointerInput(Unit) {
                    fun update(p: Offset) {
                        val s = (p.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val v = 1f - (p.y / size.height.toFloat()).coerceIn(0f, 1f)
                        onChange(s, v)
                    }
                    detectDragGestures(
                        onDragStart = { update(it) },
                        onDrag = { change, _ -> update(change.position) },
                        onDragEnd = { onCommit() },
                    )
                }
        )
        if (boxSize.width > 0) {
            val thumbX = with(density) { (saturation.coerceIn(0f, 1f) * boxSize.width).toDp() }
            val thumbY = with(density) { ((1f - brightness.coerceIn(0f, 1f)) * boxSize.height).toDp() }
            Box(
                Modifier
                    .offset(x = thumbX - 8.dp, y = thumbY - 8.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check, contentDescription = null,
                tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.padding(6.dp)
            )
        }
    }
}

private fun Color.luminance(): Float = (0.299f * red + 0.587f * green + 0.114f * blue)
