package com.betteraudio.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.db.entities.AudioPreset
import kotlin.math.roundToInt

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
    val speedPresets by viewModel.speedPresets.collectAsStateWithLifecycle()
    val boostPresets by viewModel.boostPresets.collectAsStateWithLifecycle()
    val eqPresets by viewModel.eqPresets.collectAsStateWithLifecycle()
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

            // ── This book: per-book local value + reset/delete override ───────────────
            val (thisBookValue, isOverridden, onResetBook) = when (selectedTab) {
                0 -> Triple(
                    "${String.format("%.2f", speedValue)}×",
                    speedValue != viewModel.defaultSpeed,
                    { viewModel.clearBookSpeed(); speedValue = viewModel.defaultSpeed }
                )
                1 -> Triple(
                    "+${boostValue} dB",
                    boostValue != 0,
                    { viewModel.clearBookBoost(); boostValue = 0 }
                )
                else -> Triple(
                    if (localEqBands.any { it != 0 }) "Custom" else "Flat",
                    localEqBands.any { it != 0 },
                    { viewModel.clearBookEq(); localEqBands = IntArray(5) { 0 } }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("This book", style = MaterialTheme.typography.labelLarge)
                    Text(
                        thisBookValue,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onResetBook() }, enabled = isOverridden) {
                    Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
            }

            HorizontalDivider()

            // Preset section — only shows presets for the active tab type
            val activePresets = when (selectedTab) {
                0 -> speedPresets
                1 -> boostPresets
                else -> eqPresets
            }

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

            if (activePresets.isEmpty()) {
                Text(
                    "No saved presets — tap + to save the current value",
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
                    items(activePresets, key = { it.id }) { preset ->
                        PresetChip(
                            preset = preset,
                            onLoad = { viewModel.loadAudioPreset(preset) },
                            onOverwrite = { viewModel.overwritePreset(preset) },
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
        val tabType = when (selectedTab) {
            0 -> AudioPreset.TYPE_SPEED
            1 -> AudioPreset.TYPE_BOOST
            else -> AudioPreset.TYPE_EQ
        }
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
                            viewModel.saveAudioPreset(presetName, tabType)
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

private const val SPEED_MIN = 0.5f
private const val SPEED_MAX = 3.0f
private const val SPEED_STEP = 0.05f

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdjustButton(Icons.Default.Remove, "Slower") {
                val v = (((speedValue - SPEED_STEP) / SPEED_STEP).roundToInt() * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)
                onSpeedChange(v); onSpeedCommit(v)
            }
            Slider(
                value = speedValue,
                onValueChange = onSpeedChange,
                onValueChangeFinished = { onSpeedCommit(speedValue) },
                valueRange = SPEED_MIN..SPEED_MAX,
                steps = 49,
                modifier = Modifier.weight(1f)
            )
            AdjustButton(Icons.Default.Add, "Faster") {
                val v = (((speedValue + SPEED_STEP) / SPEED_STEP).roundToInt() * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)
                onSpeedChange(v); onSpeedCommit(v)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            AdjustButton(Icons.Default.Remove, "Less boost") {
                onBoostChange((boostValue - 1).coerceIn(0, 24))
            }
            Slider(
                value = boostValue.toFloat(),
                onValueChange = { onBoostChange(it.roundToInt()) },
                valueRange = 0f..24f,
                steps = 23,
                modifier = Modifier.weight(1f)
            )
            AdjustButton(Icons.Default.Add, "More boost") {
                onBoostChange((boostValue + 1).coerceIn(0, 24))
            }
        }
    }
}

@Composable
private fun AdjustButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, cd, Modifier.size(20.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetChip(
    preset: AudioPreset,
    onLoad: () -> Unit,
    onOverwrite: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        AssistChip(
            onClick = onLoad,
            label = { Text(preset.name) },
            leadingIcon = if (preset.isDefault) {
                { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else null,
            modifier = Modifier.combinedClickable(
                onClick = onLoad,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOverwrite()
                }
            )
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
