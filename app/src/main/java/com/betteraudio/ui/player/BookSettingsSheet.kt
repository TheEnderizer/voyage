package com.betteraudio.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.data.model.BookWithProgress
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSettingsSheet(
    bwp: BookWithProgress,
    currentBoostDb: Int = 0,
    onDismiss: () -> Unit,
    onStatusChange: (BookStatus) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onBoostChange: (Int) -> Unit = {},
    onSeriesChange: (name: String?, order: Float?) -> Unit,
    onChangeCover: () -> Unit
) {
    val book = bwp.book
    var speed by remember { mutableFloatStateOf(bwp.progress?.playbackSpeed ?: 1f) }
    var boost by remember(currentBoostDb) { mutableIntStateOf(currentBoostDb) }
    var seriesName by remember { mutableStateOf(book.seriesName ?: "") }
    var seriesOrder by remember { mutableStateOf(book.seriesOrder?.toInt()?.toString() ?: "") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Book Settings", style = MaterialTheme.typography.titleLarge)

            // ── Status ────────────────────────────────────────────────
            SettingSection("Status") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookStatus.entries.forEach { status ->
                        FilterChip(
                            selected = book.status == status,
                            onClick = { onStatusChange(status); onDismiss() },
                            label = { Text(status.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // ── Playback Speed ────────────────────────────────────────
            SettingSection("Default Speed  ${"%.2f".format(speed)}x") {
                Slider(
                    value = speed,
                    onValueChange = { raw ->
                        speed = (raw / 0.05f).roundToInt() * 0.05f
                    },
                    onValueChangeFinished = { onSpeedChange(speed) },
                    valueRange = 0.5f..3.0f,
                    steps = 49,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0.5×", style = MaterialTheme.typography.labelSmall)
                    Text("3.0×", style = MaterialTheme.typography.labelSmall)
                }
            }

            // ── Volume Boost ─────────────────────────────────────────
            SettingSection("Volume Boost  ${boost} dB") {
                Slider(
                    value = boost.toFloat(),
                    onValueChange = { boost = it.toInt() },
                    onValueChangeFinished = { onBoostChange(boost) },
                    valueRange = 0f..24f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 dB", style = MaterialTheme.typography.labelSmall)
                    Text("+24 dB", style = MaterialTheme.typography.labelSmall)
                }
                if (boost > 12) {
                    Text(
                        "High boost may cause distortion",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── Cover Art ────────────────────────────────────────────
            SettingSection("Cover Art") {
                OutlinedButton(onClick = { onChangeCover(); onDismiss() }) {
                    Icon(Icons.Default.Photo, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose from gallery")
                }
            }

            // ── Series ───────────────────────────────────────────────
            SettingSection("Series") {
                OutlinedTextField(
                    value = seriesName,
                    onValueChange = { seriesName = it },
                    label = { Text("Series name") },
                    placeholder = { Text("e.g. The Witcher") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = seriesOrder,
                    onValueChange = { seriesOrder = it.filter { c -> c.isDigit() } },
                    label = { Text("Position in series") },
                    placeholder = { Text("e.g. 1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val name = seriesName.trim().ifBlank { null }
                        val order = seriesOrder.trim().toFloatOrNull()
                        onSeriesChange(name, order)
                        onDismiss()
                    }) { Text("Save series") }
                    if (book.seriesName != null) {
                        OutlinedButton(onClick = {
                            onSeriesChange(null, null)
                            onDismiss()
                        }) { Text("Remove from series") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}
