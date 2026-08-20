package com.betteraudio.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.settings.ClockMinutesPickerDialog
import com.betteraudio.ui.settings.formatClockMinutes
import kotlin.math.roundToInt
import com.betteraudio.ui.haptics.*

/**
 * Fade-out / shake-to-extend / auto-start-schedule controls — shared between Settings → Playback
 * and [SleepTimerSheet]'s long-press options, so both render identical controls instead of
 * duplicating the slider/picker logic. Stateless: values in, setters out.
 */
@Composable
fun SleepTimerTuningControls(
    fadeSeconds: Int,
    shakeEnabled: Boolean,
    shakeResetMinutes: Int,
    scheduleEnabled: Boolean,
    scheduleStartMinutes: Int,
    scheduleEndMinutes: Int,
    scheduleDefaultMinutes: Int,
    onSetFadeSeconds: (Int) -> Unit,
    onSetShakeEnabled: (Boolean) -> Unit,
    onSetShakeResetMinutes: (Int) -> Unit,
    onSetScheduleEnabled: (Boolean) -> Unit,
    onSetScheduleStartMinutes: (Int) -> Unit,
    onSetScheduleEndMinutes: (Int) -> Unit,
    onSetScheduleDefaultMinutes: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Fade-out length (0 = hard pause, 1-30s)
        var fadeSlider by remember(fadeSeconds) { mutableFloatStateOf(fadeSeconds.toFloat()) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Fade out over", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (fadeSlider == 0f) "Off (hard pause)" else "${fadeSlider.toInt()} s",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        HapticSlider(
            value = fadeSlider,
            onValueChange = { fadeSlider = it.toInt().toFloat() },
            onValueChangeFinished = { onSetFadeSeconds(fadeSlider.toInt()) },
            valueRange = 0f..30f,
            steps = 29,
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Shake to extend", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Shake the phone near the end of the timer (or right after it pauses) to keep listening.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HapticSwitch(checked = shakeEnabled, onCheckedChange = onSetShakeEnabled)
        }
        if (shakeEnabled) {
            var shakeSlider by remember(shakeResetMinutes) { mutableFloatStateOf(shakeResetMinutes.toFloat()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Extend by", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${shakeSlider.toInt()} min",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            HapticSlider(
                value = shakeSlider,
                onValueChange = { shakeSlider = it.toInt().toFloat() },
                onValueChangeFinished = { onSetShakeResetMinutes(shakeSlider.toInt().coerceAtLeast(1)) },
                valueRange = 1f..60f,
                steps = 58,
                modifier = Modifier.fillMaxWidth()
            )
        }

        HorizontalDivider()

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-start nightly", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Automatically arm the timer when you start playing within this window.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HapticSwitch(checked = scheduleEnabled, onCheckedChange = onSetScheduleEnabled)
        }
        if (scheduleEnabled) {
            var showStartPicker by remember { mutableStateOf(false) }
            var showEndPicker by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HapticOutlinedButton(onClick = { showStartPicker = true }, modifier = Modifier.weight(1f)) {
                    Text("From ${formatClockMinutes(scheduleStartMinutes)}")
                }
                HapticOutlinedButton(onClick = { showEndPicker = true }, modifier = Modifier.weight(1f)) {
                    Text("To ${formatClockMinutes(scheduleEndMinutes)}")
                }
            }
            if (showStartPicker) {
                ClockMinutesPickerDialog(
                    initialMinutes = scheduleStartMinutes,
                    onDismiss = { showStartPicker = false },
                    onConfirm = { onSetScheduleStartMinutes(it); showStartPicker = false }
                )
            }
            if (showEndPicker) {
                ClockMinutesPickerDialog(
                    initialMinutes = scheduleEndMinutes,
                    onDismiss = { showEndPicker = false },
                    onConfirm = { onSetScheduleEndMinutes(it); showEndPicker = false }
                )
            }

            var defaultSlider by remember(scheduleDefaultMinutes) { mutableFloatStateOf(scheduleDefaultMinutes.toFloat()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Duration", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${defaultSlider.toInt()} min",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            HapticSlider(
                value = defaultSlider,
                onValueChange = { defaultSlider = it.toInt().toFloat() },
                onValueChangeFinished = { onSetScheduleDefaultMinutes(defaultSlider.toInt().coerceAtLeast(1)) },
                valueRange = 5f..120f,
                steps = 22,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Opened by long-pressing the player's sleep icon. A single tap on that icon starts/cancels a
 * timer directly at [timerMinutes] (see the player screens); this sheet holds the slider + custom
 * entry that sets [timerMinutes], plus every other sleep-timer option from Settings → Playback,
 * inlined so they're reachable without leaving the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    remainingMs: Long,
    isEndOfChapter: Boolean = false,
    hasChapters: Boolean = false,
    timerMinutes: Int,
    fadeSeconds: Int,
    shakeEnabled: Boolean,
    shakeResetMinutes: Int,
    scheduleEnabled: Boolean,
    scheduleStartMinutes: Int,
    scheduleEndMinutes: Int,
    scheduleDefaultMinutes: Int,
    onSetTimer: (Long) -> Unit,
    onSetTimerMinutes: (Int) -> Unit,
    onSetEndOfChapter: () -> Unit = {},
    onSetFadeSeconds: (Int) -> Unit,
    onSetShakeEnabled: (Boolean) -> Unit,
    onSetShakeResetMinutes: (Int) -> Unit,
    onSetScheduleEnabled: (Boolean) -> Unit,
    onSetScheduleStartMinutes: (Int) -> Unit,
    onSetScheduleEndMinutes: (Int) -> Unit,
    onSetScheduleDefaultMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = com.betteraudio.ui.components.appSheetColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)

            if (remainingMs > 0L) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Timer active", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(
                                if (isEndOfChapter) "Pausing at end of chapter (~${formatTimerMs(remainingMs)})"
                                else "Pausing in ${formatTimerMs(remainingMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        HapticOutlinedButton(onClick = { onSetTimer(0L); onDismiss() }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            Text("Duration", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            var minutesSlider by remember(timerMinutes) { mutableFloatStateOf(timerMinutes.toFloat()) }
            var customText by remember(timerMinutes) { mutableStateOf(timerMinutes.toString()) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Minutes", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${minutesSlider.toInt()} min",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            HapticSlider(
                value = minutesSlider,
                onValueChange = {
                    minutesSlider = it.roundToInt().toFloat()
                    customText = minutesSlider.toInt().toString()
                },
                onValueChangeFinished = { onSetTimerMinutes(minutesSlider.toInt()) },
                valueRange = 1f..180f,
                steps = 178,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = customText,
                onValueChange = { text ->
                    val filtered = text.filter { it.isDigit() }.take(3)
                    customText = filtered
                    filtered.toIntOrNull()?.coerceIn(1, 180)?.let {
                        minutesSlider = it.toFloat()
                        onSetTimerMinutes(it)
                    }
                },
                label = { Text("Custom minutes") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            HapticButton(
                onClick = {
                    onSetTimer(minutesSlider.toInt() * 60_000L)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start") }

            if (hasChapters) {
                HapticOutlinedButton(
                    onClick = { onSetEndOfChapter(); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("End of chapter") }
            }

            HorizontalDivider()

            Text("More options", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            SleepTimerTuningControls(
                fadeSeconds = fadeSeconds,
                shakeEnabled = shakeEnabled,
                shakeResetMinutes = shakeResetMinutes,
                scheduleEnabled = scheduleEnabled,
                scheduleStartMinutes = scheduleStartMinutes,
                scheduleEndMinutes = scheduleEndMinutes,
                scheduleDefaultMinutes = scheduleDefaultMinutes,
                onSetFadeSeconds = onSetFadeSeconds,
                onSetShakeEnabled = onSetShakeEnabled,
                onSetShakeResetMinutes = onSetShakeResetMinutes,
                onSetScheduleEnabled = onSetScheduleEnabled,
                onSetScheduleStartMinutes = onSetScheduleStartMinutes,
                onSetScheduleEndMinutes = onSetScheduleEndMinutes,
                onSetScheduleDefaultMinutes = onSetScheduleDefaultMinutes
            )
        }
    }
}

private fun formatTimerMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
