package com.betteraudio.ui.widget.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.ui.widget.copyPickedWidgetImage
import com.betteraudio.widget.model.BackgroundLayerStyle
import com.betteraudio.widget.model.BgSource
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ShapeKind
import kotlinx.coroutines.launch

/** Style panel for a BACKGROUND_LAYER element — whether it's the design's base layer (full-bleed,
 *  defines the widget's outer shape) or an ordinary decorative layer higher in the stack, its fill
 *  options are identical; only the base layer's [ShapeKind]/corner-radius also shapes the widget
 *  itself (see WidgetPainter.paint). */
@Composable
fun BackgroundLayerPanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val background = element.backgroundLayer ?: BackgroundLayerStyle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = copyPickedWidgetImage(context, uri)
            if (path != null) viewModel.updateBackgroundLayer { it.copy(source = BgSource.CUSTOM_IMAGE, imagePath = path) }
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
                            viewModel.updateBackgroundLayer { it.copy(source = source) }
                        }
                    },
                    label = { Text(labelForSource(source)) }
                )
            }
        }

        if (background.source == BgSource.SOLID || background.source == BgSource.GRADIENT) {
            Text("Color")
            ColorPickerRow(
                color = background.color,
                onColorChange = { viewModel.updateBackgroundLayer { bg -> bg.copy(color = it) } }
            )
        }
        if (background.source == BgSource.GRADIENT) {
            Text("Gradient end color")
            ColorPickerRow(
                color = background.colorEnd ?: background.color,
                onColorChange = { viewModel.updateBackgroundLayer { bg -> bg.copy(colorEnd = it) } }
            )
        }

        Text("Shape")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ShapeKind.entries) { kind ->
                FilterChip(
                    selected = background.shapeKind == kind,
                    onClick = { viewModel.updateBackgroundLayer { it.copy(shapeKind = kind) } },
                    label = { Text(labelForShape(kind)) }
                )
            }
        }

        LabeledSlider("Dim", background.dim, 0f, 0.8f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackgroundLayer(immediate = false) { it.copy(dim = v) }
        }
        LabeledSlider("Blur", background.blurRadius, 0f, 60f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackgroundLayer(immediate = false) { it.copy(blurRadius = v) }
        }
        if (background.shapeKind == ShapeKind.RECT) {
            LabeledSlider("Corner radius", background.cornerRadius, 0f, 200f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
                viewModel.updateBackgroundLayer(immediate = false) { it.copy(cornerRadius = v) }
            }
        }
        LabeledSlider("Opacity", background.opacity, 0.2f, 1f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateBackgroundLayer(immediate = false) { it.copy(opacity = v) }
        }

        TapActionPicker(element, viewModel)
    }
}

private fun labelForSource(source: BgSource): String = when (source) {
    BgSource.BOOK_COVER -> "Book cover"
    BgSource.SERIES_COVER -> "Series cover"
    BgSource.CUSTOM_IMAGE -> "Custom image"
    BgSource.SOLID -> "Solid color"
    BgSource.GRADIENT -> "Gradient"
    BgSource.TRANSPARENT -> "Transparent"
}

private fun labelForShape(kind: ShapeKind): String = when (kind) {
    ShapeKind.RECT -> "Rectangle"
    ShapeKind.PILL -> "Pill"
    ShapeKind.CIRCLE -> "Circle"
    ShapeKind.SQUIRCLE -> "Squircle"
}
