package com.betteraudio.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.ebook.SpineItem
import com.betteraudio.sync.AudioChapterSpan
import com.betteraudio.sync.ChapterMap
import com.betteraudio.ui.haptics.*

/**
 * Manual fallback for aligning audio chapters to epub spine items when auto-match gets it wrong
 * (unusual TOC, heavy front/back matter, etc). One dropdown per audio chapter; "Auto-match" resets
 * to the algorithmic guess; Save persists a monotonic (non-decreasing) mapping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterAlignSheet(
    audioSpans: List<AudioChapterSpan>,
    spine: List<SpineItem>,
    onAutoMatch: () -> Unit,
    onSave: (ChapterMap) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Align chapters", style = MaterialTheme.typography.titleMedium)
                HapticTextButton(onClick = onAutoMatch) { Text("Auto-match") }
            }

            if (audioSpans.isEmpty() || spine.isEmpty()) {
                Text(
                    "Nothing to align yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
                return@Column
            }

            // Local editable copy — clamped monotonic non-decreasing on every edit so a manual
            // pick can never point earlier in the spine than a previous audio chapter's pick.
            val assignments = remember(audioSpans, spine) {
                mutableStateListOf(*List(audioSpans.size) { -1 }.toTypedArray())
            }

            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                items(audioSpans, key = { it.index }) { span ->
                    var expanded by remember { mutableStateOf(false) }
                    val selectedSpineIdx = assignments.getOrElse(span.index) { -1 }
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            span.title, style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                            OutlinedTextField(
                                value = spine.getOrNull(selectedSpineIdx)?.let { it.title ?: "Section ${it.index + 1}" }
                                    ?: "Unmapped (interpolated)",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor()
                            )
                            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                HapticDropdownMenuItem(
                                    text = { Text("Unmapped (interpolated)") },
                                    onClick = { assignments[span.index] = -1; expanded = false }
                                )
                                spine.forEach { item ->
                                    HapticDropdownMenuItem(
                                        text = { Text(item.title ?: "Section ${item.index + 1}") },
                                        onClick = { assignments[span.index] = item.index; expanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                HapticTextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                HapticButton(onClick = {
                    // Enforce monotonic non-decreasing across mapped entries — any pick that goes
                    // "backwards" relative to an earlier chapter's pick is dropped to unmapped.
                    var lastMapped = -1
                    val clamped = assignments.map { idx ->
                        if (idx >= 0 && idx >= lastMapped) { lastMapped = idx; idx } else -1
                    }
                    onSave(ChapterMap(clamped))
                    onDismiss()
                }) { Text("Save") }
            }
        }
    }
}
