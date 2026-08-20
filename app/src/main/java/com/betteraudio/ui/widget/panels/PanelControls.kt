package com.betteraudio.ui.widget.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.betteraudio.ui.haptics.*

/** A labeled slider row shared by every style panel that exposes a numeric range (dim, blur,
 *  corner radius, opacity, text size, etc.). */
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
        HapticSlider(
            value = value, onValueChange = onValueChange,
            valueRange = min..max,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}
