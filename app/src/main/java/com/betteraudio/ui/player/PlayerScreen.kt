package com.betteraudio.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.betteraudio.ui.theme.Pill
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val bwp               by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val state             by viewModel.playbackState.collectAsStateWithLifecycle()
    val chapters          by viewModel.chapters.collectAsStateWithLifecycle()
    val bookmarks         by viewModel.bookmarks.collectAsStateWithLifecycle()
    val synopsisGenerating by viewModel.synopsisGenerating.collectAsStateWithLifecycle()
    val synopsisError      by viewModel.synopsisError.collectAsStateWithLifecycle()
    val preJumpPositionMs by viewModel.preJumpPositionMs.collectAsStateWithLifecycle()
    val book = bwp?.book

    var showChapters      by remember { mutableStateOf(false) }
    var showBookSettings  by remember { mutableStateOf(false) }
    var showSleepTimer    by remember { mutableStateOf(false) }
    var showBookmarks     by remember { mutableStateOf(false) }
    var showAddBookmark   by remember { mutableStateOf(false) }
    var bookmarkComment   by remember { mutableStateOf("") }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.updateCoverArt(context, it) } }

    LaunchedEffect(bwp) {
        if (bwp != null && state.bookId != viewModel.bookId) {
            viewModel.play()
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.saveProgress() } }

    // The whole app already recolours to the playing book's cover (see BetterAudioTheme),
    // so the player just uses the ambient theme.
    run {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
            if (bwp == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Scaffold
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
            ) {
                // ── Top row (fixed) ─────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                    Spacer(Modifier.weight(1f))
                    if (state.sleepTimerRemainingMs > 0L) {
                        BadgedBox(badge = {
                            Badge { Text(formatDuration(state.sleepTimerRemainingMs)) }
                        }) {
                            PlayerCircleButton(Icons.Default.Bedtime, "Sleep timer") { showSleepTimer = true }
                        }
                    } else {
                        PlayerCircleButton(Icons.Default.Bedtime, "Sleep timer") { showSleepTimer = true }
                    }
                    Spacer(Modifier.width(8.dp))
                    // "Return to previous position" — visible after a bookmark jump
                    if (preJumpPositionMs != null) {
                        PlayerCircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Return to previous position") {
                            viewModel.returnFromJump()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (chapters.hasChapters) {
                        PlayerCircleButton(Icons.AutoMirrored.Filled.List, "Chapters") { showChapters = true }
                        Spacer(Modifier.width(8.dp))
                    }
                    PlayerCircleButton(Icons.Default.Bookmark, "Bookmarks") { showBookmarks = true }
                    Spacer(Modifier.width(8.dp))
                    PlayerCircleButton(Icons.Default.MoreVert, "Book settings") { showBookSettings = true }
                }

                // ── Scrollable content ──────────────────────────────────
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(8.dp))

                    // Cover art
                    Box(
                        Modifier
                            .fillMaxWidth(0.74f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(32.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showBookSettings = true }
                            )
                    ) {
                        AsyncImage(
                            model = book?.coverArtPath?.let { File(it) },
                            contentDescription = "Cover art — long-press to edit",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // Title & author
                    Text(
                        text = book?.title ?: "",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (book?.author?.isNotBlank() == true) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Metadata chips ──────────────────────────────────
                    val infoChips = buildList {
                        book?.narrator?.takeIf { it.isNotBlank() }?.let { add("🎙 $it") }
                        book?.genre?.takeIf { it.isNotBlank() }?.let { add(it) }
                        book?.year?.let { add(it.toString()) }
                        if (chapters.chapterCount > 0) add("${chapters.chapterCount} chapters")
                        else if (state.totalFiles > 1) add("${state.totalFiles} parts")
                        book?.totalDurationMs?.takeIf { it > 0 }?.let { add(formatDurationLong(it)) }
                    }
                    if (infoChips.isNotEmpty()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            infoChips.forEach { InfoChip(it) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    // Description / synopsis
                    val blurb = book?.description?.takeIf { it.isNotBlank() }
                        ?: book?.synopsis?.takeIf { it.isNotBlank() }
                    when {
                        blurb != null -> {
                            // Collapsed: 1 line + a fading 2nd line. Tap to expand / collapse.
                            var synopsisExpanded by remember(blurb) { mutableStateOf(false) }
                            Text(
                                blurb,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (synopsisExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { synopsisExpanded = !synopsisExpanded }
                                    .animateContentSize()
                                    .then(if (synopsisExpanded) Modifier else Modifier.bottomFade())
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        synopsisGenerating -> {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text(
                                    "Generating synopsis…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        synopsisError != null -> {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Synopsis: $synopsisError",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = { viewModel.retrySynopsis() }) {
                                        Text("Retry", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }

                    // ── Progress: chapter (main) + collapsible book bar ─────
                    val useBookLevel = state.bookTotalDurationMs > 0
                    val bookTotal = if (useBookLevel) state.bookTotalDurationMs else state.durationMs
                    val bookPos = if (useBookLevel) state.bookPositionMs else state.currentPositionMs
                    val items = chapters.rows.filterIsInstance<ChapterRow.Item>()
                    val cur = currentChapter(items, bookPos, bookTotal)

                    if (chapters.hasChapters && cur != null) {
                        // Current chapter title + index
                        Text(
                            cur.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Chapter ${cur.index + 1} of ${items.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))

                        val chDur = (cur.endMs - cur.startMs).coerceAtLeast(1L)
                        val chPos = (bookPos - cur.startMs).coerceIn(0L, chDur)
                        Slider(
                            value = (chPos.toFloat() / chDur).coerceIn(0f, 1f),
                            onValueChange = { f -> viewModel.bookSeekTo(cur.startMs + (f * chDur).toLong()) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(chPos), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("-${formatDuration(chDur - chPos)}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        Spacer(Modifier.height(10.dp))
                        BookProgressBar(
                            positionMs = bookPos,
                            totalMs = bookTotal,
                            onSeek = { viewModel.bookSeekTo(it) }
                        )
                    } else {
                        // Single-chapter / no-chapter fallback: one book-level scrubber
                        Slider(
                            value = if (bookTotal > 0) (bookPos.toFloat() / bookTotal).coerceIn(0f, 1f) else 0f,
                            onValueChange = { f -> viewModel.bookSeekTo((f * bookTotal).toLong()) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(bookPos), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatDuration(bookTotal), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Transport controls ──────────────────────────────
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.playerController.prevFile() },
                            enabled = state.currentFileIndex > 0
                        ) { Icon(Icons.Default.SkipPrevious, "Previous part", Modifier.size(28.dp)) }

                        IconButton(onClick = { viewModel.skipBack() }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Default.Replay, "Skip back", Modifier.size(32.dp))
                        }

                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(Pill)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { viewModel.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }

                        IconButton(onClick = { viewModel.skipForward() }, modifier = Modifier.size(52.dp)) {
                            Icon(Icons.Default.FastForward, "Skip forward", Modifier.size(32.dp))
                        }

                        IconButton(
                            onClick = { viewModel.playerController.nextFile() },
                            enabled = state.totalFiles > 1 && state.currentFileIndex < state.totalFiles - 1
                        ) { Icon(Icons.Default.SkipNext, "Next part", Modifier.size(28.dp)) }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ── Speed control ───────────────────────────────────
                    var sliderSpeed by remember(state.speed) { mutableFloatStateOf(state.speed) }
                    ControlCard(icon = Icons.Default.Speed, label = "Speed",
                        value = "${"%.2f".format(sliderSpeed)}×") {
                        Slider(
                            value = sliderSpeed,
                            onValueChange = { raw -> sliderSpeed = (raw / 0.05f).roundToInt() * 0.05f },
                            onValueChangeFinished = { viewModel.setSpeed(sliderSpeed) },
                            valueRange = 0.5f..3.0f,
                            steps = 49,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── Volume amplifier ────────────────────────────────
                    var boost by remember(viewModel.currentBoostDb) { mutableIntStateOf(viewModel.currentBoostDb) }
                    ControlCard(
                        icon = Icons.Default.VolumeUp,
                        label = "Volume boost",
                        value = if (boost == 0) "Off" else "+$boost dB"
                    ) {
                        Slider(
                            value = boost.toFloat(),
                            onValueChange = { boost = it.roundToInt() },
                            onValueChangeFinished = { viewModel.setVolumeBoost(boost) },
                            valueRange = 0f..24f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (boost > 12) {
                            Text(
                                "High boost may distort audio",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (showBookmarks) {
            val bookTotal = if (state.bookTotalDurationMs > 0) state.bookTotalDurationMs else state.durationMs
            BookmarkSheet(
                bookmarks = bookmarks,
                currentPositionMs = if (state.bookTotalDurationMs > 0) state.bookPositionMs else state.currentPositionMs,
                totalDurationMs = bookTotal,
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
                onSeek = { viewModel.bookSeekTo(it) },
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
    }
}

/** Fades the bottom of a composable to transparent — used so the collapsed synopsis' 2nd line trails off. */
private fun Modifier.bottomFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                0.55f to Color.Black,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
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

/**
 * Whole-book progress, collapsed by default to a slim bar so it stays out of the way;
 * tapping expands it into a full book scrubber.
 */
@Composable
private fun BookProgressBar(positionMs: Long, totalMs: Long, onSeek: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val frac = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Book",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.weight(1f).height(5.dp).clip(Pill),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${(frac * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse" else "Expand book progress",
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = frac,
                    onValueChange = { f -> onSeek((f * totalMs).toLong()) },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(positionMs), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("-${formatDuration((totalMs - positionMs).coerceAtLeast(0))} left",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(shape = Pill, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun ControlCard(
    icon: ImageVector,
    label: String,
    value: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun PlayerCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(Pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun formatDurationLong(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
