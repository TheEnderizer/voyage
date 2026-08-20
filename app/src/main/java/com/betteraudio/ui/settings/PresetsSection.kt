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

// ─── Audio presets (unified bundles + global default) ──────────────────────────

internal fun LazyListScope.presetsSection(
    presets: List<com.betteraudio.data.db.entities.AudioPreset>,
    viewModel: SettingsViewModel
) {
    item {
        var creating by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf<com.betteraudio.data.db.entities.AudioPreset?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Global default", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The starred preset sets the default speed, volume boost & EQ for every book " +
                            "in your library (including new ones). A book you tune individually keeps its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (presets.isEmpty()) {
                Text(
                    "No presets yet. Create one below, or save the current speed/boost/EQ from the " +
                        "player's audio panel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onToggleDefault = {
                            if (preset.isDefault) viewModel.clearDefaultPreset()
                            else viewModel.setDefaultPreset(preset.id)
                        },
                        onEdit = { editing = preset },
                        onDelete = { viewModel.deletePreset(preset.id) }
                    )
                }
            }

            HapticFilledTonalButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New preset")
            }
        }

        if (creating) {
            PresetEditorDialog(
                initial = null,
                onDismiss = { creating = false },
                onSave = { viewModel.savePreset(it); creating = false }
            )
        }
        editing?.let { preset ->
            PresetEditorDialog(
                initial = preset,
                onDismiss = { editing = null },
                onSave = { viewModel.savePreset(it); editing = null }
            )
        }
    }
}

@Composable
private fun PresetRow(
    preset: com.betteraudio.data.db.entities.AudioPreset,
    onToggleDefault: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = settingsCardColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(preset.summary(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HapticIconButton(onClick = onToggleDefault) {
                Icon(
                    if (preset.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                    if (preset.isDefault) "Default preset" else "Set as default",
                    tint = if (preset.isDefault) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HapticIconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit", Modifier.size(20.dp)) }
            HapticIconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, "Delete", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("Delete preset?") },
            text = { Text("\"${preset.name}\" will be removed.") },
            confirmButton = {
                HapticTextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { HapticTextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

private val PRESET_EQ_LABELS = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")

@Composable
private fun PresetEditorDialog(
    initial: com.betteraudio.data.db.entities.AudioPreset?,
    onDismiss: () -> Unit,
    onSave: (com.betteraudio.data.db.entities.AudioPreset) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var speed by remember { mutableFloatStateOf(initial?.speedMult ?: 1.0f) }
    var boost by remember { mutableIntStateOf(initial?.boostDb ?: 0) }
    val eq = remember {
        val arr = IntArray(5) { 0 }
        initial?.eqBandsJson?.let { json ->
            runCatching {
                val a = org.json.JSONArray(json)
                for (i in 0 until minOf(a.length(), 5)) arr[i] = a.getInt(i)
            }
        }
        androidx.compose.runtime.mutableStateListOf(*arr.toTypedArray())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New preset" else "Edit preset") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Speed
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Speed", style = MaterialTheme.typography.labelLarge)
                    Text("${"%.2f".format(speed)}×", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
                HapticSlider(
                    value = speed,
                    onValueChange = { speed = (it / 0.05f).roundToInt() * 0.05f },
                    valueRange = 0.5f..3.0f, steps = 49
                )
                // Boost
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Volume boost", style = MaterialTheme.typography.labelLarge)
                    Text("+$boost dB", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge)
                }
                HapticSlider(
                    value = boost.toFloat(),
                    onValueChange = { boost = it.roundToInt() },
                    valueRange = 0f..24f, steps = 23
                )
                // EQ
                Text("Equalizer", style = MaterialTheme.typography.labelLarge)
                PRESET_EQ_LABELS.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        HapticSlider(
                            value = eq[i].toFloat(),
                            onValueChange = { eq[i] = it.roundToInt() },
                            valueRange = -1500f..1500f,
                            modifier = Modifier.weight(1f)
                        )
                        Text("${if (eq[i] >= 0) "+" else ""}${"%.1f".format(eq[i] / 100f)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
                HapticTextButton(onClick = { for (i in 0 until 5) eq[i] = 0 }) { Text("Flat EQ") }
            }
        },
        confirmButton = {
            HapticTextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val hasEq = eq.any { it != 0 }
                    val json = if (hasEq) org.json.JSONArray(eq.toList()).toString() else null
                    onSave(
                        (initial ?: com.betteraudio.data.db.entities.AudioPreset(name = "")).copy(
                            name = name.trim(),
                            type = com.betteraudio.data.db.entities.AudioPreset.TYPE_BUNDLE,
                            speedMult = speed,
                            boostDb = boost,
                            eqBandsJson = json
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

