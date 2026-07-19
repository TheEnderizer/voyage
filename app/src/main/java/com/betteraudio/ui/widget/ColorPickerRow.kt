package com.betteraudio.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PRESET_SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFA552, 0xFFEF5350, 0xFFAB47BC,
    0xFF5C6BC0, 0xFF29B6F6, 0xFF66BB6A, 0xFFFFCA28, 0xFF8D6E63,
)

/**
 * A reusable color-selection row: optional "Accent" chip (recolor from the cover palette), a
 * strip of preset swatches, and a custom-color entry (hex + alpha) — used by every style panel
 * that exposes a color (icon glyph/container, text, image border, shape fill).
 */
@Composable
fun ColorPickerRow(
    color: Long,
    onColorChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
    usesAccent: Boolean? = null,
    onAccentToggle: ((Boolean) -> Unit)? = null,
) {
    var showCustom by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (usesAccent != null && onAccentToggle != null) {
                FilterChip(
                    selected = usesAccent,
                    onClick = { onAccentToggle(!usesAccent) },
                    label = { Text("Accent") }
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
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
            var hex by remember { mutableStateOf(String.format("%08X", color)) }
            OutlinedTextField(
                value = hex,
                onValueChange = { text ->
                    hex = text
                    val cleaned = text.trim().removePrefix("#")
                    val parsed = cleaned.toLongOrNull(16)
                    if (parsed != null && cleaned.length == 8) {
                        onAccentToggle?.invoke(false)
                        onColorChange(parsed)
                    }
                },
                label = { Text("AARRGGBB hex") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
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
