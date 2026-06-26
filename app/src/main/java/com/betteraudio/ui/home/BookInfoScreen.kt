package com.betteraudio.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.theme.Pill
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoScreen(
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: BookInfoViewModel = hiltViewModel()
) {
    val bwp by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val book = bwp?.book

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        if (book == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
        ) {
            // ── Cover ─────────────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                AsyncImage(
                    model = book.coverArtPath?.let { File(it) },
                    contentDescription = book.displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0.6f to Color.Transparent,
                            1f to MaterialTheme.colorScheme.background
                        )
                    )
                )
            }

            // ── Metadata ───────────────────────────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                // Series
                book.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                    val label = if (book.seriesOrder != null) "$series · #${book.seriesOrder}" else series
                    Text(
                        label.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                Text(
                    book.displayTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )

                if (book.displayAuthor.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        book.displayAuthor,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!book.narrator.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Narrated by ${book.narrator}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Status chip
                val statusLabel = when (book.status) {
                    BookStatus.NOT_STARTED -> "Not started"
                    BookStatus.IN_PROGRESS -> "In progress"
                    BookStatus.FINISHED    -> "Finished"
                }
                SuggestionChip(
                    onClick = {},
                    label = { Text(statusLabel) }
                )

                // Progress
                val prog = bwp?.progressFraction ?: 0f
                val totalMs = book.totalDurationMs
                if (totalMs > 0) {
                    Spacer(Modifier.height(16.dp))
                    val remainingMs = ((1f - prog) * totalMs).toLong().coerceAtLeast(0L)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${(prog * 100).toInt()}% complete",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${formatDurationHuman(remainingMs)} remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { prog },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(Pill),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Resume / Start button
                val buttonLabel = when {
                    prog <= 0f -> "Start"
                    bwp?.progress?.isCompleted == true -> "Play again"
                    else -> "Resume"
                }
                Button(
                    onClick = { onOpenBook(book.id) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = Pill
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
                }

                // Synopsis
                book.synopsis?.takeIf { it.isNotBlank() }?.let { synopsis ->
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "About",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Description from tags (if different from synopsis)
                if (book.description?.isNotBlank() == true && book.synopsis?.isNotBlank() != true) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Description",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        book.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun formatDurationHuman(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0  -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else       -> "<1m"
    }
}
