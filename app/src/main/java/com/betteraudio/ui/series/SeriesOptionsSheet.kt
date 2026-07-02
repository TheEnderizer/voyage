package com.betteraudio.ui.series

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.Series

/**
 * Series-level playback + metadata defaults. Every member book inherits these unless the book
 * has its own value. "Use app default" clears a field back to inherit-nothing (null).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesOptionsSheet(
    series: Series,
    onSave: (Series) -> Unit,
    onDismiss: () -> Unit
) {
    var speed by remember(series.id) { mutableStateOf(series.playbackSpeed) }        // null = app default
    var boost by remember(series.id) { mutableStateOf(series.boostDb) }              // null = none
    var skipSilence by remember(series.id) { mutableStateOf(series.skipSilenceEnabled == true) }
    var author by remember(series.id) { mutableStateOf(series.author ?: "") }
    var narrator by remember(series.id) { mutableStateOf(series.narrator ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Series options", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(
                "These apply to every book in the series unless a book has its own setting.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Speed
            SettingRow(
                label = "Playback speed",
                value = speed?.let { "%.2f×".format(it) } ?: "App default",
                onClear = { speed = null }.takeIf { speed != null }
            ) {
                Slider(
                    value = speed ?: 1.0f,
                    onValueChange = { speed = (Math.round(it * 20f) / 20f) },
                    valueRange = 0.5f..3.0f
                )
            }

            // Boost
            SettingRow(
                label = "Volume boost",
                value = boost?.let { "$it dB" } ?: "None",
                onClear = { boost = null }.takeIf { boost != null }
            ) {
                Slider(
                    value = (boost ?: 0).toFloat(),
                    onValueChange = { boost = it.toInt() },
                    valueRange = 0f..12f,
                    steps = 11
                )
            }

            // Skip silence
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Skip silence", style = MaterialTheme.typography.titleSmall)
                    Text("Auto-skip silent gaps for this series' books",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = skipSilence, onCheckedChange = { skipSilence = it })
            }

            OutlinedTextField(
                value = author, onValueChange = { author = it },
                label = { Text("Series author") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = narrator, onValueChange = { narrator = it },
                label = { Text("Series narrator") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        onSave(
                            series.copy(
                                playbackSpeed = speed,
                                boostDb = boost,
                                skipSilenceEnabled = if (skipSilence) true else null,
                                author = author.trim().ifBlank { null },
                                narrator = narrator.trim().ifBlank { null }
                            )
                        )
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClear: (() -> Unit)?,
    control: @Composable () -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            if (onClear != null) {
                TextButton(onClick = onClear) { Text("Use app default") }
            }
        }
        control()
    }
}
