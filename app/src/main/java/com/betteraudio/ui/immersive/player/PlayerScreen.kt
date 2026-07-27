package com.betteraudio.ui.immersive.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.betteraudio.ui.components.ReflectedCoverBackdrop
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.components.ScrimPill
import com.betteraudio.ui.components.frostedWhenVisible
import com.betteraudio.ui.history.BookHistoryOverlay
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.home.PlaybackOptions
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.player.AudioSettingsSheet
import com.betteraudio.ui.player.BookmarkSheet
import com.betteraudio.ui.player.ChapterOverlay
import com.betteraudio.ui.player.ChapterRow
import com.betteraudio.ui.player.LocalPlayerExpand
import com.betteraudio.ui.player.LockOverlay
import com.betteraudio.ui.player.PlayerViewModel
import com.betteraudio.ui.player.SkipSilenceSettingsSheet
import com.betteraudio.ui.player.SleepTimerSheet
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.theme.Pill
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    onCollapse: () -> Unit,
    startPlaying: Boolean = true,
    onOpenReader: (Long) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val onBack = onCollapse
    val context = LocalContext.current
    val bwp               by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val state             by viewModel.playbackState.collectAsStateWithLifecycle()
    // The full player stays composed while the sheet is collapsed (parked offscreen by
    // PlayerSheet), so only subscribe to the 500ms position ticks while actually visible —
    // otherwise every tick recomposes this whole (hidden) screen. When collapsed we collect a
    // frozen snapshot; on open, the live StateFlow's current value arrives immediately.
    val expandForTicks = LocalPlayerExpand.current
    val sheetOpen by remember { derivedStateOf { expandForTicks.progress.value > 0.01f } }
    val positionFlow = remember(sheetOpen) {
        if (sheetOpen) viewModel.positionState
        else kotlinx.coroutines.flow.MutableStateFlow(viewModel.positionState.value)
    }
    val position          by positionFlow.collectAsStateWithLifecycle()
    val chapters          by viewModel.chapters.collectAsStateWithLifecycle()
    val bookmarks         by viewModel.bookmarks.collectAsStateWithLifecycle()
    val positionStack     by viewModel.positionStack.collectAsStateWithLifecycle()
    val jumpRestore       by viewModel.jumpRestore.collectAsStateWithLifecycle()
    val skipForwardMs     by viewModel.skipForwardMs.collectAsStateWithLifecycle()
    val skipBackMs        by viewModel.skipBackMs.collectAsStateWithLifecycle()
    val sessions          by viewModel.listeningSessions.collectAsStateWithLifecycle()
    val skips             by viewModel.skipEvents.collectAsStateWithLifecycle()
    val showSeriesCover by viewModel.showSeriesCover.collectAsStateWithLifecycle()
    val seriesCover by viewModel.seriesCover.collectAsStateWithLifecycle()
    val currentSeries by viewModel.currentSeries.collectAsStateWithLifecycle()
    val sleepTimerMinutes by viewModel.sleepTimerMinutes.collectAsStateWithLifecycle()
    val book = bwp?.book
    val inSeries = book?.seriesId != null
    // A book with no author/narrator of its own falls back to the series' (metadata cascade).
    val effectiveAuthor = book?.displayAuthor?.takeIf { it.isNotBlank() } ?: currentSeries?.author
    val effectiveNarrator = book?.narrator?.takeIf { it.isNotBlank() } ?: currentSeries?.narrator

    var showChapters       by remember { mutableStateOf(false) }
    var isLocked           by remember { mutableStateOf(false) }
    var showBookOptions    by remember { mutableStateOf(false) }
    var showSleepTimer     by remember { mutableStateOf(false) }
    var showSkipSilenceSettings by remember { mutableStateOf(false) }
    var showBookmarks      by remember { mutableStateOf(false) }
    var showAddBookmark    by remember { mutableStateOf(false) }
    var bookmarkComment    by remember { mutableStateOf("") }
    var showReturnMenu     by remember { mutableStateOf(false) }
    var showAudioSettings  by remember { mutableStateOf(false) }
    var showOverflow       by remember { mutableStateOf(false) }
    var showHistory        by remember { mutableStateOf(false) }
    // null = closed; true = editing skip-forward; false = editing skip-back (long-press a skip button)
    var skipEditForward    by remember { mutableStateOf<Boolean?>(null) }

    // Scrubber drag state. While dragging, the thumb follows the finger but playback keeps
    // running from the original spot; the seek happens only on release (onValueChangeFinished).
    var chapterScrubStartMs by remember { mutableStateOf(-1L) }
    var chapterDragFrac     by remember { mutableStateOf<Float?>(null) }
    var bookScrubStartMs    by remember { mutableStateOf(-1L) }
    var bookDragFrac        by remember { mutableStateOf<Float?>(null) }

    val coverPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.updateCoverArt(context, it) } }

    // Guard against the LaunchedEffect firing multiple times (bwp changes on every DB write,
    // e.g. touchLastPlayed inside play()) before the service confirms the new bookId via syncState.
    var hasAutoPlayed by remember { mutableStateOf(false) }

    LaunchedEffect(bwp) {
        // Only auto-play when the user deliberately opened this book (startPlaying = true).
        // Cold-start restores (startPlaying = false) must not start playback automatically.
        if (!startPlaying || hasAutoPlayed) return@LaunchedEffect
        if (bwp != null && state.bookId != viewModel.bookId) {
            hasAutoPlayed = true
            viewModel.play()
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.saveProgress() } }

    // Shared-element expansion from the mini player: 0 = mini bar, 1 = full player. The cover,
    // title and transport morph from their mini counterparts; everything else reveals.
    val expand = LocalPlayerExpand.current
    val expandProgress = expand.progress

    Scaffold(
        containerColor = Color.Transparent
    ) { padding ->
        val loading = bwp == null
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val accent = MaterialTheme.colorScheme.primary
        // Immersive: full-bleed cover player, near-white accent-tinted text over the scrim.
        val onScrim = ImmersiveStyle.scrimText()
        val onScrimMuted = ImmersiveStyle.scrimText(muted = true)

        // For a book in a series, optionally show the series cover instead of the book's own.
        val useSeriesCover = inSeries && showSeriesCover && seriesCover != null
        val coverPath = when {
            useSeriesCover -> seriesCover
            else -> book?.coverArtPath
        }
        val bakedPath = when {
            useSeriesCover -> null            // series cover isn't pre-baked; live-render the effect
            else -> book?.coverFxPath
        }

        // True only when the service already has *this* book loaded and playing/paused. False on
        // cold start and whenever the player sheet opens before the service confirms.
        val serviceHasBook = state.bookId == viewModel.bookId && viewModel.bookId != -1L

        val bookTotal: Long
        val bookPos: Long
        if (serviceHasBook && position.bookTotalDurationMs > 0) {
            bookTotal = position.bookTotalDurationMs
            bookPos = position.bookPositionMs
        } else if (serviceHasBook) {
            bookTotal = position.durationMs
            bookPos = position.currentPositionMs
        } else {
            // Service doesn't have this book (e.g. cold start, info-panel open before first play).
            // Show the saved position from the DB so the scrubber reflects where reading left off.
            val p = bwp?.progress
            val sortedFiles = bwp?.audioFiles
                ?.sortedWith(compareBy({ it.trackNumber }, { it.fileName })) ?: emptyList()
            val filesBeforeSaved = sortedFiles
                .takeWhile { it.id != p?.currentFileId }.sumOf { it.durationMs }
            bookTotal = bwp?.book?.totalDurationMs ?: 0L
            bookPos = if (p != null && bookTotal > 0)
                (filesBeforeSaved + p.positionMs).coerceAtMost(bookTotal)
            else 0L
        }

        // For the chapter-context line, only the current book's chapters matter (series lists
        // carry every book's chapters, each with positions relative to its own book). Memoized:
        // rebuilding this list on every position tick was O(all chapters in the series).
        val items = remember(chapters, state.bookId) {
            chapters.rows.filterIsInstance<ChapterRow.Item>()
                .filter { it.bookId == -1L || it.bookId == state.bookId }
        }
        val cur = currentChapter(items, bookPos, bookTotal)

        val trackColor = Color.White.copy(alpha = 0.24f)
        val sliderColors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = trackColor
        )

        val dimColor = Color.Black
        Box(
            Modifier
                .fillMaxSize()
                // Dim/solidify as the player opens (deferred read — no per-frame recompose).
                .drawBehind {
                    val p = expandProgress.value.coerceIn(0f, 1f)
                    drawRect(dimColor.copy(alpha = p * p))
                }
                .frostedWhenVisible(showHistory || showChapters)
        ) {
            // ── Cover + reflection + progressive scrim — grows out of the mini cover. The clip
            // mask itself is animated (rounded like the mini cover → square, full-bleed) so it
            // reads as one object growing into the screen rather than a plain fade. The baked
            // composite (see CoverEffectBaker) is already the "pre-generated blurred lower-half
            // version" the immersive player uses — its sharp top half sits at the composite's
            // 25%-height mark and this backdrop is top-anchored/fill-width, so the visible sharp
            // artwork's center naturally lands ~1/4 down from the top with no extra positioning. ──
            ReflectedCoverBackdrop(
                coverPath = coverPath,
                bakedPath = bakedPath,
                modifier  = Modifier
                    .fillMaxSize()
                    .morphFrom(
                        expand.miniCover, expandProgress,
                        anchorTopLeft = true, byWidth = true, fadeIn = true,
                        sourceRadius = expand.coverSourceRadius, destRadius = 0.dp
                    )
            )
            // Sharp copy of the cover that physically TRAVELS from the mini player's slot up
            // into the backdrop's cover area while the (blurred/reflected) backdrop fades in
            // underneath it; it dissolves into the backdrop at the end of the gesture. The mini
            // bar hides its own cover as soon as the drag starts, so this is the one the eye
            // follows.
            val coverCacheKey = if (!useSeriesCover && book != null) "cover-${book.id}" else null
            val context = LocalContext.current
            // Shares Home's grid-card cache key (see HomeStyle.bookCoverModel) so this cover
            // reuses the already-decoded bitmap instead of redecoding once the morph lands.
            val coverImageModel = remember(coverPath, coverCacheKey) {
                coverPath?.let {
                    coil3.request.ImageRequest.Builder(context)
                        .data(File(it))
                        .memoryCacheKey(coverCacheKey)
                        .build()
                }
            }
            AsyncImage(
                model = coverImageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .morphFrom(
                        expand.miniCover, expandProgress,
                        anchorTopLeft = true, byWidth = true,
                        sourceRadius = expand.coverSourceRadius, destRadius = 0.dp
                    )
                    .graphicsLayer {
                        val p = expandProgress.value
                        alpha = 1f - ((p - 0.55f) / 0.35f).coerceIn(0f, 1f)
                    }
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                // ── Top bar ─────────────────────────────────────────────
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).expandReveal(expandProgress),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScrimButton(Icons.Default.KeyboardArrowDown, "Back", tonal = false, onClick = onBack)
                    Spacer(Modifier.weight(1f))
                    val topLabel = book?.seriesName?.takeIf { it.isNotBlank() }
                    topLabel?.let {
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
                        ScrimButton(Icons.Default.MoreVert, "More", tonal = false) { showOverflow = true }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }, containerColor = ImmersiveStyle.menuColor()) {
                            DropdownMenuItem(
                                text = { Text("Book options") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { showOverflow = false; showBookOptions = true }
                            )
                            DropdownMenuItem(
                                text = { Text("Add bookmark") },
                                leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                                onClick = { showOverflow = false; showAddBookmark = true }
                            )
                            if (inSeries) {
                                DropdownMenuItem(
                                    text = { Text(if (showSeriesCover) "Show book cover" else "Show series cover") },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { showOverflow = false; viewModel.toggleShowSeriesCover() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Listening history") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { showOverflow = false; showHistory = true }
                            )
                            if (book?.ebookPath != null) {
                                DropdownMenuItem(
                                    text = { Text("Read from here") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.readFromHere { bookId -> onOpenReader(bookId) }
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Refresh cover effect") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = { showOverflow = false; viewModel.refreshCoverEffect() }
                            )
                            DropdownMenuItem(
                                text = { Text("Lock screen") },
                                leadingIcon = { Icon(Icons.Default.Lock, null) },
                                onClick = {
                                    showOverflow = false
                                    isLocked = true
                                    // A sheet hosted above LockOverlay would otherwise stay
                                    // reachable while "locked" — close everything first.
                                    showChapters = false
                                    showBookOptions = false
                                    showSleepTimer = false
                                    showSkipSilenceSettings = false
                                    showBookmarks = false
                                    showAddBookmark = false
                                    showReturnMenu = false
                                    showAudioSettings = false
                                    showHistory = false
                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Player controls ─────────────────────────────────────────────────
                Column(Modifier.fillMaxWidth()) {

                // ── Bottom control cluster ──────────────────────────────
                if (!isLocked && chapters.hasChapters && cur != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .expandReveal(expandProgress)
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
                    // Moves/enlarges out of the mini player's title as the sheet opens.
                    modifier = Modifier.fillMaxWidth().morphFrom(expand.miniTitle, expandProgress, anchorTopLeft = true)
                )
                if (!effectiveAuthor.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = effectiveAuthor,
                        style = MaterialTheme.typography.titleSmall,
                        color = onScrimMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().expandReveal(expandProgress)
                    )
                }

                // ── Return / Confirm jump-history pills ─────────────────
                if (!isLocked && positionStack.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.expandReveal(expandProgress)
                    ) {
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
                            DropdownMenu(expanded = showReturnMenu, onDismissRequest = { showReturnMenu = false }, containerColor = ImmersiveStyle.menuColor()) {
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

                // ── Unexpected-jump restore pill (non-destructive; never auto-seeks) ────
                if (!isLocked) {
                    jumpRestore?.let { restore ->
                        Spacer(Modifier.height(14.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.expandReveal(expandProgress)
                        ) {
                            ScrimPill(
                                icon = Icons.AutoMirrored.Filled.Undo,
                                label = "Playback jumped — tap to go back",
                                onClick = { viewModel.restoreFromJump(restore.preJumpBookPosMs) }
                            )
                            ScrimPill(
                                icon = Icons.Default.Close,
                                label = "Dismiss",
                                onClick = { viewModel.dismissJumpRestore() }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isLocked) {
                    // ── Locked: read-only progress only, no draggable slider ────
                    TimeRow(formatDuration(bookPos), formatDuration(bookTotal), onScrimMuted)
                    Spacer(Modifier.height(6.dp))
                    CompactBookProgress(bookPos, bookTotal, accent, onScrimMuted, trackColor, readOnly = true) {}
                } else {
                // ── Scrubber (reveals as the sheet opens) ───────────────
                Column(Modifier.fillMaxWidth().expandReveal(expandProgress)) {
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
                    CompactBookProgress(bookPos, bookTotal, accent, onScrimMuted, trackColor) { viewModel.bookSeekTo(it) }
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
                } // end Column (scrubber reveal)
                } // end else (unlocked scrubber)

                Spacer(Modifier.height(10.dp))

                // ── Transport — the play button GROWS out of the mini player's accent play
                // button (same round accent visual); the skip controls reveal around it. ──
                if (!isLocked) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.totalFiles > 1) {
                        val enabled = state.currentFileIndex > 0
                        IconButton(
                            onClick = { viewModel.playerController.prevFile() },
                            enabled = enabled,
                            modifier = Modifier.expandReveal(expandProgress)
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Previous part", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                    Box(Modifier.expandReveal(expandProgress)) {
                        SkipButton(seconds = (skipBackMs / 1000).toInt(), forward = false, tint = onScrim,
                            onLongPress = { skipEditForward = false }) { viewModel.skipBack() }
                    }
                    Box(
                        Modifier
                            .morphFrom(expand.miniControls, expandProgress)
                            .size(72.dp).clip(Pill).background(accent).clickable {
                                if (!serviceHasBook) viewModel.play() else viewModel.togglePlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Box(Modifier.expandReveal(expandProgress)) {
                        SkipButton(seconds = (skipForwardMs / 1000).toInt(), forward = true, tint = onScrim,
                            onLongPress = { skipEditForward = true }) { viewModel.skipForward() }
                    }
                    if (state.totalFiles > 1) {
                        val enabled = state.currentFileIndex < state.totalFiles - 1
                        IconButton(
                            onClick = { viewModel.playerController.nextFile() },
                            enabled = enabled,
                            modifier = Modifier.expandReveal(expandProgress)
                        ) {
                            Icon(Icons.Default.SkipNext, "Next part", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                }
                } // end if (!isLocked) (transport)

                Spacer(Modifier.height(14.dp))

                // ── Secondary actions ───────────────────────────────────
                if (!isLocked) {
                Row(
                    Modifier.fillMaxWidth().expandReveal(expandProgress),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip-silence toggle (replaces the old playback-speed pill — speed lives
                    // in the audio settings sheet's Speed tab).
                    run {
                        val skipSilenceOn = book?.skipSilenceEnabled == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(Pill)
                                .background(if (skipSilenceOn) accent.copy(alpha = 0.22f) else Color.Transparent)
                                .combinedClickable(
                                    onClick = { viewModel.setSkipSilenceEnabled(!skipSilenceOn) },
                                    onLongClick = { showSkipSilenceSettings = true }
                                )
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                Icons.Default.FastForward, null, Modifier.size(16.dp),
                                tint = if (skipSilenceOn) accent else onScrimMuted
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Skip silence",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (skipSilenceOn) accent else onScrimMuted
                            )
                        }
                    }
                    SecondaryIcon(Icons.Default.Tune, "Audio settings", accent) { showAudioSettings = true }
                    SecondaryIcon(Icons.Default.Bookmark, "Bookmarks", onScrim) { showBookmarks = true }
                    // Tap starts a timer at the slider's set duration (or cancels one already
                    // running); long-press opens the full options (slider/custom entry/end-of-
                    // chapter/fade/shake/schedule).
                    Box(
                        Modifier
                            .clip(Pill)
                            .combinedClickable(
                                onClick = {
                                    if (position.sleepTimerRemainingMs > 0L) {
                                        viewModel.playerController.setSleepTimer(0L)
                                    } else {
                                        viewModel.playerController.setSleepTimer(sleepTimerMinutes * 60_000L)
                                    }
                                },
                                onLongClick = { showSleepTimer = true }
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (position.sleepTimerRemainingMs > 0L) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(22.dp), tint = accent)
                                Text(formatDuration(position.sleepTimerRemainingMs), style = MaterialTheme.typography.labelSmall, color = accent)
                            }
                        } else {
                            Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(22.dp), tint = onScrim)
                        }
                    }
                }
                } // end if (!isLocked) (secondary actions)

                Spacer(Modifier.height(18.dp))
                } // end Column (player controls)
            }
        }

        if (showBookmarks) {
            val bookTotalForSheet = if (position.bookTotalDurationMs > 0) position.bookTotalDurationMs else position.durationMs
            BookmarkSheet(
                bookmarks = bookmarks,
                currentPositionMs = if (position.bookTotalDurationMs > 0) position.bookPositionMs else position.currentPositionMs,
                totalDurationMs = bookTotalForSheet,
                onJump = { viewModel.jumpToBookmark(it) },
                onDelete = { viewModel.deleteBookmark(it) },
                onAddHere = { showAddBookmark = true },
                onDismiss = { showBookmarks = false }
            )
        }

        if (showAddBookmark) {
            AlertDialog(
                containerColor = ImmersiveStyle.dialogColor(),
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

        ChapterOverlay(
            visible = showChapters && chapters.rows.isNotEmpty(),
            rows = chapters.rows,
            currentPositionMs = if (position.bookTotalDurationMs > 0) position.bookPositionMs else position.currentPositionMs,
            currentBookId = state.bookId,
            onSelect = { viewModel.onChapterSelected(it) },
            onDismiss = { showChapters = false }
        )

        LockOverlay(locked = isLocked, onUnlock = { isLocked = false })

        if (showBookOptions && bwp != null) {
            BookOptionsSheet(
                bwp = bwp!!,
                onDismiss = { showBookOptions = false },
                onUpdateMetadata = { title, author ->
                    bwp?.book?.let { viewModel.updateBookMetadata(title, author) }
                },
                onUpdateSeries = { name, order -> viewModel.updateSeriesInfo(name, order) },
                onUpdateStatus = { viewModel.updateBookStatus(it) },
                onSearchOnlineCover = { showBookOptions = false },
                onRefreshCoverEffect = { viewModel.refreshCoverEffect() },
                onIgnore = { },
                onDeletePermanently = { },
                playback = PlaybackOptions(
                    currentSpeed = state.speed,
                    currentBoostDb = viewModel.currentBoostDb,
                    onSpeedChange = { viewModel.setSpeed(it) },
                    onBoostChange = { viewModel.setVolumeBoost(it) },
                    onChangeCoverFromGallery = { coverPickerLauncher.launch("image/*") }
                )
            )
        }

        if (showSleepTimer) {
            val fadeSeconds by viewModel.sleepFadeSeconds.collectAsStateWithLifecycle()
            val shakeEnabled by viewModel.sleepShakeEnabled.collectAsStateWithLifecycle()
            val shakeResetMinutes by viewModel.sleepShakeResetMinutes.collectAsStateWithLifecycle()
            val scheduleEnabled by viewModel.sleepScheduleEnabled.collectAsStateWithLifecycle()
            val scheduleStartMinutes by viewModel.sleepScheduleStartMinutes.collectAsStateWithLifecycle()
            val scheduleEndMinutes by viewModel.sleepScheduleEndMinutes.collectAsStateWithLifecycle()
            val scheduleDefaultMinutes by viewModel.sleepScheduleDefaultMinutes.collectAsStateWithLifecycle()
            SleepTimerSheet(
                remainingMs = position.sleepTimerRemainingMs,
                isEndOfChapter = position.sleepTimerEndOfChapter,
                hasChapters = chapters.hasChapters,
                timerMinutes = sleepTimerMinutes,
                fadeSeconds = fadeSeconds,
                shakeEnabled = shakeEnabled,
                shakeResetMinutes = shakeResetMinutes,
                scheduleEnabled = scheduleEnabled,
                scheduleStartMinutes = scheduleStartMinutes,
                scheduleEndMinutes = scheduleEndMinutes,
                scheduleDefaultMinutes = scheduleDefaultMinutes,
                onSetTimer = { viewModel.playerController.setSleepTimer(it) },
                onSetTimerMinutes = { viewModel.setSleepTimerMinutes(it) },
                onSetEndOfChapter = { viewModel.setSleepTimerEndOfCurrentChapter() },
                onSetFadeSeconds = { viewModel.setSleepFadeSeconds(it) },
                onSetShakeEnabled = { viewModel.setSleepShakeEnabled(it) },
                onSetShakeResetMinutes = { viewModel.setSleepShakeResetMinutes(it) },
                onSetScheduleEnabled = { viewModel.setSleepScheduleEnabled(it) },
                onSetScheduleStartMinutes = { viewModel.setSleepScheduleStartMinutes(it) },
                onSetScheduleEndMinutes = { viewModel.setSleepScheduleEndMinutes(it) },
                onSetScheduleDefaultMinutes = { viewModel.setSleepScheduleDefaultMinutes(it) },
                onDismiss = { showSleepTimer = false }
            )
        }

        if (showSkipSilenceSettings) {
            val minMs by viewModel.skipSilenceMinMs.collectAsStateWithLifecycle()
            val threshold by viewModel.skipSilenceThreshold.collectAsStateWithLifecycle()
            val paddingMs by viewModel.skipSilencePaddingMs.collectAsStateWithLifecycle()
            SkipSilenceSettingsSheet(
                minMs = minMs,
                threshold = threshold,
                paddingMs = paddingMs,
                onSetMinMs = { viewModel.setSkipSilenceMinMs(it) },
                onSetThreshold = { viewModel.setSkipSilenceThreshold(it) },
                onSetPaddingMs = { viewModel.setSkipSilencePaddingMs(it) },
                onDismiss = { showSkipSilenceSettings = false }
            )
        }

        if (showAudioSettings) {
            AudioSettingsSheet(
                viewModel = viewModel,
                onDismiss = { showAudioSettings = false }
            )
        }

        skipEditForward?.let { forward ->
            SkipValueDialog(
                forward = forward,
                currentSeconds = ((if (forward) skipForwardMs else skipBackMs) / 1000).toInt(),
                onConfirm = { secs ->
                    if (forward) viewModel.setSkipForwardMs(secs * 1000L)
                    else viewModel.setSkipBackMs(secs * 1000L)
                    skipEditForward = null
                },
                onDismiss = { skipEditForward = null }
            )
        }

        BookHistoryOverlay(
            visible = showHistory,
            sessions = sessions,
            skips = skips,
            onResumeSession = { endPos ->
                viewModel.resumeFromHistory(endPos)
                showHistory = false
            },
            onDismiss = { showHistory = false }
        )
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

/** Slim whole-book progress, tappable to reveal a full book scrubber. Styled for the dark scrim.
 *  [readOnly] (used for the locked player) disables the tap-to-expand/drag entirely and hides the
 *  expand chevron, leaving a purely passive progress display. */
@Composable
private fun CompactBookProgress(
    positionMs: Long,
    totalMs: Long,
    accent: Color,
    muted: Color,
    trackColor: Color,
    readOnly: Boolean = false,
    onSeek: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val frac = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .let { if (readOnly) it else it.clip(Pill).clickable { expanded = !expanded } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Book", style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.weight(1f).height(4.dp).clip(Pill),
                color = accent,
                trackColor = trackColor
            )
            Spacer(Modifier.width(10.dp))
            Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = muted)
            if (!readOnly) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse" else "Expand book progress",
                    Modifier.size(18.dp),
                    tint = muted
                )
            }
        }
        if (!readOnly && expanded) {
            var dragFrac by remember { mutableStateOf<Float?>(null) }
            val displayFrac = dragFrac ?: frac
            Slider(
                value = displayFrac,
                onValueChange = { dragFrac = it },
                onValueChangeFinished = { dragFrac?.let { onSeek((it * totalMs).toLong()) }; dragFrac = null },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = trackColor
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

/** A circular skip control that shows the configured seconds in its centre. Long-press to change
 *  the skip amount. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkipButton(
    seconds: Int,
    forward: Boolean,
    tint: Color,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit
) {
    Box(
        Modifier.size(56.dp).clip(Pill)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Replay,
            if (forward) "Skip forward $seconds seconds" else "Skip back $seconds seconds",
            Modifier.size(38.dp).graphicsLayer { if (forward) scaleX = -1f },
            tint = tint
        )
        // The Replay glyph's open loop sits slightly low-left of the box centre, so the centred
        // number reads as off. Nudge it into the loop's optical centre (the icon is mirrored for
        // the forward button, but the text is a separate child so it isn't flipped).
        Text(
            "$seconds",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.offset(x = 0.5.dp, y = 1.5.dp)
        )
    }
}

/** Stepper dialog to change a skip interval (opened by long-pressing a skip button). */
@Composable
private fun SkipValueDialog(
    forward: Boolean,
    currentSeconds: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var secs by remember { mutableStateOf(currentSeconds.coerceIn(5, 300)) }
    AlertDialog(
        containerColor = ImmersiveStyle.dialogColor(),
        onDismissRequest = onDismiss,
        title = { Text(if (forward) "Skip forward" else "Skip back") },
        text = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = { secs = (secs - 5).coerceAtLeast(5) }) {
                    Icon(Icons.Default.Remove, "Less")
                }
                Text("$secs s", style = MaterialTheme.typography.headlineSmall)
                FilledTonalIconButton(onClick = { secs = (secs + 5).coerceAtMost(300) }) {
                    Icon(Icons.Default.Add, "More")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(secs) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SecondaryIcon(icon: ImageVector, cd: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, cd, Modifier.size(22.dp), tint = tint) }
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
