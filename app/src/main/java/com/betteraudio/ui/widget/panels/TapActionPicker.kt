package com.betteraudio.ui.widget.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.widget.WidgetEditorViewModel
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.TapAction
import com.betteraudio.ui.haptics.*

/** Tap-action selector for TEXT/IMAGE/SHAPE elements — the "more interactivity" for those types:
 *  a photo or line of text can behave like a button on the real widget. */
@Composable
fun TapActionPicker(element: ElementSpec, viewModel: WidgetEditorViewModel) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("On tap")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TapAction.entries) { action ->
                HapticFilterChip(
                    selected = element.tapAction == action,
                    onClick = { viewModel.updateSelected { it.copy(tapAction = action) } },
                    label = { Text(labelFor(action)) }
                )
            }
        }
    }
}

private fun labelFor(action: TapAction): String = when (action) {
    TapAction.NONE -> "None"
    TapAction.OPEN_APP -> "Open app"
    TapAction.OPEN_PLAYER -> "Open player"
    TapAction.PLAY_PAUSE -> "Play/Pause"
    TapAction.SKIP_FORWARD -> "Skip forward"
    TapAction.SKIP_BACK -> "Skip back"
    TapAction.NEXT_CHAPTER -> "Next chapter"
    TapAction.PREV_CHAPTER -> "Prev chapter"
}
