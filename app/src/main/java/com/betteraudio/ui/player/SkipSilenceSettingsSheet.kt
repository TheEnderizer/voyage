package com.betteraudio.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The three skip-silence tuning sliders (minimum silence length, sensitivity, silence kept) —
 * shared between Settings → Playback and [SkipSilenceSettingsSheet] so both render identical
 * controls instead of duplicating the slider logic. Stateless: values in, setters out.
 */
@Composable
fun SkipSilenceControls(
    minMs: Long,
    threshold: Int,
    paddingMs: Long,
    onSetMinMs: (Long) -> Unit,
    onSetThreshold: (Int) -> Unit,
    onSetPaddingMs: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Skip silence", style = MaterialTheme.typography.titleSmall)
        Text(
            "Tune how silence is detected. Enable per book from the player.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Minimum silence length before trimming (0.2–3.0 s)
        var minSlider by remember(minMs) { mutableFloatStateOf(minMs / 1000f) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Minimum silence", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${"%.1f".format(minSlider)} s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = minSlider,
            onValueChange = { minSlider = (it / 0.1f).roundToInt() * 0.1f },
            onValueChangeFinished = { onSetMinMs((minSlider * 1000).toLong()) },
            valueRange = 0.2f..3.0f,
            steps = 27,
            modifier = Modifier.fillMaxWidth()
        )

        // Sensitivity (PCM threshold level). Higher slider = more aggressive trimming.
        var sensSlider by remember(threshold) { mutableFloatStateOf(threshold.toFloat()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Sensitivity", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${sensSlider.toInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = sensSlider,
            onValueChange = { sensSlider = it },
            onValueChangeFinished = { onSetThreshold(sensSlider.toInt()) },
            valueRange = 256f..4096f,
            modifier = Modifier.fillMaxWidth()
        )

        // How much of each skipped silence is left in place, so word onsets aren't clipped.
        var keepSlider by remember(paddingMs) { mutableFloatStateOf(paddingMs / 1000f) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Silence to keep", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${"%.1f".format(keepSlider)} s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = keepSlider,
            onValueChange = { keepSlider = (it / 0.1f).roundToInt() * 0.1f },
            onValueChangeFinished = { onSetPaddingMs((keepSlider * 1000).toLong()) },
            valueRange = 0f..2.0f,
            steps = 19,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "How much of each trimmed pause is left in, so words aren't cut off. " +
                "Changes apply immediately.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Opened by long-pressing the player's "Skip silence" toggle — the same tuning controls as
 *  Settings → Playback, in a bottom sheet so they're reachable without leaving the player. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkipSilenceSettingsSheet(
    minMs: Long,
    threshold: Int,
    paddingMs: Long,
    onSetMinMs: (Long) -> Unit,
    onSetThreshold: (Int) -> Unit,
    onSetPaddingMs: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = com.betteraudio.ui.components.appSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        SkipSilenceControls(
            minMs = minMs,
            threshold = threshold,
            paddingMs = paddingMs,
            onSetMinMs = onSetMinMs,
            onSetThreshold = onSetThreshold,
            onSetPaddingMs = onSetPaddingMs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp)
        )
    }
}
