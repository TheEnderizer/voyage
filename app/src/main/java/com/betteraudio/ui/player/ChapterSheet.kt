package com.betteraudio.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.components.FrostedOverlay

/**
 * Chapter list, rendered as an in-player frosted overlay (not a system bottom sheet) so it
 * floats over the blurred player with high-contrast text. For joined groups the rows are
 * interleaved book-title headers followed by that book's chapters, in playback sequence.
 * Selecting a chapter seeks to its absolute position in the currently-loaded timeline.
 */
@Composable
fun ChapterOverlay(
    visible: Boolean,
    rows: List<ChapterRow>,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val onScrim = Color.White
    val onScrimMuted = Color.White.copy(alpha = 0.6f)
    val accent = MaterialTheme.colorScheme.primary

    // Index of the active chapter = last Item whose start <= current position
    val activeIndex = rows.indexOfLast { it is ChapterRow.Item && it.absStartMs <= currentPositionMs + 250 }
        .let { if (it < 0) rows.indexOfFirst { r -> r is ChapterRow.Item } else it }

    val listState = rememberLazyListState()
    LaunchedEffect(visible, activeIndex) {
        if (visible && activeIndex > 1) listState.scrollToItem((activeIndex - 1).coerceAtLeast(0))
    }

    FrostedOverlay(visible = visible, onDismiss = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.42f),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 18.dp)) {
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(22.dp), tint = accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Chapters",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onScrim
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close", tint = onScrimMuted) }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(rows) { index, row ->
                        when (row) {
                            is ChapterRow.BookHeader -> {
                                Text(
                                    row.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = accent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp)
                                        .padding(top = 16.dp, bottom = 4.dp)
                                )
                            }
                            is ChapterRow.Item -> {
                                val isActive = index == activeIndex
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(
                                            if (isActive) accent.copy(alpha = 0.22f) else Color.Transparent
                                        )
                                        .clickable { onSeek(row.absStartMs); onDismiss() }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.GraphicEq, null,
                                            Modifier.size(18.dp),
                                            tint = accent
                                        )
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Text(
                                        text = row.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isActive) onScrim else onScrim.copy(alpha = 0.85f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = formatMs(row.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = onScrimMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
