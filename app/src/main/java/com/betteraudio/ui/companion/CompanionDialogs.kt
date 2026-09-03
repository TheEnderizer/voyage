package com.betteraudio.ui.companion

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.companion.RevealCursorRepository
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackScrap
import com.betteraudio.ui.components.appDialogColor
import com.betteraudio.ui.haptics.HapticTextButton

/**
 * The deck's dialogs.
 *
 * Ported unchanged in behaviour from the `CompanionEditorSheet` this replaces — the wording,
 * the field defaults and the pickers were all already right; what was wrong was that they were
 * reached through a modal sheet stacked on another modal sheet. They are dialogs rather than panes
 * because each one is a *commit*: a short, cancellable question with an answer, which is exactly
 * what a dialog is for and exactly what a pane is not.
 *
 * They resolve their fill through [appDialogColor], so they belong to whichever theme is active
 * without this file knowing which one that is.
 */

@Composable
fun CreatePackDialog(defaultTitle: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(defaultTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text("Create companion pack") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true
            )
        },
        confirmButton = { Button(onClick = { onConfirm(title.ifBlank { defaultTitle }) }) { Text("Create") } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One text field and a confirm — naming a map, renaming one. */
@Composable
fun NameDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = { Button(onClick = { onConfirm(value.trim()) }) { Text(confirm) } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddEntityDialog(onConfirm: (EntityKind, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(EntityKind.CHARACTER) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text("Add to cast") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                EntityKindPicker(selected = kind, onSelect = { kind = it })
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onConfirm(kind, name.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun AddFactDialog(
    entityName: String,
    onConfirm: (String, String, Importance) -> Unit,
    onDismiss: () -> Unit
) {
    var field by remember { mutableStateOf("note") }
    var value by remember { mutableStateOf("") }
    var importance by remember { mutableStateOf(Importance.NOTABLE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text("Add fact — $entityName") },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("Field (e.g. allegiance)") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Value") })
                Spacer(Modifier.height(8.dp))
                Text(
                    "Anchored at the current playback position.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                ImportancePicker(selected = importance, onSelect = { importance = it })
            }
        },
        confirmButton = {
            Button(
                onClick = { if (field.isNotBlank() && value.isNotBlank()) onConfirm(field.trim(), value.trim(), importance) },
                enabled = field.isNotBlank() && value.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** §9.1's "capture now, structure later" — two seconds, no typing required beyond the note. */
@Composable
fun QuickCaptureDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text("Capture a note") },
        text = {
            Column {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note") },
                    maxLines = 3
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Anchored where you are now. Turn it into a fact later.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(note.trim()) }, enabled = note.isNotBlank()) { Text("Capture") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConvertScrapDialog(
    scrap: PackScrap,
    existingEntities: List<PackEntity>,
    onConfirm: (entityId: String?, newName: String?, kind: EntityKind, field: String, importance: Importance) -> Unit,
    onDismiss: () -> Unit
) {
    var useExisting by remember { mutableStateOf(existingEntities.isNotEmpty()) }
    var selectedExisting by remember { mutableStateOf(existingEntities.firstOrNull()) }
    var newName by remember { mutableStateOf(scrap.note.take(30)) }
    var kind by remember { mutableStateOf(EntityKind.CHARACTER) }
    var field by remember { mutableStateOf("note") }
    var importance by remember { mutableStateOf(Importance.NOTABLE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text("Turn note into a fact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("\"${scrap.note}\"", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                if (existingEntities.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = useExisting, onClick = { useExisting = true }, label = { Text("Existing character") })
                        FilterChip(selected = !useExisting, onClick = { useExisting = false }, label = { Text("New character") })
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (useExisting && existingEntities.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        existingEntities.forEach { e ->
                            FilterChip(
                                selected = selectedExisting?.entityId == e.entityId,
                                onClick = { selectedExisting = e },
                                label = { Text(e.name) }
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New character name") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    EntityKindPicker(selected = kind, onSelect = { kind = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("Field") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                ImportancePicker(selected = importance, onSelect = { importance = it })
            }
        },
        confirmButton = {
            Button(onClick = {
                if (useExisting && selectedExisting != null) {
                    onConfirm(selectedExisting!!.entityId, null, kind, field.ifBlank { "note" }, importance)
                } else {
                    onConfirm(null, newName.ifBlank { scrap.note.take(30) }, kind, field.ifBlank { "note" }, importance)
                }
            }) { Text("Convert") }
        },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ShareChoiceDialog(onShareDataOnly: () -> Unit, onShareFull: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text("Share companion pack") },
        text = {
            Text(
                "Pack only sends just the map/cast data — the recipient needs the book already. " +
                    "Include audio bundles the book itself too, for a friend who doesn't have it yet.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = { Button(onClick = onShareDataOnly) { Text("Pack only") } },
        dismissButton = { OutlinedButton(onClick = onShareFull) { Text("Include audio") } }
    )
}

/**
 * §6.3's confirmed reveal-seek — the one movement of the cursor that is not automatic.
 *
 * It asks for the count *before* committing rather than offering a bare confirm, because the whole
 * point of the reveal cursor is that the listener controls what they are allowed to know, and "this
 * will show you 41 more facts" is the only form of that question worth asking. Undo comes from the
 * repository's in-memory buffer, which is deliberately never persisted.
 */
@Composable
fun CatchUpDialog(
    targetMs: Long,
    preview: suspend (Long) -> RevealCursorRepository.SeekConfirmPreview,
    onConfirm: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit
) {
    var result by remember { mutableStateOf<RevealCursorRepository.SeekConfirmPreview?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    LaunchedEffect(targetMs) { result = preview(targetMs) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = appDialogColor(),
        title = { Text(if (confirmed) "Caught up" else "Catch the companion up?") },
        text = {
            val p = result
            Text(
                when {
                    confirmed -> "The companion now shows everything up to ${formatDeckHm(targetMs)}."
                    p == null -> "Counting…"
                    p.revealCount == 0 -> "Nothing new to reveal at ${formatDeckHm(targetMs)}."
                    else -> "Reveals ${p.revealCount} fact${if (p.revealCount == 1) "" else "s"} " +
                        "up to ${formatDeckHm(targetMs)} — everything this pack knows about the part " +
                        "you have already listened to. Nothing beyond that point is shown."
                }
            )
        },
        confirmButton = {
            if (confirmed) {
                HapticTextButton(onClick = onDismiss) { Text("Done") }
            } else {
                HapticTextButton(
                    enabled = result != null,
                    onClick = { onConfirm(); confirmed = true }
                ) { Text("Catch up") }
            }
        },
        dismissButton = {
            if (confirmed) {
                HapticTextButton(onClick = { onUndo(); onDismiss() }) { Text("Undo") }
            } else {
                HapticTextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun EntityKindPicker(selected: EntityKind, onSelect: (EntityKind) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        EntityKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                label = { Text(kind.name.lowercase().replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

@Composable
private fun ImportancePicker(selected: Importance, onSelect: (Importance) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Importance.entries.forEach { level ->
            FilterChip(
                selected = level == selected,
                onClick = { onSelect(level) },
                label = { Text(level.name.lowercase().replaceFirstChar { it.uppercase() }) }
            )
        }
    }
}

/** Shares an exported pack file. Same recipe as `ui/settings/BackupSection.kt`'s `shareBackupFile`. */
fun shareCompanionPack(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        putExtra(android.content.Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        android.content.Intent.createChooser(send, "Share companion pack")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
