package com.betteraudio.ui.widget.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.ColorPickerRow
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.ui.widget.copyPickedWidgetImage
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.ImageFit
import com.betteraudio.widget.model.ImageStyle
import kotlinx.coroutines.launch
import com.betteraudio.ui.haptics.*

private data class AspectPreset(val label: String, val ratio: Float)
private val ASPECT_PRESETS = listOf(
    AspectPreset("1:1", 1f),
    AspectPreset("2:3", 2f / 3f),
    AspectPreset("3:2", 3f / 2f),
    AspectPreset("16:9", 16f / 9f),
)

@Composable
fun ImagePanel(viewModel: WidgetEditorViewModel, element: ElementSpec) {
    val style = element.image ?: ImageStyle()
    val recentColors by viewModel.recentColors.collectAsState()
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
            HapticFilledTonalButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (element.imagePath == null) "Choose image" else "Replace image") }
        }

        Text("Fit")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageFit.entries.forEach { fit ->
                HapticFilterChip(
                    selected = style.fit == fit,
                    onClick = { viewModel.updateImage { it.copy(fit = fit) } },
                    label = { Text(fit.name.lowercase().replaceFirstChar { c -> c.uppercase() }) }
                )
            }
        }

        AspectLockSection(viewModel, element, style)

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
                onColorChange = { viewModel.updateImage { s -> s.copy(borderColor = it) } },
                recentColors = recentColors,
                onCustomColorCommitted = viewModel::addRecentColor,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Shadow")
            HapticSwitch(checked = style.shadow, onCheckedChange = { viewModel.updateImage { s -> s.copy(shadow = it) } })
        }

        TapActionPicker(element, viewModel)
    }
}

/** Locks the width/height ratio so drag-resize keeps a fixed shape (e.g. a perfect circle/square
 *  when paired with a full corner radius) instead of stretching freely. Picking a preset or a
 *  custom value immediately conforms the element to that ratio; toggling the switch off unlocks it
 *  again without changing the current size. */
@Composable
private fun AspectLockSection(viewModel: WidgetEditorViewModel, element: ElementSpec, style: ImageStyle) {
    val locked = style.lockedAspect != null
    var customText by remember(element.id) {
        mutableStateOf(style.lockedAspect?.let { "%.2f".format(it) } ?: "")
    }

    fun applyRatio(ratio: Float) {
        if (ratio <= 0f) return
        viewModel.updateSelected {
            it.copy(
                h = (it.w / ratio).coerceAtLeast(20f),
                image = (it.image ?: ImageStyle()).copy(lockedAspect = ratio),
            )
        }
        customText = "%.2f".format(ratio)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Lock aspect ratio")
            HapticSwitch(
                checked = locked,
                onCheckedChange = { on ->
                    if (on) applyRatio(element.w / element.h.coerceAtLeast(1f))
                    else viewModel.updateImage { it.copy(lockedAspect = null) }
                }
            )
        }
        if (locked) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ASPECT_PRESETS.forEach { preset ->
                    HapticFilterChip(
                        selected = style.lockedAspect?.let { kotlin.math.abs(it - preset.ratio) < 0.01f } == true,
                        onClick = { applyRatio(preset.ratio) },
                        label = { Text(preset.label) }
                    )
                }
            }
            OutlinedTextField(
                value = customText,
                onValueChange = { text ->
                    customText = text
                    text.toFloatOrNull()?.let { applyRatio(it) }
                },
                label = { Text("Custom ratio (width ÷ height)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.width(220.dp),
            )
        }
    }
}
