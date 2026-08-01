package com.betteraudio.ui.widget.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.ProgressShape
import com.betteraudio.widget.model.ProgressSource
import com.betteraudio.widget.model.ShapeKind
import com.betteraudio.widget.model.ShapeStyle

@Composable
fun ShapePanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val style = element.shape ?: ShapeStyle()
    val recentColors by viewModel.recentColors.collectAsState()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (element.type == ElementType.PROGRESS_BAR) "Progress bar" else "Shape")

        if (element.type == ElementType.RECT) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShapeKind.entries.forEach { kind ->
                    FilterChip(
                        selected = style.kind == kind,
                        onClick = { viewModel.updateShape { it.copy(kind = kind) } },
                        label = { Text(kind.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                    )
                }
            }
            Text("Fill color")
            ColorPickerRow(
                color = style.fillColor,
                onColorChange = { viewModel.updateShape { s -> s.copy(fillColor = it) } },
                recentColors = recentColors,
                onCustomColorCommitted = viewModel::addRecentColor,
            )
            if (style.kind == ShapeKind.RECT) {
                LabeledSlider("Corner radius", style.cornerRadius, 0f, 200f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
                    viewModel.updateShape(immediate = false) { it.copy(cornerRadius = v) }
                }
            }
        } else {
            Text("Tracks")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressSource.entries.forEach { source ->
                    FilterChip(
                        selected = style.progressSource == source,
                        onClick = { viewModel.updateShape { it.copy(progressSource = source) } },
                        label = { Text(labelForProgressSource(source)) }
                    )
                }
            }
            Text("Shape")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressShape.entries.forEach { shape ->
                    FilterChip(
                        selected = style.progressShape == shape,
                        onClick = { viewModel.updateShape { it.copy(progressShape = shape) } },
                        label = { Text(labelForProgressShape(shape)) }
                    )
                }
            }
            Text("Track color")
            ColorPickerRow(
                color = style.trackColor,
                onColorChange = { viewModel.updateShape { s -> s.copy(trackColor = it) } },
                recentColors = recentColors,
                onCustomColorCommitted = viewModel::addRecentColor,
            )
            Text("Fill color")
            ColorPickerRow(
                color = style.fillColorBar,
                onColorChange = { viewModel.updateShape { s -> s.copy(fillColorBar = it) } },
                recentColors = recentColors,
                onCustomColorCommitted = viewModel::addRecentColor,
            )
            if (style.progressShape != ProgressShape.LINE) {
                LabeledSlider("Stroke width", style.strokeWidth, 4f, 80f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
                    viewModel.updateShape(immediate = false) { it.copy(strokeWidth = v) }
                }
                if (style.progressShape == ProgressShape.ROUNDED_SQUARE) {
                    LabeledSlider("Corner radius", style.cornerRadius, 0f, 200f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
                        viewModel.updateShape(immediate = false) { it.copy(cornerRadius = v) }
                    }
                }
            }
        }

        TapActionPicker(element, viewModel)
    }
}

private fun labelForProgressShape(shape: ProgressShape): String = when (shape) {
    ProgressShape.LINE -> "Line"
    ProgressShape.RING -> "Circle"
    ProgressShape.SQUARE -> "Square"
    ProgressShape.ROUNDED_SQUARE -> "Rounded"
}

private fun labelForProgressSource(source: ProgressSource): String = when (source) {
    ProgressSource.BOOK -> "Book"
    ProgressSource.CHAPTER -> "Chapter"
}
