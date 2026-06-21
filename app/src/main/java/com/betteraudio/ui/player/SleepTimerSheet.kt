package com.betteraudio.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    remainingMs: Long,
    onSetTimer: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val presets = listOf(
        "5 min"  to 5 * 60_000L,
        "15 min" to 15 * 60_000L,
        "30 min" to 30 * 60_000L,
        "45 min" to 45 * 60_000L,
        "60 min" to 60 * 60_000L,
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
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
                                "Pausing in ${formatTimerMs(remainingMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        OutlinedButton(onClick = { onSetTimer(0L); onDismiss() }) {
                            Text("Cancel")
                        }
                    }
                }
            }

            Text("Set timer", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.take(3).forEach { (label, ms) ->
                    FilterChip(
                        selected = false,
                        onClick = { onSetTimer(ms); onDismiss() },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.drop(3).forEach { (label, ms) ->
                    FilterChip(
                        selected = false,
                        onClick = { onSetTimer(ms); onDismiss() },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private fun formatTimerMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
