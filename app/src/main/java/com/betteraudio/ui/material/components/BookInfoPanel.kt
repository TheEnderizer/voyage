package com.betteraudio.ui.material.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.components.BookSegmentTrack
import com.betteraudio.ui.components.PartItem
import com.betteraudio.ui.player.InfoSlot
import com.betteraudio.ui.player.ResumeHandoff
import com.betteraudio.ui.player.leavesOnResume
import com.betteraudio.ui.player.publishSlot
import com.betteraudio.ui.player.travellingSlot
import com.betteraudio.ui.theme.Pill
import java.util.concurrent.TimeUnit
import com.betteraudio.ui.haptics.*

/**
 * Info panel displayed on the player backdrop when opened from the book grid.
 *
 * Colors come from the ambient MaterialTheme (cover-driven via VoyageTheme).
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
    parts: List<PartItem>? = null,
    onShowHistory: (() -> Unit)? = null,
    chapterStarts: List<Long> = emptyList(),
    handoff: ResumeHandoff = com.betteraudio.ui.player.NoResumeHandoff
) {
    val accent      = MaterialTheme.colorScheme.primary
    val onScrim     = MaterialTheme.colorScheme.onSurface
    val onScrimMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        seriesLabel?.takeIf { it.isNotBlank() }?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                modifier = Modifier.padding(bottom = 4.dp).leavesOnResume(handoff.progress)
            )
        }

        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = onScrim,
            fontWeight = FontWeight.Bold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().travellingSlot(handoff, InfoSlot.TITLE)
        )

        author?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.titleSmall, color = onScrimMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().travellingSlot(handoff, InfoSlot.AUTHOR))
        }

        narrator?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text("Narrated by $it", style = MaterialTheme.typography.bodySmall, color = onScrimMuted,
                modifier = Modifier.leavesOnResume(handoff.progress))
        }

        Spacer(Modifier.height(12.dp))

        val statusLabel = when (status) {
            BookStatus.NOT_STARTED -> "Not started"
            BookStatus.IN_PROGRESS -> "In progress"
            BookStatus.FINISHED    -> "Finished"
            null                   -> ""
        }
        if (statusLabel.isNotEmpty()) SuggestionChip(
            onClick = {},
            label = { Text(statusLabel) },
            modifier = Modifier.leavesOnResume(handoff.progress)
        )

        if (totalMs > 0) {
            Spacer(Modifier.height(14.dp))
            val remainingMs = ((1f - progressFraction) * totalMs).toLong().coerceAtLeast(0L)
            Row(
                Modifier.fillMaxWidth().leavesOnResume(handoff.progress),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
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
            // The player's own whole-book track, not a second design of the same idea — which is
            // also what lets it travel down into the player when Resume is pressed.
            BookSegmentTrack(
                positionMs = (progressFraction * totalMs).toLong(),
                totalMs = totalMs,
                chapterStarts = chapterStarts,
                accent = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().travellingSlot(handoff, InfoSlot.TRACK)
            )
        }

        synopsis?.takeIf { it.isNotBlank() }?.let { about ->
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .leavesOnResume(handoff.progress)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(about, style = MaterialTheme.typography.bodyMedium, color = onScrimMuted)
            }
        }

        if (!parts.isNullOrEmpty()) {
          Column(Modifier.leavesOnResume(handoff.progress)) {
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
          }
        }

        Spacer(Modifier.height(18.dp))

        val resumeLabel = when {
            progressFraction <= 0f -> "Start"
            else                   -> "Resume"
        }
        // The button the player's play control grows out of: it publishes its own rect, its glyph's
        // and its label's, then stands aside once the player is carrying all three.
        HapticButton(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth().height(56.dp).travellingSlot(handoff, InfoSlot.BUTTON),
            shape = Pill
        ) {
            Icon(
                Icons.Default.PlayArrow, null,
                Modifier.size(22.dp).publishSlot(handoff, InfoSlot.BUTTON_ICON)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                resumeLabel,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.publishSlot(handoff, InfoSlot.BUTTON_LABEL)
            )
        }

        if (onShowHistory != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier
                    .leavesOnResume(handoff.progress)
                    .clip(Pill)
                    .clickable(onClick = onShowHistory)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.History, null, Modifier.size(18.dp), tint = onScrimMuted)
                Spacer(Modifier.width(6.dp))
                Text("Listening history", style = MaterialTheme.typography.labelLarge, color = onScrimMuted)
            }
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
