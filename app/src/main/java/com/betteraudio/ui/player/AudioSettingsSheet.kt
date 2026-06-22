package com.betteraudio.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.db.entities.AudioPreset
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private val BOOST_PRESETS = listOf(0, 3, 6, 9, 12)
private val EQ_BAND_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
private const val EQ_MIN_MB = -1500
private const val EQ_MAX_MB = 1500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val audioPresets by viewModel.audioPresets.collectAsStateWithLifecycle()
    val eqBands by viewModel.eqBandsMillibels.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var speedValue by remember(playbackState.speed) { mutableFloatStateOf(playbackState.speed) }
    var boostValue by remember(viewModel.currentBoostDb) { mutableIntStateOf(viewModel.currentBoostDb) }
    var localEqBands by remember(eqBands) {
        mutableStateOf(eqBands ?: IntArray(5) { 0 })
    }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Speed") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Boost") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("EQ") })
            }

            Box(modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp)
            ) {
                when (selectedTab) {
                    0 -> SpeedTab(
                        speedValue = speedValue,
                        onSpeedChange = { speedValue = it },
                        onSpeedCommit = { viewModel.setSpeed(it) }
                    )
                    1 -> BoostTab(
                        boostValue = boostValue,
                        onBoostChange = {
                            boostValue = it
                            viewModel.setVolumeBoost(it)
                        }
                    )
                    2 -> EqTab(
                        bands = localEqBands,
                        onBandChange = { band, value ->
                            localEqBands = localEqBands.copyOf().also { it[band] = value }
                            viewModel.setEqBands(localEqBands)
                        },
                        onFlat = {
                            localEqBands = IntArray(5) { 0 }
                            viewModel.setEqBands(null)
                        }
                    )
                }
            }

            HorizontalDivider()

            // Preset section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Presets", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { presetName = ""; showSaveDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Save preset")
                }
            }

            if (audioPresets.isEmpty()) {
                Text(
                    "No saved presets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    items(audioPresets, key = { it.id }) { preset ->
                        PresetChip(
                            preset = preset,
                            onLoad = { viewModel.loadAudioPreset(preset) },
                            onDelete = { viewModel.deleteAudioPreset(preset.id) },
                            onSetDefault = { viewModel.setAsDefaultPreset(preset.id) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset name") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (presetName.isNotBlank()) {
                            viewModel.saveAudioPreset(presetName)
                        }
                        showSaveDialog = false
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SpeedTab(
    speedValue: Float,
    onSpeedChange: (Float) -> Unit,
    onSpeedCommit: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "${String.format("%.2f", speedValue)}×",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Slider(
            value = speedValue,
            onValueChange = onSpeedChange,
            onValueChangeFinished = { onSpeedCommit(speedValue) },
            valueRange = 0.5f..3.0f,
            steps = 49,
            modifier = Modifier.fillMaxWidth()
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(SPEED_PRESETS) { preset ->
                FilterChip(
                    selected = kotlin.math.abs(speedValue - preset) < 0.01f,
                    onClick = { onSpeedChange(preset); onSpeedCommit(preset) },
                    label = { Text("${preset}×") }
                )
            }
        }
    }
}

@Composable
private fun BoostTab(
    boostValue: Int,
    onBoostChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "+${boostValue} dB",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Slider(
            value = boostValue.toFloat(),
            onValueChange = { onBoostChange(it.roundToInt()) },
            valueRange = 0f..24f,
            steps = 23,
            modifier = Modifier.fillMaxWidth()
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(BOOST_PRESETS) { preset ->
                FilterChip(
                    selected = boostValue == preset,
                    onClick = { onBoostChange(preset) },
                    label = { Text(if (preset == 0) "Off" else "+${preset} dB") }
                )
            }
        }
    }
}

@Composable
private fun EqTab(
    bands: IntArray,
    onBandChange: (band: Int, value: Int) -> Unit,
    onFlat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Equalizer", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onFlat) { Text("Flat") }
        }
        EQ_BAND_LABELS.forEachIndexed { index, label ->
            val levelMb = bands.getOrElse(index) { 0 }
            val levelDb = levelMb / 100f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(64.dp)
                )
                Slider(
                    value = levelMb.toFloat(),
                    onValueChange = { onBandChange(index, it.roundToInt()) },
                    valueRange = EQ_MIN_MB.toFloat()..EQ_MAX_MB.toFloat(),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${if (levelDb >= 0) "+" else ""}${String.format("%.1f", levelDb)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    preset: AudioPreset,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = onLoad,
            label = { Text(preset.name) },
            leadingIcon = if (preset.isDefault) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null
        )
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(text = { Text("Load") }, onClick = { showMenu = false; onLoad() })
            DropdownMenuItem(text = { Text("Set as default") }, onClick = { showMenu = false; onSetDefault() })
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { showMenu = false; onDelete() }
            )
        }
    }
}
