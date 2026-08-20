package com.betteraudio.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.components.FolderBrowser
import com.betteraudio.ui.components.ImportStructureDialog
import com.betteraudio.ui.components.label
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.material.MaterialStyle
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import com.betteraudio.util.AppLog
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.betteraudio.ui.haptics.*

internal fun LazyListScope.playbackSection(
    skipForwardMs: Long,
    skipBackMs: Long,
    autoRewindSeconds: Int,
    autoRewindThresholdMinutes: Int,
    skipSilenceMinMs: Long,
    skipSilenceThreshold: Int,
    skipSilencePaddingMs: Long,
    sleepFadeSeconds: Int,
    sleepShakeEnabled: Boolean,
    sleepShakeResetMinutes: Int,
    sleepScheduleEnabled: Boolean,
    sleepScheduleStartMinutes: Int,
    sleepScheduleEndMinutes: Int,
    sleepScheduleDefaultMinutes: Int,
    headsetMultiPressEnabled: Boolean,
    headsetDoublePressAction: String,
    headsetTriplePressAction: String,
    btAutoResumeEnabled: Boolean,
    btAutoResumeWindowMinutes: Int,
    viewModel: SettingsViewModel
) {
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Skip forward", style = MaterialTheme.typography.titleSmall)
                IntervalChips(
                    options = listOf(10_000L, 15_000L, 30_000L, 60_000L),
                    selected = skipForwardMs,
                    onSelect = { viewModel.setSkipForward(it) }
                )
                Text("Skip back", style = MaterialTheme.typography.titleSmall)
                IntervalChips(
                    options = listOf(5_000L, 10_000L, 15_000L, 30_000L),
                    selected = skipBackMs,
                    onSelect = { viewModel.setSkipBack(it) }
                )

                Text("Default speed, boost & EQ", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The default audio for every book now comes from your global default preset. " +
                        "A book keeps its own settings if you change them individually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HapticFilledTonalButton(
                    onClick = { viewModel.navigateTo(SettingsSection.Presets) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Manage audio presets")
                }
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Auto-rewind", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Rewind when resuming after a long pause",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Trigger threshold slider (0 = off, 1–30 min)
                var thresholdSlider by remember(autoRewindThresholdMinutes) {
                    mutableFloatStateOf(autoRewindThresholdMinutes.toFloat())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Trigger after", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (thresholdSlider == 0f) "Off" else "${thresholdSlider.toInt()} min",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HapticIconButton(
                        onClick = {
                            val v = (thresholdSlider - 1).coerceAtLeast(0f)
                            thresholdSlider = v
                            viewModel.setAutoRewindThresholdMinutes(v.toInt())
                        },
                        modifier = Modifier.size(32.dp)
                    ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                    HapticSlider(
                        value = thresholdSlider,
                        onValueChange = { thresholdSlider = it.toInt().toFloat() },
                        onValueChangeFinished = { viewModel.setAutoRewindThresholdMinutes(thresholdSlider.toInt()) },
                        valueRange = 0f..30f,
                        steps = 29,
                        modifier = Modifier.weight(1f)
                    )
                    HapticIconButton(
                        onClick = {
                            val v = (thresholdSlider + 1).coerceAtMost(30f)
                            thresholdSlider = v
                            viewModel.setAutoRewindThresholdMinutes(v.toInt())
                        },
                        modifier = Modifier.size(32.dp)
                    ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                }

                // Rewind amount slider (0–90 sec)
                if (autoRewindThresholdMinutes > 0) {
                    var rewindSlider by remember(autoRewindSeconds) {
                        mutableFloatStateOf(autoRewindSeconds.toFloat())
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Rewind amount", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${rewindSlider.toInt()} sec",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        HapticIconButton(
                            onClick = {
                                val v = (rewindSlider - 1).coerceAtLeast(0f)
                                rewindSlider = v
                                viewModel.setAutoRewindSeconds(v.toInt())
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                        HapticSlider(
                            value = rewindSlider,
                            onValueChange = { rewindSlider = it.toInt().toFloat() },
                            onValueChangeFinished = { viewModel.setAutoRewindSeconds(rewindSlider.toInt()) },
                            valueRange = 0f..90f,
                            steps = 89,
                            modifier = Modifier.weight(1f)
                        )
                        HapticIconButton(
                            onClick = {
                                val v = (rewindSlider + 1).coerceAtMost(90f)
                                rewindSlider = v
                                viewModel.setAutoRewindSeconds(v.toInt())
                            },
                            modifier = Modifier.size(32.dp)
                        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
    }
    item {
        CardContainer {
            com.betteraudio.ui.player.SkipSilenceControls(
                minMs = skipSilenceMinMs,
                threshold = skipSilenceThreshold,
                paddingMs = skipSilencePaddingMs,
                onSetMinMs = { viewModel.setSkipSilenceMinMs(it) },
                onSetThreshold = { viewModel.setSkipSilenceThreshold(it) },
                onSetPaddingMs = { viewModel.setSkipSilencePaddingMs(it) },
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Sleep timer", style = MaterialTheme.typography.titleSmall)
                com.betteraudio.ui.player.SleepTimerTuningControls(
                    fadeSeconds = sleepFadeSeconds,
                    shakeEnabled = sleepShakeEnabled,
                    shakeResetMinutes = sleepShakeResetMinutes,
                    scheduleEnabled = sleepScheduleEnabled,
                    scheduleStartMinutes = sleepScheduleStartMinutes,
                    scheduleEndMinutes = sleepScheduleEndMinutes,
                    scheduleDefaultMinutes = sleepScheduleDefaultMinutes,
                    onSetFadeSeconds = { viewModel.setSleepFadeSeconds(it) },
                    onSetShakeEnabled = { viewModel.setSleepShakeEnabled(it) },
                    onSetShakeResetMinutes = { viewModel.setSleepShakeResetMinutes(it) },
                    onSetScheduleEnabled = { viewModel.setSleepScheduleEnabled(it) },
                    onSetScheduleStartMinutes = { viewModel.setSleepScheduleStartMinutes(it) },
                    onSetScheduleEndMinutes = { viewModel.setSleepScheduleEndMinutes(it) },
                    onSetScheduleDefaultMinutes = { viewModel.setSleepScheduleDefaultMinutes(it) }
                )
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Headset button mapping", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Double/triple-click the headset button for extra actions. Adds a short delay to every single press to tell them apart — behavior varies by headset.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HapticSwitch(checked = headsetMultiPressEnabled, onCheckedChange = { viewModel.setHeadsetMultiPressEnabled(it) })
                }
                if (headsetMultiPressEnabled) {
                    HeadsetActionPicker(
                        label = "Double press",
                        selected = headsetDoublePressAction,
                        onSelect = { viewModel.setHeadsetDoublePressAction(it) }
                    )
                    HeadsetActionPicker(
                        label = "Triple press",
                        selected = headsetTriplePressAction,
                        onSelect = { viewModel.setHeadsetTriplePressAction(it) }
                    )
                }
            }
        }
    }
    item {
        CardContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Resume on headphones", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Automatically resume a paused book when headphones or a Bluetooth speaker connect, if it was paused recently. Only while the app is still running in the background.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HapticSwitch(checked = btAutoResumeEnabled, onCheckedChange = { viewModel.setBtAutoResumeEnabled(it) })
                }
                if (btAutoResumeEnabled) {
                    var windowSlider by remember(btAutoResumeWindowMinutes) { mutableFloatStateOf(btAutoResumeWindowMinutes.toFloat()) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Within", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${windowSlider.toInt()} min of pausing",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    HapticSlider(
                        value = windowSlider,
                        onValueChange = { windowSlider = it.toInt().toFloat() },
                        onValueChangeFinished = { viewModel.setBtAutoResumeWindowMinutes(windowSlider.toInt().coerceAtLeast(1)) },
                        valueRange = 1f..120f,
                        steps = 118,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
    item {
        CardContainer {
            NavRow(
                icon = Icons.Default.Tune,
                label = "Audio Presets — open via Tune button in player",
                onClick = {}
            )
        }
    }
}

private val HEADSET_ACTIONS = listOf(
    "play_pause" to "Play / Pause",
    "skip_forward" to "Skip forward",
    "skip_back" to "Skip back",
    "next_chapter" to "Next chapter",
    "prev_chapter" to "Previous chapter",
    "bookmark" to "Add bookmark",
    "none" to "Nothing",
)

@Composable
private fun HeadsetActionPicker(label: String, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = HEADSET_ACTIONS.firstOrNull { it.first == selected }?.second ?: "Nothing"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            HapticOutlinedButton(onClick = { expanded = true }) { Text(selectedLabel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                HEADSET_ACTIONS.forEach { (value, actionLabel) ->
                    HapticDropdownMenuItem(
                        text = { Text(actionLabel) },
                        onClick = { onSelect(value); expanded = false }
                    )
                }
            }
        }
    }
}

