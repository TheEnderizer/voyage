package com.betteraudio.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.components.BookInfoPanel
import com.betteraudio.ui.components.PartItem
import com.betteraudio.ui.components.ReflectedCoverBackdrop
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.components.ScrimPill
import com.betteraudio.ui.components.frostedWhenVisible
import com.betteraudio.ui.history.BookHistoryOverlay
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.home.PlaybackOptions
import com.betteraudio.ui.theme.Pill
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    onCollapse: () -> Unit,
    initiallyShowInfo: Boolean = false,
    startPlaying: Boolean = true,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val onBack = onCollapse
    val context = LocalContext.current
    val bwp               by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val groupInfo         by viewModel.groupInfo.collectAsStateWithLifecycle()
    val state             by viewModel.playbackState.collectAsStateWithLifecycle()
    val chapters          by viewModel.chapters.collectAsStateWithLifecycle()
    val bookmarks         by viewModel.bookmarks.collectAsStateWithLifecycle()
    val positionStack     by viewModel.positionStack.collectAsStateWithLifecycle()
    val skipForwardMs     by viewModel.skipForwardMs.collectAsStateWithLifecycle()
    val skipBackMs        by viewModel.skipBackMs.collectAsStateWithLifecycle()
    val sessions          by viewModel.listeningSessions.collectAsStateWithLifecycle()
    val skips             by viewModel.skipEvents.collectAsStateWithLifecycle()
    val showSeriesCover by viewModel.showSeriesCover.collectAsStateWithLifecycle()
    val seriesCover by viewModel.seriesCover.collectAsStateWithLifecycle()
    val currentSeries by viewModel.currentSeries.collectAsStateWithLifecycle()
    val book = bwp?.book
    val isGroup = viewModel.groupId != -1L
    val inSeries = book?.seriesId != null
    // A book with no author/narrator of its own falls back to the series' (metadata cascade).
    val effectiveAuthor = book?.displayAuthor?.takeIf { it.isNotBlank() } ?: currentSeries?.author
    val effectiveNarrator = book?.narrator?.takeIf { it.isNotBlank() } ?: currentSeries?.narrator

    // Info panel mode: starts in info view when opened from book grid, switches to controls on play.
    // Back is intentionally NOT intercepted here — the system back / swipe always returns straight
    // to home from either info or controls, never bouncing back to the info panel.
    val showInfoState = remember { mutableStateOf(initiallyShowInfo) }

    var showChapters       by remember { mutableStateOf(false) }
    var showBookOptions    by remember { mutableStateOf(false) }
    var showSleepTimer     by remember { mutableStateOf(false) }
    var showBookmarks      by remember { mutableStateOf(false) }
    var showAddBookmark    by remember { mutableStateOf(false) }
    var bookmarkComment    by remember { mutableStateOf("") }
    var showReturnMenu     by remember { mutableStateOf(false) }
    var showAudioSettings  by remember { mutableStateOf(false) }
    var showOverflow       by remember { mutableStateOf(false) }
    var showHistory        by remember { mutableStateOf(false) }

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

    LaunchedEffect(bwp, groupInfo) {
        // Only auto-play when the user deliberately opened this book (startPlaying = true).
        // Cold-start restores (startPlaying = false) must not start playback automatically.
        if (!startPlaying || showInfoState.value || hasAutoPlayed) return@LaunchedEffect
        if (isGroup && groupInfo != null && state.groupId != viewModel.groupId) {
            hasAutoPlayed = true
            viewModel.play()
        } else if (!isGroup && bwp != null && state.bookId != viewModel.bookId) {
            hasAutoPlayed = true
            viewModel.play()
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.saveProgress() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        val loading = if (isGroup) groupInfo == null else bwp == null
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        val onScrim = Color.White
        val onScrimMuted = Color.White.copy(alpha = 0.62f)
        val accent = MaterialTheme.colorScheme.primary

        // For a book in a series, optionally show the series cover instead of the book's own.
        val useSeriesCover = inSeries && showSeriesCover && seriesCover != null
        val coverPath = when {
            useSeriesCover -> seriesCover
            isGroup -> groupInfo?.coverArtPath
            else -> book?.coverArtPath
        }
        val bakedPath = when {
            useSeriesCover -> null            // series cover isn't pre-baked; live-render the effect
            isGroup -> null
            else -> book?.coverFxPath
        }

        // True only when the service already has *this* book/group loaded and playing/paused.
        // False on cold start and whenever the player sheet opens before the service confirms.
        val serviceHasBook = if (isGroup) state.groupId == viewModel.groupId && viewModel.groupId != -1L
                             else state.bookId == viewModel.bookId && viewModel.bookId != -1L

        val bookTotal: Long
        val bookPos: Long
        if (serviceHasBook && state.bookTotalDurationMs > 0) {
            bookTotal = state.bookTotalDurationMs
            bookPos = state.bookPositionMs
        } else if (serviceHasBook) {
            bookTotal = state.durationMs
            bookPos = state.currentPositionMs
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
        // carry every book's chapters, each with positions relative to its own book).
        val items = chapters.rows.filterIsInstance<ChapterRow.Item>()
            .filter { it.bookId == -1L || it.bookId == state.bookId }
        val cur = currentChapter(items, bookPos, bookTotal)

        val sliderColors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.24f)
        )

        Box(Modifier.fillMaxSize().frostedWhenVisible(showHistory || showChapters)) {
            // ── Cover + reflection + progressive scrim ────────────────────
            ReflectedCoverBackdrop(
                coverPath = coverPath,
                bakedPath = bakedPath,
                modifier  = Modifier.fillMaxSize()
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
                    val topLabel = if (isGroup)
                        groupInfo?.name?.takeIf { it.isNotBlank() }
                    else
                        book?.seriesName?.takeIf { it.isNotBlank() }
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
                        ScrimButton(Icons.Default.MoreVert, "More") { showOverflow = true }
                        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                            if (!isGroup) {
                                DropdownMenuItem(
                                    text = { Text("Book options") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                                    onClick = { showOverflow = false; showBookOptions = true }
                                )
                            }
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
                            if (!isGroup) {
                                DropdownMenuItem(
                                    text = { Text("Listening history") },
                                    leadingIcon = { Icon(Icons.Default.History, null) },
                                    onClick = { showOverflow = false; showHistory = true }
                                )
                            }
                            if (!isGroup) {
                                DropdownMenuItem(
                                    text = { Text("Refresh cover effect") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                    onClick = { showOverflow = false; viewModel.refreshCoverEffect() }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // ── Bottom: info panel OR player controls (crossfade, background stays static) ──
                Crossfade(targetState = showInfoState.value, animationSpec = tween(280)) { isInfo: Boolean ->
                if (isInfo) {
                    if (isGroup && groupInfo != null) {
                        val gi = groupInfo!!
                        BookInfoPanel(
                            title            = gi.name,
                            author           = gi.books.firstOrNull()?.displayAuthor,
                            narrator         = null,
                            seriesLabel      = "${gi.books.size} books in series",
                            status           = null,
                            progressFraction = gi.progressFraction,
                            totalMs          = gi.totalDurationMs,
                            synopsis         = null,
                            onResume         = { hasAutoPlayed = true; viewModel.play(); showInfoState.value = false },
                            parts            = gi.books.map { b ->
                                PartItem(b.displayTitle, b.totalDurationMs)
                            }
                        )
                    } else {
                        val seriesLabel = book?.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                            if (book.seriesOrder != null) "$series · #${book.seriesOrder}" else series
                        }
                        BookInfoPanel(
                            title            = book?.displayTitle ?: "",
                            author           = effectiveAuthor,
                            narrator         = effectiveNarrator,
                            seriesLabel      = seriesLabel,
                            status           = book?.status,
                            progressFraction = bwp?.progressFraction ?: 0f,
                            totalMs          = book?.totalDurationMs ?: 0L,
                            synopsis         = book?.synopsis?.takeIf { it.isNotBlank() }
                                               ?: book?.description?.takeIf { it.isNotBlank() },
                            onResume         = { hasAutoPlayed = true; viewModel.play(); showInfoState.value = false },
                            onShowHistory    = { showHistory = true }
                        )
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
                if (!effectiveAuthor.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = effectiveAuthor,
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
                        Modifier.size(72.dp).clip(Pill).background(accent).clickable {
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
                    if (!isGroup) {
                        // Skip-silence toggle (replaces the old playback-speed pill — speed lives
                        // in the audio settings sheet's Speed tab).
                        val skipSilenceOn = book?.skipSilenceEnabled == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(Pill)
                                .background(if (skipSilenceOn) accent.copy(alpha = 0.22f) else Color.Transparent)
                                .clickable { viewModel.setSkipSilenceEnabled(!skipSilenceOn) }
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
                    } else {
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

        ChapterOverlay(
            visible = showChapters && chapters.rows.isNotEmpty(),
            rows = chapters.rows,
            currentPositionMs = if (state.bookTotalDurationMs > 0) state.bookPositionMs else state.currentPositionMs,
            currentBookId = state.bookId,
            onSelect = { viewModel.onChapterSelected(it) },
            onDismiss = { showChapters = false }
        )

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

        BookHistoryOverlay(
            visible = showHistory,
            sessions = sessions,
            skips = skips,
            onResumeSession = { endPos ->
                showInfoState.value = false
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
