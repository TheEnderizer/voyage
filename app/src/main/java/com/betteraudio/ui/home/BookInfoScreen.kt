package com.betteraudio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.components.ReflectedProgressiveBlurCover
import com.betteraudio.ui.theme.Pill
import java.util.concurrent.TimeUnit

/**
 * Same full-bleed frame as [com.betteraudio.ui.player.PlayerScreen] — black
 * background, the identical [ReflectedProgressiveBlurCover] behind a matching
 * progressive scrim, a top bar, and a bottom-anchored content block. The only
 * difference is the bottom block: book info (status / progress / synopsis /
 * resume) instead of playback transport controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: BookInfoViewModel = hiltViewModel()
) {
    val bwp by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val book = bwp?.book

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (book == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White
            )
            return@Box
        }

        val onScrim      = Color.White
        val onScrimMuted = Color.White.copy(alpha = 0.62f)
        val accent       = MaterialTheme.colorScheme.primary

        // ── Cover + reflection background (identical to PlayerScreen) ──────────
        Box(Modifier.fillMaxSize().clipToBounds()) {
            ReflectedProgressiveBlurCover(
                coverPath = book.coverArtPath,
                modifier  = Modifier.fillMaxWidth()
            )
        }
        // Progressive scrim: clear over the cover, dark over the info block.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f    to Color.Black.copy(alpha = 0.15f),
                    0.38f to Color.Black.copy(alpha = 0.04f),
                    0.54f to Color.Black.copy(alpha = 0.52f),
                    0.75f to Color.Black.copy(alpha = 0.86f),
                    1f    to Color.Black.copy(alpha = 0.97f)
                )
            )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ── Top bar ────────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                book.seriesName?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = onScrimMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                // Spacer to balance the back button so the series label stays centred.
                Spacer(Modifier.size(42.dp))
            }

            Spacer(Modifier.weight(1f))

            // ── Bottom info block (replaces the player's controls) ──────────────
            book.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                val label = if (book.seriesOrder != null) "$series · #${book.seriesOrder}" else series
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Text(
                text = book.displayTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = onScrim,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )

            if (book.displayAuthor.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = book.displayAuthor,
                    style = MaterialTheme.typography.titleSmall,
                    color = onScrimMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!book.narrator.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Narrated by ${book.narrator}",
                    style = MaterialTheme.typography.bodySmall,
                    color = onScrimMuted
                )
            }

            Spacer(Modifier.height(12.dp))

            // Status chip
            val statusLabel = when (book.status) {
                BookStatus.NOT_STARTED -> "Not started"
                BookStatus.IN_PROGRESS -> "In progress"
                BookStatus.FINISHED    -> "Finished"
            }
            SuggestionChip(onClick = {}, label = { Text(statusLabel) })

            // Progress
            val prog    = bwp?.progressFraction ?: 0f
            val totalMs = book.totalDurationMs
            if (totalMs > 0) {
                Spacer(Modifier.height(14.dp))
                val remainingMs = ((1f - prog) * totalMs).toLong().coerceAtLeast(0L)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${(prog * 100).toInt()}% complete",
                        style = MaterialTheme.typography.labelMedium, color = onScrimMuted)
                    Text("${formatDurationHuman(remainingMs)} remaining",
                        style = MaterialTheme.typography.labelMedium, color = onScrimMuted)
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { prog },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(Pill),
                    color = accent,
                    trackColor = Color.White.copy(alpha = 0.22f)
                )
            }

            // Synopsis / description — capped scroll so a long blurb never
            // pushes the resume button off-screen.
            val about = book.synopsis?.takeIf { it.isNotBlank() }
                ?: book.description?.takeIf { it.isNotBlank() }
            if (about != null) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        about,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onScrimMuted
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // Resume / Start button (replaces the transport row)
            val buttonLabel = when {
                prog <= 0f                          -> "Start"
                bwp?.progress?.isCompleted == true  -> "Play again"
                else                                -> "Resume"
            }
            Button(
                onClick  = { onOpenBook(book.id) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape    = Pill
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(18.dp))
        }
    }
}

/** Matches PlayerScreen's translucent round scrim button. */
@Composable
private fun ScrimButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(42.dp).clip(Pill).background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, Modifier.size(22.dp), tint = Color.White)
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
