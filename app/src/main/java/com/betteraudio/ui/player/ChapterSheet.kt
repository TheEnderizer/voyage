package com.betteraudio.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.playback.ChapterMark
import com.betteraudio.ui.components.FrostedOverlay
import com.betteraudio.ui.material.MaterialAdaptive
import kotlinx.coroutines.launch
import com.betteraudio.ui.haptics.*

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
 * What the list actually draws. [ChapterRow] is the ViewModel's flat interleaving of headers and
 * chapters; this adds the three things a reader needs that the flat shape can't carry — a
 * chapter's number within its own book, which header it lives under (so filtering can drop a
 * header whose chapters all filtered away), and its position in the whole timeline (so "already
 * played" is a comparison rather than a list scan per drawn row).
 */
private sealed interface ChapterListRow {
    val group: Int

    data class Header(val title: String, override val group: Int) : ChapterListRow

    data class Entry(
        val item: ChapterRow.Item,
        /** 1-based, restarting per book so a series list numbers each book from one. */
        val number: Int,
        /** Index among entries across the whole list, in playback order. */
        val ordinal: Int,
        override val group: Int
    ) : ChapterListRow
}

private fun buildDisplayRows(rows: List<ChapterRow>): List<ChapterListRow> {
    val out = ArrayList<ChapterListRow>(rows.size)
    var group = 0
    var number = 0
    var ordinal = 0
    rows.forEach { row ->
        when (row) {
            is ChapterRow.BookHeader -> {
                group++
                number = 0
                out += ChapterListRow.Header(row.title, group)
            }
            is ChapterRow.Item -> out += ChapterListRow.Entry(row, ++number, ordinal++, group)
        }
    }
    return out
}

/**
 * [query] matched against chapter titles, and against the chapter number when the query is digits
 * — "12" is how people refer to a chapter out loud, and it is usually nowhere in the title. A
 * header survives only if something under it did, so a result never shows an empty book.
 */
private fun filterDisplayRows(all: List<ChapterListRow>, query: String): List<ChapterListRow> {
    val q = query.trim()
    if (q.isEmpty()) return all
    val byNumber = q.toIntOrNull()
    val hits = all.filterIsInstance<ChapterListRow.Entry>().filterTo(HashSet()) { entry ->
        entry.item.title.contains(q, ignoreCase = true) || (byNumber != null && entry.number == byNumber)
    }
    if (hits.isEmpty()) return emptyList()
    val liveGroups = hits.mapTo(HashSet()) { it.group }
    return all.filter { row ->
        when (row) {
            is ChapterListRow.Header -> row.group in liveGroups
            is ChapterListRow.Entry -> row in hits
        }
    }
}

/**
 * Chapter list, rendered as an in-player frosted overlay (not a system bottom sheet) so it
 * floats over the blurred player with high-contrast text. For joined groups the rows are
 * interleaved book-title headers followed by that book's chapters, in playback sequence.
 * Selecting a chapter seeks to its absolute position in the currently-loaded timeline.
 *
 * A long book is a long list, so navigating it is the design problem here: search filters by title
 * or chapter number, every row leads with the number people actually navigate by, chapters behind
 * the playhead recede so scroll position alone tells you where you are, a drag rail on the right
 * covers the whole list in one gesture, and a pill offers the way back to what's playing the
 * moment it scrolls out of sight.
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

    val display = remember(rows) { buildDisplayRows(rows) }
    val activeItem = remember(rows, activeRowIndex) {
        rows.getOrNull(activeRowIndex) as? ChapterRow.Item
            ?: rows.firstOrNull { it is ChapterRow.Item } as? ChapterRow.Item
    }
    val activeEntry = remember(display, activeItem) {
        display.firstOrNull { it is ChapterListRow.Entry && it.item == activeItem }
            as? ChapterListRow.Entry
    }

    var query by remember { mutableStateOf("") }
    // The query belongs to one visit, not to the player. Reopening onto a stale filter would hide
    // the chapter you are on with no sign of why.
    LaunchedEffect(visible) { if (!visible) query = "" }

    val filtered = remember(display, query) { filterDisplayRows(display, query) }
    val totalChapters = remember(display) { display.count { it is ChapterListRow.Entry } }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val haptics = LocalHaptics.current

    val activeIndexInList = remember(filtered, activeItem) {
        filtered.indexOfFirst { it is ChapterListRow.Entry && it.item == activeItem }
    }
    // Opening lands on the playing chapter with one row of lead-in above it, so it reads as "you
    // are here" rather than "this is the top". Keyed on the visit and the chapter, NOT on the
    // filtered list — otherwise typing in the search box would yank the list back every keystroke.
    LaunchedEffect(visible, activeItem) {
        if (!visible) return@LaunchedEffect
        val target = filtered.indexOfFirst { it is ChapterListRow.Entry && it.item == activeItem }
        if (target > 0) listState.scrollToItem((target - 1).coerceAtLeast(0))
    }

    val activeOffScreen by remember(activeIndexInList) {
        derivedStateOf {
            activeIndexInList >= 0 &&
                listState.layoutInfo.visibleItemsInfo.none { it.index == activeIndexInList }
        }
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
                .fillMaxHeight(if (wide) 0.94f else 0.86f)
        ) {
            Column(Modifier.padding(top = 18.dp, bottom = 10.dp)) {
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(22.dp), tint = accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Chapters",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = onScrim
                        )
                        Text(
                            when {
                                // While filtering, the count that matters is how much is left.
                                query.isNotBlank() -> {
                                    val n = filtered.count { it is ChapterListRow.Entry }
                                    if (n == 1) "1 match" else "$n matches"
                                }
                                activeEntry != null -> "${activeEntry.number} of $totalChapters"
                                else -> "$totalChapters chapters"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = onScrimMuted
                        )
                    }
                    HapticIconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = onScrimMuted)
                    }
                }

                Spacer(Modifier.height(12.dp))
                ChapterSearchField(
                    query = query,
                    onQueryChange = { query = it },
                    accent = accent,
                    onScrim = onScrim,
                    onScrimMuted = onScrimMuted,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(10.dp))

                Box(Modifier.weight(1f)) {
                    if (filtered.isEmpty()) {
                        Text(
                            "Nothing matches “${query.trim()}”.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onScrimMuted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 32.dp, vertical = 40.dp)
                        )
                    }
                    // Picking a chapter is a choice among many, not a button press.
                    PressFeel(Feel.Select) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 12.dp)
                    ) {
                        items(
                            items = filtered,
                            key = { row ->
                                when (row) {
                                    is ChapterListRow.Header -> "h${row.group}"
                                    is ChapterListRow.Entry -> "c${row.item.bookId}:${row.item.absStartMs}"
                                }
                            }
                        ) { row ->
                            when (row) {
                                is ChapterListRow.Header -> ChapterBookHeader(row.title, accent)
                                is ChapterListRow.Entry -> ChapterEntryRow(
                                    entry = row,
                                    isActive = row.item == activeItem,
                                    isPlayed = activeEntry != null && row.ordinal < activeEntry.ordinal,
                                    accent = accent,
                                    onScrim = onScrim,
                                    onScrimMuted = onScrimMuted,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onSelect(row.item)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                    }

                    // One gesture covers the whole list. Below this many rows a normal fling
                    // already reaches everything and a rail would just be furniture.
                    if (filtered.size > 20) {
                        ChapterScrollRail(
                            listState = listState,
                            itemCount = filtered.size,
                            accent = accent,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 6.dp)
                        )
                    }

                    NowPlayingJump(
                        visible = activeOffScreen && query.isBlank(),
                        accent = accent,
                        onClick = {
                            haptics.play(Feel.Select)
                            scope.launch {
                                listState.animateScrollToItem((activeIndexInList - 1).coerceAtLeast(0))
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    accent: Color,
    onScrim: Color,
    onScrimMuted: Color,
    modifier: Modifier = Modifier
) {
    // BasicTextField rather than M3's TextField: this sits on a black frosted panel, and the stock
    // container/indicator colours would paint their own light surface right through it.
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = onScrim),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = onScrimMuted)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search chapters",
                            style = MaterialTheme.typography.bodyLarge,
                            color = onScrimMuted
                        )
                    }
                    inner()
                }
                if (query.isNotEmpty()) {
                    HapticIconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, "Clear search", Modifier.size(16.dp), tint = onScrimMuted)
                    }
                }
            }
        }
    )
}

@Composable
private fun ChapterBookHeader(title: String, accent: Color) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            // Opaque enough to stay readable as list rows pass under it.
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 24.dp)
            .padding(top = 14.dp, bottom = 8.dp)
    )
}

@Composable
private fun ChapterEntryRow(
    entry: ChapterListRow.Entry,
    isActive: Boolean,
    isPlayed: Boolean,
    accent: Color,
    onScrim: Color,
    onScrimMuted: Color,
    onClick: () -> Unit
) {
    val titleColor = when {
        isActive -> onScrim
        // Behind the playhead, so it recedes — this is what lets scroll position alone tell you
        // where you are, without reading a single word.
        isPlayed -> onScrim.copy(alpha = 0.45f)
        else -> onScrim.copy(alpha = 0.87f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) accent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The number is the handle people actually navigate by ("go back to 14"), so it leads the
        // row instead of being implied by scroll position.
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (isActive) accent else Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            if (isActive) {
                Icon(
                    Icons.Default.GraphicEq, null,
                    Modifier.size(16.dp),
                    tint = if (accent.luminance() > 0.5f) Color.Black else Color.White
                )
            } else {
                Text(
                    entry.number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPlayed) onScrimMuted else onScrim.copy(alpha = 0.8f)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.item.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            // Where the chapter starts, which is what you navigate by; how long it runs underneath,
            // which is what you judge by.
            Text(
                text = formatMs(entry.item.absStartMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (isPlayed) onScrimMuted else onScrim.copy(alpha = 0.75f)
            )
            Text(
                text = formatMs(entry.item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = onScrim.copy(alpha = 0.38f)
            )
        }
    }
}

/**
 * A drag rail down the list's full height: one thumb-length gesture reaches any chapter in a
 * 300-file book, which no amount of flinging does comfortably.
 */
@Composable
private fun ChapterScrollRail(
    listState: LazyListState,
    itemCount: Int,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var dragging by remember { mutableStateOf(false) }
    var railHeight by remember { mutableStateOf(0f) }
    // One tick per row the rail passes, so a 300-chapter drag reports distance instead of
    // sliding silently. The engine's own floor keeps a fast flick from turning into a buzz.
    var lastIndex by remember { mutableStateOf(Int.MIN_VALUE) }

    // Rows are near enough uniform that index-proportional placement tracks the real scroll
    // position closely, and it costs nothing — no measuring pass, no per-item height cache.
    val fraction by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val span = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
            (listState.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
        }
    }
    val thumbFraction by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount == 0) 1f
            else (info.visibleItemsInfo.size.toFloat() / info.totalItemsCount).coerceIn(0.06f, 1f)
        }
    }

    Box(
        modifier
            .width(24.dp)
            .onSizeChanged { railHeight = it.height.toFloat() }
            .pointerInput(itemCount) {
                fun seek(y: Float) {
                    if (railHeight <= 0f || itemCount <= 0) return
                    val target = ((y / railHeight) * (itemCount - 1)).toInt()
                        .coerceIn(0, itemCount - 1)
                    if (target != lastIndex) {
                        lastIndex = target
                        if (target == 0 || target == itemCount - 1) haptics.play(Feel.Boundary)
                        else haptics.play(Feel.Step)
                    }
                    scope.launch { listState.scrollToItem(target) }
                }
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        dragging = true; lastIndex = Int.MIN_VALUE
                        haptics.play(Feel.Grab); seek(offset.y)
                    },
                    onDragEnd = { dragging = false; haptics.play(Feel.Release) },
                    onDragCancel = { dragging = false; haptics.play(Feel.Release) },
                    onVerticalDrag = { change, _ -> seek(change.position.y) }
                )
            }
    ) {
        Box(
            Modifier
                .align(Alignment.Center)
                .width(4.dp)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(Color.White.copy(alpha = if (dragging) 0.16f else 0.08f))
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val thumbH = (maxHeight * thumbFraction).coerceAtLeast(36.dp)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (maxHeight - thumbH) * fraction)
                    .width(if (dragging) 8.dp else 5.dp)
                    .height(thumbH)
                    .clip(CircleShape)
                    .background(if (dragging) accent else Color.White.copy(alpha = 0.45f))
            )
        }
    }
}

/** Its own composable so [AnimatedVisibility] resolves to the plain overload — inside the overlay
 *  the enclosing Column's receiver is in scope and the ColumnScope one wins instead. */
@Composable
private fun NowPlayingJump(
    visible: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        NowPlayingPill(accent = accent, onClick = onClick)
    }
}

@Composable
private fun NowPlayingPill(accent: Color, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = accent, onClick = onClick) {
        val ink = if (accent.luminance() > 0.5f) Color.Black else Color.White
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.GraphicEq, null, Modifier.size(16.dp), tint = ink)
            Spacer(Modifier.width(8.dp))
            Text(
                "Now playing",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = ink
            )
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
