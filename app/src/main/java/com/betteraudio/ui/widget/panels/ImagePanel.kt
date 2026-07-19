package com.betteraudio.ui.widget.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.ui.widget.copyPickedWidgetImage
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.ImageFit
import com.betteraudio.widget.model.ImageStyle
import kotlinx.coroutines.launch

@Composable
fun ImagePanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val style = element.image ?: ImageStyle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = copyPickedWidgetImage(context, uri)
            if (path != null) viewModel.updateSelected { it.copy(imagePath = path) }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Image")

        if (element.type == ElementType.CUSTOM_IMAGE) {
            FilledTonalButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (element.imagePath == null) "Choose image" else "Replace image") }
        }

        Text("Fit")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageFit.entries.forEach { fit ->
                FilterChip(
                    selected = style.fit == fit,
                    onClick = { viewModel.updateImage { it.copy(fit = fit) } },
                    label = { Text(fit.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                )
            }
        }

        LabeledSlider("Corner radius", style.cornerRadius, 0f, 200f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateImage(immediate = false) { it.copy(cornerRadius = v) }
        }
        LabeledSlider("Border width", style.borderWidth, 0f, 20f, onValueChangeFinished = viewModel::endContinuousEdit) { v ->
            viewModel.updateImage(immediate = false) { it.copy(borderWidth = v) }
        }
        if (style.borderWidth > 0f) {
            Text("Border color")
            ColorPickerRow(
                color = style.borderColor,
                onColorChange = { viewModel.updateImage { s -> s.copy(borderColor = it) } }
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Shadow")
            Switch(checked = style.shadow, onCheckedChange = { viewModel.updateImage { s -> s.copy(shadow = it) } })
        }

        TapActionPicker(element, viewModel)
    }
}
