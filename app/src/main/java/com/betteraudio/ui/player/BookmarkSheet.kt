package com.betteraudio.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.Bookmark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    bookmarks: List<BookmarkUi>,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onJump: (Bookmark) -> Unit,
    onDelete: (Long) -> Unit,
    onAddHere: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = com.betteraudio.ui.components.appSheetColor(), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            // Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
                Button(
                    onClick = { onAddHere(); onDismiss() },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Bookmark, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add here")
                }
            }

            HorizontalDivider()

            if (bookmarks.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.Bookmark,
                            null,
                            Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            "No bookmarks yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Tap \"Add here\" to save your current position",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(bookmarks, key = { it.bookmark.id }) { item ->
                        BookmarkRow(
                            item = item,
                            totalDurationMs = totalDurationMs,
                            onJump = { onJump(item.bookmark); onDismiss() },
                            onDelete = { onDelete(item.bookmark.id) }
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BookmarkRow(
    item: BookmarkUi,
    totalDurationMs: Long,
    onJump: () -> Unit,
    onDelete: () -> Unit
) {
    val bookmark = item.bookmark
    Surface(
        shape = MaterialTheme.shapes.large,
        // Slightly translucent so the row blends with the (frosted, in Immersive) sheet fill
        // instead of reading as an opaque Material card on top of it.
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Chapter-relative label, not a book offset: the bookmark is anchored to its own
                // file, so this keeps naming the same spot even after other files are deleted.
                Text(
                    if (item.chapterName.isNotBlank())
                        "${item.chapterName} - ${formatBookmarkTime(item.positionInChapterMs)}"
                    else
                        formatBookmarkTime(item.absPositionMs),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.fileMissing) {
                    Text(
                        "File missing — approximate position",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (totalDurationMs > 0) {
                    val pct = (item.absPositionMs * 100f / totalDurationMs).toInt()
                    Text(
                        "${formatBookmarkTime(item.absPositionMs)} · $pct% through book",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (bookmark.comment.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        bookmark.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onJump) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Jump to bookmark",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    "Delete bookmark",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}

private fun formatBookmarkTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
