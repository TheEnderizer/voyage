package com.betteraudio.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.theme.Pill
import java.util.concurrent.TimeUnit

/** One book in a joined group — shown in the parts list below the synopsis. */
data class PartItem(val title: String, val durationMs: Long)

/**
 * Info panel displayed on the player backdrop when opened from the book grid or group grid.
 * Identical appearance for single books and joined groups (pass [parts] for groups).
 *
 * Colors are hardcoded white/muted-white to remain legible over any cover art. The accent
 * comes from the ambient MaterialTheme (cover-driven via VoyageTheme).
 */
@Composable
fun BookInfoPanel(
    title: String,
    author: String?,
    narrator: String?,
    seriesLabel: String?,
    status: BookStatus?,
    progressFraction: Float,
    totalMs: Long,
    synopsis: String?,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
    parts: List<PartItem>? = null
) {
    val accent      = MaterialTheme.colorScheme.primary
    val onScrim     = Color.White
    val onScrimMuted = Color.White.copy(alpha = 0.62f)

    Column(modifier.fillMaxWidth()) {
        seriesLabel?.takeIf { it.isNotBlank() }?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = onScrim,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        author?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.titleSmall, color = onScrimMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        narrator?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text("Narrated by $it", style = MaterialTheme.typography.bodySmall, color = onScrimMuted)
        }

        Spacer(Modifier.height(12.dp))

        val statusLabel = when (status) {
            BookStatus.NOT_STARTED -> "Not started"
            BookStatus.IN_PROGRESS -> "In progress"
            BookStatus.FINISHED    -> "Finished"
            null                   -> ""
        }
        if (statusLabel.isNotEmpty()) SuggestionChip(onClick = {}, label = { Text(statusLabel) })

        if (totalMs > 0) {
            Spacer(Modifier.height(14.dp))
            val remainingMs = ((1f - progressFraction) * totalMs).toLong().coerceAtLeast(0L)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${(progressFraction * 100).toInt()}% complete",
                    style = MaterialTheme.typography.labelMedium, color = onScrimMuted
                )
                Text(
                    "${formatDurationHuman(remainingMs)} remaining",
                    style = MaterialTheme.typography.labelMedium, color = onScrimMuted
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(Pill),
                color = accent,
                trackColor = Color.White.copy(alpha = 0.22f)
            )
        }

        synopsis?.takeIf { it.isNotBlank() }?.let { about ->
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(about, style = MaterialTheme.typography.bodyMedium, color = onScrimMuted)
            }
        }

        if (!parts.isNullOrEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Parts", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = onScrim)
            Spacer(Modifier.height(8.dp))
            parts.forEachIndexed { index, part ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = accent,
                        modifier = Modifier.width(26.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(part.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, color = onScrim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (part.durationMs > 0) {
                            Text(
                                formatDurationHuman(part.durationMs),
                                style = MaterialTheme.typography.bodySmall, color = onScrimMuted
                            )
                        }
                    }
                }
                if (index < parts.lastIndex) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        val resumeLabel = when {
            progressFraction <= 0f -> "Start"
            else                   -> "Resume"
        }
        Button(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = Pill
        ) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(resumeLabel, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(18.dp))
    }
}

private fun formatDurationHuman(ms: Long): String {
    val hours   = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0   -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else        -> "<1m"
    }
}
