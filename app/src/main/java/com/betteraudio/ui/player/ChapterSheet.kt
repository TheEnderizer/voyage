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
import com.betteraudio.playback.ChapterMark
import com.betteraudio.ui.components.FrostedOverlay
import com.betteraudio.ui.material.MaterialAdaptive

/**
 * Row index in [rows] matching [cur] — the same active chapter the pill/scrubber show, computed
 * by [com.betteraudio.playback.ChapterTimeline] — for [effectiveBookId] (the screen's own book,
 * NOT necessarily the playing book; see [PlayerViewModel.chapterTimeline]'s doc). Matches on
 * (bookId, absStartMs) rather than any per-mark id: [ChapterMark.key] is `Chapter.id` for embedded
 * chapters and `AudioFile.id` for per-file ones, and those id spaces can collide inside one
 * series' interleaved chapter list, so an id match alone isn't safe here.
 */
fun activeChapterRowIndex(rows: List<ChapterRow>, effectiveBookId: Long, cur: ChapterMark?): Int {
    if (cur == null) return -1
    return rows.indexOfFirst {
        it is ChapterRow.Item && it.bookId == effectiveBookId && it.absStartMs == cur.startMs
    }
}

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
    activeRowIndex: Int,
    onSelect: (ChapterRow.Item) -> Unit,
    onDismiss: () -> Unit
) {
    val onScrim = Color.White
    val onScrimMuted = Color.White.copy(alpha = 0.6f)
    val accent = MaterialTheme.colorScheme.primary

    val activeIndex = if (activeRowIndex >= 0) activeRowIndex
        else rows.indexOfFirst { it is ChapterRow.Item }

    val listState = rememberLazyListState()
    LaunchedEffect(visible, activeIndex) {
        if (visible && activeIndex > 1) listState.scrollToItem((activeIndex - 1).coerceAtLeast(0))
    }

    FrostedOverlay(visible = visible, onDismiss = onDismiss) {
        // Shared/unsplit file resolving one thing per theme, same pattern as MiniPlayerBar's
        // isImmersive branch (PlayerSheet.kt) and appSheetColor() (SheetStyle.kt).
        // isMaterialLandscape() folds the theme check in, so Immersive is false in BOTH
        // orientations by construction. At ~890x338dp a one-column list is mostly whitespace.
        val wide = MaterialAdaptive.isMaterialLandscape()
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.42f),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (wide) Modifier.widthIn(max = 640.dp) else Modifier)
                .fillMaxHeight(if (wide) 0.94f else 0.82f)
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
                                        .clickable { onSelect(row); onDismiss() }
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
