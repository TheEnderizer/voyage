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
import com.betteraudio.ui.theme.AppTheme

/** User-facing copy for each app theme. */
data class ThemeOption(val theme: AppTheme, val title: String, val detail: String)

val THEME_OPTIONS = listOf(
    ThemeOption(
        AppTheme.MATERIAL_YOU,
        "Material You",
        "Clean tonal surfaces in the Material You style, coloured from your wallpaper or the playing book."
    ),
    ThemeOption(
        AppTheme.IMMERSIVE,
        "Immersive",
        "Your book cover fills the whole app behind a deep blur, and every colour follows it."
    )
)

/**
 * Dialog for choosing the app theme. Shown once on first launch (APP_THEME still "") and
 * reusable from Settings → Theme. Dismissing commits [initial] so the prompt never reappears.
 */
@Composable
fun ThemePickerDialog(
    initial: AppTheme,
    confirmLabel: String = "Continue",
    onConfirm: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose your look") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pick how Voyage should look. You can change this anytime in Settings → Theme.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                THEME_OPTIONS.forEach { opt ->
                    ThemeOptionRow(
                        opt = opt,
                        selected = selected == opt.theme,
                        onSelect = { selected = opt.theme }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(confirmLabel) } }
    )
}

@Composable
fun ThemeOptionRow(opt: ThemeOption, selected: Boolean, onSelect: () -> Unit) {
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
