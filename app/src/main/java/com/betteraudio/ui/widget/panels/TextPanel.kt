package com.betteraudio.ui.widget.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.HorizontalTextAlign
import com.betteraudio.widget.model.TextStyle

private val WEIGHTS = listOf(400 to "Regular", 500 to "Medium", 600 to "Semibold", 700 to "Bold", 800 to "Black")

@Composable
fun TextPanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val style = element.text ?: TextStyle()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Text")

        if (element.type == ElementType.CUSTOM_TEXT) {
            OutlinedTextField(
                value = element.customText ?: "",
                onValueChange = { text -> viewModel.updateSelected { it.copy(customText = text) } },
                label = { Text("Custom text") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        LabeledSlider("Size", style.sizeUnits, 20f, 160f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateText(immediate = false) { it.copy(sizeUnits = v) }
        }

        Text("Weight")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WEIGHTS.forEach { (weight, label) ->
                FilterChip(
                    selected = style.weight == weight,
                    onClick = { viewModel.updateText { it.copy(weight = weight) } },
                    label = { Text(label) }
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Italic")
            Switch(checked = style.italic, onCheckedChange = { viewModel.updateText { s -> s.copy(italic = it) } })
        }

        Text("Color")
        ColorPickerRow(
            color = style.color,
            usesAccent = style.usesAccent,
            onAccentToggle = { viewModel.updateText { s -> s.copy(usesAccent = it) } },
            onColorChange = { viewModel.updateText { s -> s.copy(color = it) } }
        )

        Text("Alignment")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalTextAlign.entries.forEach { align ->
                FilterChip(
                    selected = style.align == align,
                    onClick = { viewModel.updateText { it.copy(align = align) } },
                    label = { Text(align.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                )
            }
        }

        LabeledSlider("Max lines", style.maxLines.toFloat(), 1f, 4f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateText(immediate = false) { it.copy(maxLines = v.toInt().coerceIn(1, 4)) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Shadow")
            Switch(checked = style.shadow, onCheckedChange = { viewModel.updateText { s -> s.copy(shadow = it) } })
        }

        TapActionPicker(element, viewModel)
    }
}
