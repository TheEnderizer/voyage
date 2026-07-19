package com.betteraudio.ui.widget.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.ui.widget.copyPickedWidgetImage
import com.betteraudio.widget.model.BgSource
import kotlinx.coroutines.launch

@Composable
fun BackgroundPanel(viewModel: WidgetEditorViewModel, background: com.betteraudio.widget.model.BackgroundSpec) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = copyPickedWidgetImage(context, uri)
            if (path != null) viewModel.updateBackground { it.copy(source = BgSource.CUSTOM_IMAGE, imagePath = path) }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Background")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BgSource.entries) { source ->
                FilterChip(
                    selected = background.source == source,
                    onClick = {
                        if (source == BgSource.CUSTOM_IMAGE) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } else {
                            viewModel.updateBackground { it.copy(source = source) }
                        }
                    },
                    label = { Text(labelFor(source)) }
                )
            }
        }

        if (background.source == BgSource.SOLID || background.source == BgSource.GRADIENT) {
            Text("Color")
            ColorPickerRow(
                color = background.color,
                onColorChange = { viewModel.updateBackground { bg -> bg.copy(color = it) } }
            )
        }
        if (background.source == BgSource.GRADIENT) {
            Text("Gradient end color")
            ColorPickerRow(
                color = background.colorEnd ?: background.color,
                onColorChange = { viewModel.updateBackground { bg -> bg.copy(colorEnd = it) } }
            )
        }

        LabeledSlider("Dim", background.dim, 0f, 0.8f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackground(immediate = false) { it.copy(dim = v) }
        }
        LabeledSlider("Blur", background.blurRadius, 0f, 60f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackground(immediate = false) { it.copy(blurRadius = v) }
        }
        LabeledSlider("Corner radius", background.cornerRadius, 0f, 200f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackground(immediate = false) { it.copy(cornerRadius = v) }
        }
        LabeledSlider("Opacity", background.opacity, 0.2f, 1f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackground(immediate = false) { it.copy(opacity = v) }
        }
    }
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("%.0f".format(value))
        }
        Slider(
            value = value, onValueChange = onValueChange,
            valueRange = min..max,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

private fun labelFor(source: BgSource): String = when (source) {
    BgSource.BOOK_COVER -> "Book cover"
    BgSource.SERIES_COVER -> "Series cover"
    BgSource.CUSTOM_IMAGE -> "Custom image"
    BgSource.SOLID -> "Solid color"
    BgSource.GRADIENT -> "Gradient"
    BgSource.TRANSPARENT -> "Transparent"
}
