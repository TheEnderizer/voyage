package com.betteraudio.ui.widget.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.ShapeKind
import com.betteraudio.widget.model.ShapeStyle

@Composable
fun ShapePanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val style = element.shape ?: ShapeStyle()

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
                onColorChange = { viewModel.updateShape { s -> s.copy(fillColor = it) } }
            )
            if (style.kind == ShapeKind.RECT) {
                LabeledSlider("Corner radius", style.cornerRadius, 0f, 200f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
                    viewModel.updateShape(immediate = false) { it.copy(cornerRadius = v) }
                }
            }
        } else {
            Text("Track color")
            ColorPickerRow(
                color = style.trackColor,
                onColorChange = { viewModel.updateShape { s -> s.copy(trackColor = it) } }
            )
            Text("Fill color")
            ColorPickerRow(
                color = style.fillColorBar,
                onColorChange = { viewModel.updateShape { s -> s.copy(fillColorBar = it) } }
            )
        }

        TapActionPicker(element, viewModel)
    }
}
