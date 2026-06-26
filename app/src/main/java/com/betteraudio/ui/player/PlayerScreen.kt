package com.betteraudio.ui.player

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.components.ReflectedProgressiveBlurCover
import com.betteraudio.ui.theme.Pill
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    initiallyShowInfo: Boolean = false,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val bwp               by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val state             by viewModel.playbackState.collectAsStateWithLifecycle()
    val chapters          by viewModel.chapters.collectAsStateWithLifecycle()
    val bookmarks         by viewModel.bookmarks.collectAsStateWithLifecycle()
    val positionStack     by viewModel.positionStack.collectAsStateWithLifecycle()
    val skipForwardMs     by viewModel.skipForwardMs.collectAsStateWithLifecycle()
    val skipBackMs        by viewModel.skipBackMs.collectAsStateWithLifecycle()
    val book = bwp?.book

    // Info panel mode: starts in info view when opened from book grid, switches to controls on play.
    val showInfoState = remember { mutableStateOf(initiallyShowInfo) }
    // Back in player-mode when opened from info: return to info view rather than popping the screen.
    BackHandler(enabled = !showInfoState.value && initiallyShowInfo) { showInfoState.value = true }

    var showChapters       by remember { mutableStateOf(false) }
    var showBookSettings   by remember { mutableStateOf(false) }
    var showSleepTimer     by remember { mutableStateOf(false) }
    var showBookmarks      by remember { mutableStateOf(false) }
    var showAddBookmark    by remember { mutableStateOf(false) }
    var bookmarkComment    by remember { mutableStateOf("") }
    var showReturnMenu     by remember { mutableStateOf(false) }
    var showAudioSettings  by remember { mutableStateOf(false) }
    var showOverflow       by remember { mutableStateOf(false) }

    // Scrubber drag state. While dragging, the thumb follows the finger but playback keeps
    // running from the original spot; the seek happens only on release (onValueChangeFinished).
    var chapterScrubStartMs by remember { mutableStateOf(-1L) }
    var chapterDragFrac     by remember { mutableStateOf<Float?>(null) }
    var bookScrubStartMs    by remember { mutableStateOf(-1L) }
    var bookDragFrac        by remember { mutableStateOf<Float?>(null) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.updateCoverArt(context, it) } }

    LaunchedEffect(bwp) {
        if (bwp != null && state.bookId != viewModel.bookId && !showInfoState.value) {
            viewModel.play()
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.saveProgress() } }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        if (bwp == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val onScrim = Color.White
        val onScrimMuted = Color.White.copy(alpha = 0.62f)
        val accent = MaterialTheme.colorScheme.primary

        val useBookLevel = state.bookTotalDurationMs > 0
        val bookTotal = if (useBookLevel) state.bookTotalDurationMs else state.durationMs
        val bookPos = if (useBookLevel) state.bookPositionMs else state.currentPositionMs
        val items = chapters.rows.filterIsInstance<ChapterRow.Item>()
        val cur = currentChapter(items, bookPos, bookTotal)

        val sliderColors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.24f)
        )

        Box(Modifier.fillMaxSize()) {
            // ── Cover + reflection background ─────────────────────────────
            Box(Modifier.fillMaxSize().background(Color.Black))
            Box(Modifier.fillMaxSize().clipToBounds()) {
                ReflectedProgressiveBlurCover(
                    coverPath = book?.coverArtPath,
                    bakedPath = book?.coverFxPath,
                    modifier  = Modifier.fillMaxWidth()
                )
            }
            // Progressive scrim: clear at top of cover, dark over controls
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.15f),
                        0.38f to Color.Black.copy(alpha = 0.04f),
                        0.54f to Color.Black.copy(alpha = 0.52f),
                        0.75f to Color.Black.copy(alpha = 0.86f),
                        1f to Color.Black.copy(alpha = 0.97f)
                    )
                )
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                // ── Top bar ─────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScrimButton(Icons.Default.KeyboardArrowDown, "Back", onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    book?.seriesName?.takeIf { it.isNotBlank() }?.let {
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
                    Box {
                        ScrimButton(Icons.Default.MoreVert, "More") { showOverflow = true }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Book settings") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { showOverflow = false; showBookSettings = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Add bookmark") },
                                leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                                onClick = { showOverflow = false; showAddBookmark = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Refresh cover effect") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = { showOverflow = false; viewModel.refreshCoverEffect() }
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Bottom: info panel OR player controls (crossfade, background stays static) ──
                Crossfade(targetState = showInfoState.value, animationSpec = tween(280)) { isInfo: Boolean ->
                if (isInfo) {
                    // ── Book info panel (same content as old BookInfoScreen) ──────────────
                    Column(Modifier.fillMaxWidth()) {
                        book?.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                            val label = if (book.seriesOrder != null) "$series · #${book.seriesOrder}" else series
                            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium,
                                color = accent, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Text(book?.displayTitle ?: "", style = MaterialTheme.typography.headlineSmall,
                            color = onScrim, fontWeight = FontWeight.Bold,
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth())
                        if (book?.displayAuthor?.isNotBlank() == true) {
                            Spacer(Modifier.height(2.dp))
                            Text(book.displayAuthor, style = MaterialTheme.typography.titleSmall,
                                color = onScrimMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!book?.narrator.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text("Narrated by ${book!!.narrator}", style = MaterialTheme.typography.bodySmall, color = onScrimMuted)
                        }
                        Spacer(Modifier.height(12.dp))
                        val statusLabel = when (book?.status) {
                            BookStatus.NOT_STARTED -> "Not started"
                            BookStatus.IN_PROGRESS -> "In progress"
                            BookStatus.FINISHED    -> "Finished"
                            null                   -> ""
                        }
                        if (statusLabel.isNotEmpty()) SuggestionChip(onClick = {}, label = { Text(statusLabel) })
                        val prog = bwp?.progressFraction ?: 0f
                        val totalMs = book?.totalDurationMs ?: 0L
                        if (totalMs > 0) {
                            Spacer(Modifier.height(14.dp))
                            val remainingMs = ((1f - prog) * totalMs).toLong().coerceAtLeast(0L)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${(prog * 100).toInt()}% complete",
                                    style = MaterialTheme.typography.labelMedium, color = onScrimMuted)
                                Text("${formatDurationHuman(remainingMs)} remaining",
                                    style = MaterialTheme.typography.labelMedium, color = onScrimMuted)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { prog },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(Pill),
                                color = accent, trackColor = Color.White.copy(alpha = 0.22f))
                        }
                        val about = book?.synopsis?.takeIf { it.isNotBlank() }
                            ?: book?.description?.takeIf { it.isNotBlank() }
                        if (about != null) {
                            Spacer(Modifier.height(16.dp))
                            Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                                Text(about, style = MaterialTheme.typography.bodyMedium, color = onScrimMuted)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        val buttonLabel = when {
                            prog <= 0f                         -> "Start"
                            bwp?.progress?.isCompleted == true -> "Play again"
                            else                               -> "Resume"
                        }
                        Button(
                            onClick = { viewModel.play(); showInfoState.value = false },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = Pill
                        ) {
                            Icon(Icons.Default.PlayArrow, null, Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(buttonLabel, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                } else {
                // ── Player controls ─────────────────────────────────────────────────
                Column(Modifier.fillMaxWidth()) {

                // ── Bottom control cluster ──────────────────────────────
                if (chapters.hasChapters && cur != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(com.betteraudio.ui.theme.Pill)
                            .clickable { showChapters = true }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(15.dp), tint = onScrimMuted)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Chapter ${cur.index + 1} · ${cur.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = onScrimMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    text = book?.title ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = onScrim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                if (book?.author?.isNotBlank() == true) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.titleSmall,
                        color = onScrimMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // ── Return / Confirm jump-history pills ─────────────────
                if (positionStack.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            ScrimPill(
                                icon = Icons.AutoMirrored.Filled.ArrowBack,
                                label = "Return ${formatDuration(positionStack.last())}",
                                trailing = if (positionStack.size > 1) Icons.Default.ArrowDropDown else null,
                                onClick = {
                                    if (positionStack.size > 1) showReturnMenu = true
                                    else viewModel.returnFromJump()
                                }
                            )
                            DropdownMenu(expanded = showReturnMenu, onDismissRequest = { showReturnMenu = false }) {
                                positionStack.reversed().forEachIndexed { displayIdx, posMs ->
                                    val stackIdx = positionStack.size - 1 - displayIdx
                                    DropdownMenuItem(
                                        text = { Text(formatDuration(posMs)) },
                                        onClick = { showReturnMenu = false; viewModel.returnToIndex(stackIdx) }
                                    )
                                }
                            }
                        }
                        ScrimPill(
                            icon = Icons.Default.Check,
                            label = "Confirm",
                            filled = true,
                            onClick = { viewModel.confirmPosition() }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Scrubber ────────────────────────────────────────────
                if (chapters.hasChapters && cur != null) {
                    val chDur = (cur.endMs - cur.startMs).coerceAtLeast(1L)
                    val livePos = (bookPos - cur.startMs).coerceIn(0L, chDur)
                    val chDisplayFrac = chapterDragFrac ?: (livePos.toFloat() / chDur).coerceIn(0f, 1f)
                    val chDisplayPos = (chDisplayFrac * chDur).toLong()
                    Slider(
                        value = chDisplayFrac,
                        onValueChange = { f ->
                            if (chapterDragFrac == null) chapterScrubStartMs = bookPos
                            chapterDragFrac = f
                        },
                        onValueChangeFinished = {
                            val f = chapterDragFrac
                            if (f != null) {
                                val target = cur.startMs + (f * chDur).toLong()
                                viewModel.bookSeekTo(target)
                                if (chapterScrubStartMs >= 0L)
                                    viewModel.pushPositionIfLargeJump(chapterScrubStartMs, target)
                            }
                            chapterScrubStartMs = -1L
                            chapterDragFrac = null
                        },
                        colors = sliderColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeRow(formatDuration(chDisplayPos), "-${formatDuration(chDur - chDisplayPos)}", onScrimMuted)
                    Spacer(Modifier.height(2.dp))
                    CompactBookProgress(bookPos, bookTotal, accent, onScrimMuted) { viewModel.bookSeekTo(it) }
                } else {
                    val liveFrac = if (bookTotal > 0) (bookPos.toFloat() / bookTotal).coerceIn(0f, 1f) else 0f
                    val bookDisplayFrac = bookDragFrac ?: liveFrac
                    val bookDisplayPos = (bookDisplayFrac * bookTotal).toLong()
                    Slider(
                        value = bookDisplayFrac,
                        onValueChange = { f ->
                            if (bookDragFrac == null) bookScrubStartMs = bookPos
                            bookDragFrac = f
                        },
                        onValueChangeFinished = {
                            val f = bookDragFrac
                            if (f != null) {
                                val target = (f * bookTotal).toLong()
                                viewModel.bookSeekTo(target)
                                if (bookScrubStartMs >= 0L)
                                    viewModel.pushPositionIfLargeJump(bookScrubStartMs, target)
                            }
                            bookScrubStartMs = -1L
                            bookDragFrac = null
                        },
                        colors = sliderColors,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeRow(formatDuration(bookDisplayPos), formatDuration(bookTotal), onScrimMuted)
                }

                Spacer(Modifier.height(10.dp))

                // ── Transport ───────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.totalFiles > 1) {
                        val enabled = state.currentFileIndex > 0
                        IconButton(onClick = { viewModel.playerController.prevFile() }, enabled = enabled) {
                            Icon(Icons.Default.SkipPrevious, "Previous part", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                    SkipButton(seconds = (skipBackMs / 1000).toInt(), forward = false, tint = onScrim) { viewModel.skipBack() }
                    Box(
                        Modifier.size(72.dp).clip(Pill).background(accent).clickable { viewModel.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    SkipButton(seconds = (skipForwardMs / 1000).toInt(), forward = true, tint = onScrim) { viewModel.skipForward() }
                    if (state.totalFiles > 1) {
                        val enabled = state.currentFileIndex < state.totalFiles - 1
                        IconButton(onClick = { viewModel.playerController.nextFile() }, enabled = enabled) {
                            Icon(Icons.Default.SkipNext, "Next part", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Secondary actions ───────────────────────────────────
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(Pill)
                            .clickable { showAudioSettings = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Speed, null, Modifier.size(16.dp), tint = onScrim)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${"%.2f".format(state.speed).trimEnd('0').trimEnd('.')}×",
                            style = MaterialTheme.typography.labelLarge,
                            color = onScrim
                        )
                    }
                    SecondaryIcon(Icons.Default.Tune, "Audio settings", accent) { showAudioSettings = true }
                    SecondaryIcon(Icons.Default.Bookmark, "Bookmarks", onScrim) { showBookmarks = true }
                    if (state.sleepTimerRemainingMs > 0L) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(Pill).clickable { showSleepTimer = true }.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(22.dp), tint = accent)
                            Text(formatDuration(state.sleepTimerRemainingMs), style = MaterialTheme.typography.labelSmall, color = accent)
                        }
                    } else {
                        SecondaryIcon(Icons.Default.Bedtime, "Sleep timer", onScrim) { showSleepTimer = true }
                    }
                }

                Spacer(Modifier.height(18.dp))
                } // end Column (player controls)
                } // end else (player mode)
                } // end Crossfade
            }
        }

        if (showBookmarks) {
            val bookTotalForSheet = if (state.bookTotalDurationMs > 0) state.bookTotalDurationMs else state.durationMs
            BookmarkSheet(
                bookmarks = bookmarks,
                currentPositionMs = if (state.bookTotalDurationMs > 0) state.bookPositionMs else state.currentPositionMs,
                totalDurationMs = bookTotalForSheet,
                onJump = { viewModel.jumpToBookmark(it) },
                onDelete = { viewModel.deleteBookmark(it) },
                onAddHere = { showAddBookmark = true },
                onDismiss = { showBookmarks = false }
            )
        }

        if (showAddBookmark) {
            AlertDialog(
                onDismissRequest = { showAddBookmark = false; bookmarkComment = "" },
                title = { Text("Add bookmark") },
                text = {
                    OutlinedTextField(
                        value = bookmarkComment,
                        onValueChange = { bookmarkComment = it },
                        label = { Text("Note (optional)") },
                        placeholder = { Text("e.g. 'Interesting part'") },
                        singleLine = false,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.addBookmark(bookmarkComment)
                        bookmarkComment = ""
                        showAddBookmark = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddBookmark = false; bookmarkComment = "" }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showChapters && chapters.rows.isNotEmpty()) {
            ChapterSheet(
                rows = chapters.rows,
                currentPositionMs = if (state.bookTotalDurationMs > 0) state.bookPositionMs else state.currentPositionMs,
                onSeek = { viewModel.seekToChapter(it) },
                onDismiss = { showChapters = false }
            )
        }

        if (showBookSettings && bwp != null) {
            BookSettingsSheet(
                bwp = bwp!!,
                currentBoostDb = viewModel.currentBoostDb,
                onDismiss = { showBookSettings = false },
                onStatusChange = { viewModel.updateBookStatus(it) },
                onSpeedChange = { viewModel.setSpeed(it) },
                onBoostChange = { viewModel.setVolumeBoost(it) },
                onSeriesChange = { name, order -> viewModel.updateSeriesInfo(name, order) },
                onChangeCover = { coverPickerLauncher.launch("image/*") }
            )
        }

        if (showSleepTimer) {
            SleepTimerSheet(
                remainingMs = state.sleepTimerRemainingMs,
                onSetTimer = { viewModel.playerController.setSleepTimer(it) },
                onDismiss = { showSleepTimer = false }
            )
        }

        if (showAudioSettings) {
            AudioSettingsSheet(
                viewModel = viewModel,
                onDismiss = { showAudioSettings = false }
            )
        }
    }
}

private data class ChapterBounds(val title: String, val startMs: Long, val endMs: Long, val index: Int)

/** The chapter containing [posMs] = the last chapter whose start is at or before it. */
private fun currentChapter(items: List<ChapterRow.Item>, posMs: Long, totalMs: Long): ChapterBounds? {
    if (items.isEmpty()) return null
    var idx = items.indexOfLast { it.absStartMs <= posMs + 250 }
    if (idx < 0) idx = 0
    val item = items[idx]
    val end = items.getOrNull(idx + 1)?.absStartMs ?: totalMs.coerceAtLeast(item.absStartMs)
    return ChapterBounds(item.title, item.absStartMs, end, idx)
}

/** Slim whole-book progress, tappable to reveal a full book scrubber. Styled for the dark scrim. */
@Composable
private fun CompactBookProgress(
    positionMs: Long,
    totalMs: Long,
    accent: Color,
    muted: Color,
    onSeek: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val frac = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clip(Pill).clickable { expanded = !expanded }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Book", style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.weight(1f).height(4.dp).clip(Pill),
                color = accent,
                trackColor = Color.White.copy(alpha = 0.22f)
            )
            Spacer(Modifier.width(10.dp))
            Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = muted)
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) "Collapse" else "Expand book progress",
                Modifier.size(18.dp),
                tint = muted
            )
        }
        if (expanded) {
            var dragFrac by remember { mutableStateOf<Float?>(null) }
            val displayFrac = dragFrac ?: frac
            Slider(
                value = displayFrac,
                onValueChange = { dragFrac = it },
                onValueChangeFinished = { dragFrac?.let { onSeek((it * totalMs).toLong()) }; dragFrac = null },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TimeRow(left: String, right: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = MaterialTheme.typography.labelMedium, color = color)
        Text(right, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A circular skip control that shows the configured seconds in its centre. */
@Composable
private fun SkipButton(seconds: Int, forward: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(Pill).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Replay,
            if (forward) "Skip forward $seconds seconds" else "Skip back $seconds seconds",
            Modifier.size(38.dp).graphicsLayer { if (forward) scaleX = -1f },
            tint = tint
        )
        Text("$seconds", style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SecondaryIcon(icon: ImageVector, cd: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, cd, Modifier.size(22.dp), tint = tint) }
}

@Composable
private fun ScrimButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(Pill).background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, Modifier.size(22.dp), tint = Color.White)
    }
}

@Composable
private fun ScrimPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: ImageVector? = null,
    filled: Boolean = false
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f)
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier.clip(Pill).background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        if (trailing != null) {
            Spacer(Modifier.width(2.dp))
            Icon(trailing, null, Modifier.size(15.dp), tint = fg)
        }
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

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
