package com.betteraudio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.data.scanner.ImportStructure

/** User-facing copy for each import structure. */
private data class StructureOption(
    val structure: ImportStructure,
    val title: String,
    val detail: String
)

private val STRUCTURE_OPTIONS = listOf(
    StructureOption(
        ImportStructure.AUTO,
        "Automatic",
        "Detect books from tags and folder names. Best for mixed libraries."
    ),
    StructureOption(
        ImportStructure.AUTHOR_SERIES_BOOK,
        "Author / Series / Book",
        "Folders: Author › Series › Book › files. The series level is optional."
    ),
    StructureOption(
        ImportStructure.AUTHOR_DASH_SERIES_BOOK,
        "Author - Series / Book",
        "Folders: “Author - Series” › Book › files. No dash ⇒ folder is the series, " +
            "author comes from the file’s tags."
    )
)

/**
 * Dialog for choosing how the library folders are laid out. Used both on first launch and
 * from the Library settings section. [initial] pre-selects the current choice.
 */
@Composable
fun ImportStructureDialog(
    initial: ImportStructure,
    confirmLabel: String = "Save",
    onConfirm: (ImportStructure) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Library structure") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "How are your audiobook folders organised?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                STRUCTURE_OPTIONS.forEach { opt ->
                    StructureRow(
                        opt = opt,
                        selected = selected == opt.structure,
                        onSelect = { selected = opt.structure }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun StructureRow(opt: StructureOption, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.padding(start = 4.dp)) {
                Text(opt.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    opt.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Short label for the current structure, for the settings row subtitle. */
fun ImportStructure.label(): String = when (this) {
    ImportStructure.AUTO -> "Automatic"
    ImportStructure.AUTHOR_SERIES_BOOK -> "Author / Series / Book"
    ImportStructure.AUTHOR_DASH_SERIES_BOOK -> "Author - Series / Book"
}
