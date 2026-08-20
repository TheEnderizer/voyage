package com.betteraudio.ui.widget.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ContainerShape
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.ui.haptics.*

@Composable
fun IconPanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val style = element.icon ?: com.betteraudio.widget.model.IconStyle()
    val recentColors by viewModel.recentColors.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Icon")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ContainerShape.entries.forEach { shape ->
                ContainerPreviewChip(
                    shape = shape,
                    selected = style.container == shape,
                    onClick = { viewModel.updateIcon { it.copy(container = shape) } }
                )
            }
        }

        if (style.container != ContainerShape.NONE) {
            Text("Container color")
            ColorPickerRow(
                color = style.containerColor,
                usesAccent = style.containerUsesAccent,
                onAccentToggle = { viewModel.updateIcon { s -> s.copy(containerUsesAccent = it) } },
                onColorChange = { viewModel.updateIcon { s -> s.copy(containerColor = it) } },
                recentColors = recentColors,
                onCustomColorCommitted = viewModel::addRecentColor,
            )
        }

        Text("Glyph color")
        ColorPickerRow(
            color = style.glyphColor,
            usesAccent = style.glyphUsesAccent,
            onAccentToggle = { viewModel.updateIcon { s -> s.copy(glyphUsesAccent = it) } },
            onColorChange = { viewModel.updateIcon { s -> s.copy(glyphColor = it) } },
            recentColors = recentColors,
            onCustomColorCommitted = viewModel::addRecentColor,
        )

        if (element.type == ElementType.SLEEP_TIMER) {
            Text("Sleep timer duration")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30, 45, 60).forEach { minutes ->
                    HapticFilterChip(
                        selected = element.sleepDurationMs == minutes * 60_000L,
                        onClick = { viewModel.updateSelected { it.copy(sleepDurationMs = minutes * 60_000L) } },
                        label = { Text("${minutes}m") }
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Show countdown label")
                HapticSwitch(
                    checked = element.showCountdown,
                    onCheckedChange = { viewModel.updateSelected { s -> s.copy(showCountdown = it) } }
                )
            }
        }
    }
}

@Composable
private fun ContainerPreviewChip(shape: ContainerShape, selected: Boolean, onClick: () -> Unit) {
    val clip = when (shape) {
        ContainerShape.NONE -> RoundedCornerShape(0.dp)
        ContainerShape.CIRCLE -> CircleShape
        ContainerShape.ROUNDED -> RoundedCornerShape(10.dp)
        ContainerShape.SQUIRCLE -> RoundedCornerShape(16.dp)
    }
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(40.dp)
            .clip(clip)
            .background(if (shape == ContainerShape.NONE) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer)
            .border(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.outline, clip)
            .clickable(onClick = onClick)
    )
}
