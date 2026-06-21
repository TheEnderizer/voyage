package com.betteraudio.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortFilterSheet(
    current: SortFilter,
    onApply: (SortFilter) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sort & Filter", style = MaterialTheme.typography.titleLarge)

            // ── Sort by ─────────────────────────────────────────────
            Text("Sort by", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SortOption.entries.forEach { option ->
                    val selected = current.option == option
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = {
                                onApply(current.copy(option = option))
                            }
                        )
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Direction ───────────────────────────────────────────
            Text("Order", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = current.direction == SortDirection.ASC,
                    onClick = { onApply(current.copy(direction = SortDirection.ASC)) },
                    label = { Text("Ascending") },
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, null, Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = current.direction == SortDirection.DESC,
                    onClick = { onApply(current.copy(direction = SortDirection.DESC)) },
                    label = { Text("Descending") },
                    leadingIcon = { Icon(Icons.Default.ArrowDownward, null, Modifier.size(16.dp)) }
                )
            }

            // ── Reset ────────────────────────────────────────────────
            TextButton(onClick = { onApply(SortFilter()); onDismiss() }) {
                Text("Reset to default")
            }
        }
    }
}
