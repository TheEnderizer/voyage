package com.betteraudio.ui.immersive.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.betteraudio.R
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
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.LocalHaptics
import com.betteraudio.ui.haptics.PressFeel
import com.betteraudio.ui.immersive.components.drawScrubber
import com.betteraudio.ui.player.LocalPlayerExpand
import com.betteraudio.ui.player.LockOverlay
import com.betteraudio.ui.player.PlayerViewModel
import com.betteraudio.ui.player.activeChapterRowIndex
import com.betteraudio.ui.player.SkipSilenceSettingsSheet
import com.betteraudio.ui.player.SleepTimerSheet
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.player.morphFromCircle
import com.betteraudio.ui.theme.Pill
import java.io.File
import java.util.concurrent.TimeUnit
import com.betteraudio.ui.haptics.*

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
    val chapterTimeline   by viewModel.chapterTimeline.collectAsStateWithLifecycle()
    val chapterNav        by viewModel.chapterNav.collectAsStateWithLifecycle()
    val bookmarks         by viewModel.bookmarkRows.collectAsStateWithLifecycle()
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
    val coverSearchOpen    by viewModel.coverSearchOpen.collectAsStateWithLifecycle()
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
            // Prefer chapterTimeline's own file-duration sum over the (possibly stale) Book row,
            // so the last chapter's endMs and this whole-book total always agree.
            bookTotal = chapterTimeline.bookTotalMs.takeIf { it > 0 } ?: (bwp?.book?.totalDurationMs ?: 0L)
            bookPos = if (p != null && bookTotal > 0)
                (filesBeforeSaved + p.positionMs).coerceAtMost(bookTotal)
            else 0L
        }

        // chapterTimeline is DB-backed and scoped to THIS screen's book (viewModel.bookId), not
        // whichever book the service happens to have loaded — so this renders correctly even
        // before the service confirms state.bookId (cold widget open, before first play).
        val cur = chapterTimeline.chapterAt(bookPos)

        val trackColor = Color.White.copy(alpha = 0.24f)

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
            // Shape and scaling must match the BAKE exactly, or the morph lands crooked. The bake
            // keeps the cover's own aspect (CoverEffectBaker: hc = height/width * w, sharp half on
            // top) and is drawn FillWidth/top-anchored — so a hardcoded square with Crop only ever
            // agreed with it for perfectly square art. See rememberCoverAspect.
            val coverAspect = com.betteraudio.ui.components.rememberCoverAspect(coverPath)
            AsyncImage(
                model = coverImageModel,
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(coverAspect)
                    // A circular mini cover and a full-bleed one are different shapes, so they
                    // need different morphs — morphFromCircle grows the disc out of its own centre
                    // crop, morphFrom slides the aspect-matched cap up as a rigid box. Same
                    // destination either way; only the first half of the travel differs.
                    .then(
                        if (expand.coverSourceIsCircle)
                            Modifier.morphFromCircle(expand.miniCover, expandProgress)
                        else
                            Modifier.morphFrom(
                                expand.miniCover, expandProgress,
                                anchorTopLeft = true, byWidth = true,
                                sourceRadius = expand.coverSourceRadius, destRadius = 0.dp
                            )
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
                            HapticDropdownMenuItem(
                                text = { Text("Book options") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { showOverflow = false; showBookOptions = true }
                            )
                            HapticDropdownMenuItem(
                                text = { Text("Add bookmark") },
                                leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                                onClick = { showOverflow = false; showAddBookmark = true }
                            )
                            if (inSeries) {
                                HapticDropdownMenuItem(
                                    text = { Text(if (showSeriesCover) "Show book cover" else "Show series cover") },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { showOverflow = false; viewModel.toggleShowSeriesCover() }
                                )
                            }
                            HapticDropdownMenuItem(
                                text = { Text("Listening history") },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { showOverflow = false; showHistory = true }
                            )
                            if (book?.ebookPath != null && com.betteraudio.util.FeatureFlags.EBOOKS_UI) {
                                HapticDropdownMenuItem(
                                    text = { Text("Read from here") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.readFromHere { bookId -> onOpenReader(bookId) }
                                    }
                                )
                            }
                            HapticDropdownMenuItem(
                                text = { Text("Refresh cover effect") },
                                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                                onClick = { showOverflow = false; viewModel.refreshCoverEffect() }
                            )
                            HapticDropdownMenuItem(
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

                // ── Band 1: identity ────────────────────────────────────
                // Title, then author · narrator, then the chapter as a plain tinted line.
                // The chapter used to be a PILL sitting above the title — a container between
                // the artwork and the book's own name, and one of the seven separate bands this
                // screen used to stack. It is a piece of "where am I" information, so it now sits
                // under the identity it describes and stays a full-width tap target for the
                // chapter list.
                Text(
                    text = book?.displayTitle ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = onScrim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Moves/enlarges out of the mini player's title as the sheet opens.
                    modifier = Modifier.fillMaxWidth().morphFrom(expand.miniTitle, expandProgress, anchorTopLeft = true)
                )
                val credit = listOfNotNull(
                    effectiveAuthor?.takeIf { it.isNotBlank() },
                    effectiveNarrator?.takeIf { it.isNotBlank() }?.let { "read by $it" }
                ).joinToString(" · ")
                if (credit.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = credit,
                        style = MaterialTheme.typography.titleSmall,
                        color = onScrimMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().expandReveal(expandProgress)
                    )
                }
                if (!isLocked && chapterTimeline.hasMultiple && cur != null) {
                    Spacer(Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .expandReveal(expandProgress)
                            .clip(com.betteraudio.ui.theme.Pill)
                            .clickable { showChapters = true }
                            .padding(vertical = 2.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(14.dp), tint = onScrimMuted)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Chapter ${cur.index + 1} — ${cur.title}",
                            style = MaterialTheme.typography.labelMedium,
                            color = onScrimMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
                                    HapticDropdownMenuItem(
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
                if (chapterTimeline.hasMultiple && cur != null) {
                    val chDur = (cur.endMs - cur.startMs).coerceAtLeast(1L)
                    val livePos = (bookPos - cur.startMs).coerceIn(0L, chDur)
                    val chDisplayFrac = chapterDragFrac ?: (livePos.toFloat() / chDur).coerceIn(0f, 1f)
                    val chDisplayPos = (chDisplayFrac * chDur).toLong()
                    ImmersiveScrubber(
                        fraction = chDisplayFrac,
                        accent = accent,
                        trackColor = trackColor,
                        onScrubStart = { chapterScrubStartMs = bookPos },
                        onScrub = { f -> chapterDragFrac = f },
                        onScrubEnd = { f ->
                            val target = cur.startMs + (f * chDur).toLong()
                            viewModel.bookSeekTo(target)
                            if (chapterScrubStartMs >= 0L)
                                viewModel.onScrubSeek(chapterScrubStartMs, target)
                            chapterScrubStartMs = -1L
                            chapterDragFrac = null
                        }
                    )
                    // ── Band 2, lower half: the whole book as a PICTURE, not a second control.
                    // This replaces a stacked time row + "Book 34% ⌄" expander + a second time
                    // row. One tick per chapter, the current chapter's segment lit, everything
                    // before it dimmed — so "where am I in the book" is readable at a glance and
                    // still tappable to seek, without a second slider competing with the first.
                    Spacer(Modifier.height(4.dp))
                    BookTickTrack(
                        bookPos = bookPos,
                        bookTotal = bookTotal,
                        timeline = chapterTimeline,
                        accent = accent,
                        trackColor = trackColor
                    )
                    Spacer(Modifier.height(5.dp))
                    ThreeUpTimeRow(
                        left = formatDuration(chDisplayPos),
                        center = if (bookTotal > 0) "${formatDurationHuman(bookTotal - bookPos)} left in book" else "",
                        right = "-${formatDuration(chDur - chDisplayPos)}",
                        color = onScrimMuted
                    )
                } else {
                    val liveFrac = if (bookTotal > 0) (bookPos.toFloat() / bookTotal).coerceIn(0f, 1f) else 0f
                    val bookDisplayFrac = bookDragFrac ?: liveFrac
                    val bookDisplayPos = (bookDisplayFrac * bookTotal).toLong()
                    ImmersiveScrubber(
                        fraction = bookDisplayFrac,
                        accent = accent,
                        trackColor = trackColor,
                        onScrubStart = { bookScrubStartMs = bookPos },
                        onScrub = { f -> bookDragFrac = f },
                        onScrubEnd = { f ->
                            val target = (f * bookTotal).toLong()
                            viewModel.bookSeekTo(target)
                            if (bookScrubStartMs >= 0L)
                                viewModel.onScrubSeek(bookScrubStartMs, target)
                            bookScrubStartMs = -1L
                            bookDragFrac = null
                        }
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
                    if (chapterNav.count > 1) {
                        val enabled = serviceHasBook && chapterNav.hasPrev
                        HapticIconButton(
                            onClick = { viewModel.prevChapter() },
                            enabled = enabled,
                            feel = Feel.Transport,
                            modifier = Modifier.expandReveal(expandProgress)
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Previous chapter", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                    Box(Modifier.expandReveal(expandProgress)) {
                        SkipButton(seconds = (skipBackMs / 1000).toInt(), forward = false, tint = onScrim,
                            onLongPress = { skipEditForward = false }) { viewModel.skipBack() }
                    }
                    // Transport, not Tap: play/pause is the control this app exists to offer, and
                    // it is the one press worth giving weight to.
                    PressFeel(Feel.Transport) {
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
                    }
                    Box(Modifier.expandReveal(expandProgress)) {
                        SkipButton(seconds = (skipForwardMs / 1000).toInt(), forward = true, tint = onScrim,
                            onLongPress = { skipEditForward = true }) { viewModel.skipForward() }
                    }
                    if (chapterNav.count > 1) {
                        val enabled = serviceHasBook && chapterNav.hasNext
                        HapticIconButton(
                            onClick = { viewModel.nextChapter() },
                            enabled = enabled,
                            feel = Feel.Transport,
                            modifier = Modifier.expandReveal(expandProgress)
                        ) {
                            Icon(Icons.Default.SkipNext, "Next chapter", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                }
                } // end if (!isLocked) (transport)

                Spacer(Modifier.height(14.dp))

                // ── Secondary actions ───────────────────────────────────
                if (!isLocked) {
                // ── Band 4: one utility rail ────────────────────────
                // These four are one set of book-level utilities, laid out as four equal slots
                // across the width. They used to sit inside a cover-glass pill, but that pill drew
                // a hard edge across an otherwise edgeless sheet; the glyphs alone carry the set,
                // and an active slot still tints its own ground in accent, which is the same state
                // signal the "Skip silence" label used to carry in words.
                val skipSilenceOn = book?.skipSilenceEnabled == true
                val sleepOn = position.sleepTimerRemainingMs > 0L
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = 6.dp)
                        .expandReveal(expandProgress),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // The same waveform-with-the-gap-closed glyph the Material You player uses, so
                    // the control reads identically across themes.
                    UtilitySlot(
                        icon = painterResource(R.drawable.ic_skip_silence),
                        label = "Skip silence",
                        active = skipSilenceOn,
                        accent = accent,
                        idleTint = onScrimMuted,
                        onClick = { viewModel.setSkipSilenceEnabled(!skipSilenceOn) },
                        onLongClick = { showSkipSilenceSettings = true }
                    )
                    UtilitySlot(
                        icon = rememberVectorPainter(Icons.Default.Tune),
                        label = "Audio settings",
                        active = false,
                        accent = accent,
                        idleTint = onScrim,
                        onClick = { showAudioSettings = true }
                    )
                    UtilitySlot(
                        icon = rememberVectorPainter(Icons.Default.Bookmark),
                        label = "Bookmarks",
                        active = false,
                        accent = accent,
                        idleTint = onScrim,
                        onClick = { showBookmarks = true }
                    )
                    // Tap starts a timer at the slider's set duration (or cancels one already
                    // running); long-press opens the full options (slider/custom entry/end-of-
                    // chapter/fade/shake/schedule).
                    UtilitySlot(
                        icon = rememberVectorPainter(Icons.Default.Bedtime),
                        label = "Sleep timer",
                        active = sleepOn,
                        accent = accent,
                        idleTint = onScrim,
                        trailing = if (sleepOn) formatDuration(position.sleepTimerRemainingMs) else null,
                        onClick = {
                            if (sleepOn) viewModel.playerController.setSleepTimer(0L)
                            else viewModel.playerController.setSleepTimer(sleepTimerMinutes * 60_000L)
                        },
                        onLongClick = { showSleepTimer = true }
                    )
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
                    HapticTextButton(onClick = {
                        viewModel.addBookmark(bookmarkComment)
                        bookmarkComment = ""
                        showAddBookmark = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    HapticTextButton(onClick = { showAddBookmark = false; bookmarkComment = "" }) {
                        Text("Cancel")
                    }
                }
            )
        }

        ChapterOverlay(
            visible = showChapters && chapters.rows.isNotEmpty(),
            rows = chapters.rows,
            activeRowIndex = remember(chapters, viewModel.bookId, cur) {
                activeChapterRowIndex(chapters.rows, viewModel.bookId, cur)
            },
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
                onSearchOnlineCover = { showBookOptions = false; viewModel.openCoverSearch() },
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

        if (coverSearchOpen) {
            com.betteraudio.ui.home.CoverSearchSheet(
                initialQuery = bwp?.book?.let {
                    listOf(it.displayTitle, it.displayAuthor).filter(String::isNotBlank).joinToString(" ")
                } ?: "",
                onSearch = { query -> viewModel.searchCovers(query) },
                onPick = { url -> viewModel.setCoverFromUrl(url) },
                onDismiss = { viewModel.closeCoverSearch() }
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
            HapticSlider(
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

/**
 * The whole book as one hairline: a tick per chapter, everything already listened dimmed, and the
 * chapter you are currently in lit. Tappable to seek anywhere in the book.
 *
 * This replaces [CompactBookProgress]'s labelled row + expandable second slider. The old control
 * asked for two taps to reveal a scrubber that duplicated the chapter slider directly above it;
 * this says the same thing — where you are, how much is left, how the book is divided — without
 * being a control that competes with the one your thumb is already on.
 */
@Composable
private fun BookTickTrack(
    bookPos: Long,
    bookTotal: Long,
    timeline: com.betteraudio.playback.ChapterTimeline,
    accent: Color,
    trackColor: Color
) {
    if (bookTotal <= 0L) return
    val inkTick = Color.Black.copy(alpha = 0.62f)
    val cur = timeline.chapterAt(bookPos)
    val curStart = cur?.startMs ?: 0L
    val curEnd = cur?.endMs ?: bookTotal
    // Deliberately NOT tappable. This is a picture of where you are in the book, not a second
    // control — the chapter slider directly above it is the thing your thumb reaches for, and a
    // 2.5dp line sitting right under it was far too easy to hit by accident and jump the book.
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
    ) {
        val h = 2.5.dp.toPx()
        val y = (size.height - h) / 2f
        fun x(ms: Long) = (ms.toFloat() / bookTotal).coerceIn(0f, 1f) * size.width

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, y),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        // Everything already played, quietly.
        drawRoundRect(
            color = accent.copy(alpha = 0.38f),
            topLeft = Offset(0f, y),
            size = Size(x(bookPos), h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        // The chapter you are in, at full strength — this is what makes position legible.
        val litStart = x(curStart)
        val litWidth = (x(curEnd) - litStart).coerceAtLeast(h)
        drawRoundRect(
            color = accent,
            topLeft = Offset(litStart, y),
            size = Size(litWidth, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        // Chapter boundaries, drawn 2px wide so a split actually reads as a split rather than a
        // hairline artefact. A book with a lot of chapters counts in 2s, 3s or more instead of
        // trying to draw every one: past a certain density the ticks stop being information and
        // become a grey bar. The stride is chosen so roughly TARGET_TICKS marks survive, which
        // keeps the divisions legible at any chapter count while still showing the book's shape.
        val tickWidth = 2f
        val stride = ((timeline.marks.size + TARGET_TICKS - 1) / TARGET_TICKS).coerceAtLeast(1)
        // Even after striding, refuse to place two ticks closer than their own width plus a gap.
        val minGap = tickWidth * 3f
        var lastX = -minGap
        timeline.marks.forEachIndexed { index, mark ->
            if (mark.startMs <= 0L) return@forEachIndexed
            if (index % stride != 0) return@forEachIndexed
            val tx = x(mark.startMs)
            if (tx - lastX < minGap) return@forEachIndexed
            lastX = tx
            drawRect(color = inkTick, topLeft = Offset(tx, y), size = Size(tickWidth, h))
        }
    }
}

/** How many chapter ticks the book track aims to show before it starts counting in 2s, 3s, … */
private const val TARGET_TICKS = 24

/**
 * The Immersive player's scrubber.
 *
 * What it replaces was a stock Material 3 `Slider`: a grey inactive track, a flat accent active
 * track and the pill thumb every Material app on the phone has. It is a perfectly good control and
 * it looks like nothing else in this theme — every other surface here is glass over the book's own
 * artwork, tinted with the book's own colour.
 *
 * So this is drawn instead:
 *
 *  - **A rail, not a bar.** The unplayed side is a hairline of the same white-at-low-alpha the
 *    glass edges use, so it belongs to the surface rather than sitting on it.
 *  - **The played side deepens as it goes.** The fill is a gradient anchored to the *full* width
 *    and clipped to the current position, so early in a chapter it is a pale wash of the accent and
 *    by the end it is the accent at full strength. Progress reads as colour gaining weight, which
 *    is legible from the corner of the eye in a way a bar's length is not.
 *  - **A bead, not a thumb.** The playhead is a slim vertical capsule standing proud of the rail
 *    with a soft accent glow behind it — it looks lit from the artwork, and it does not cover the
 *    track the way a 20dp circle does.
 *  - **It swells under the thumb.** Touch it and the rail thickens, the bead grows and the glow
 *    brightens on a spring; let go and it settles back. The control acknowledges the touch instead
 *    of just following it.
 *
 * Touch handling is deliberately not Material's: the whole 28dp height is the target (the rail
 * itself is 6dp — far too thin to hit), a tap anywhere seeks there, and a drag scrubs
 * continuously. [onScrubEnd] carries the final fraction, so the caller never has to read back the
 * drag state it just wrote.
 */
@Composable
private fun ImmersiveScrubber(
    fraction: Float,
    accent: Color,
    trackColor: Color,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    // 0 at rest, 1 while the thumb is down — drives every dimension below at once, so the swell
    // reads as one object reacting rather than four properties animating.
    val swell by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "scrubberSwell"
    )
    val haptics = LocalHaptics.current
    // Detents for a control with no steps of its own. Coarse enough that a slow drag has grain
    // rather than a hum, fine enough that a fast one still reports distance travelled.
    val detents = 40
    var lastDetent by remember { mutableIntStateOf(Int.MIN_VALUE) }
    val f = fraction.coerceIn(0f, 1f)
    // The design is a user choice; the gesture, the swell and the hit target are not. Everything
    // above this line is shared by all four, everything below is one call into ScrubberArt.
    val style = com.betteraudio.ui.immersive.components.LocalScrubberStyle.current
    // Both gesture blocks below are keyed on `Unit`, so they are started ONCE and keep running
    // across every later recomposition — which means whatever they captured directly, they keep.
    // These callbacks close over the CURRENT CHAPTER (`cur`/`chDur` at the call site), so a
    // directly-captured `onScrubEnd` kept mapping the drag into whichever chapter happened to be
    // playing when the block started: pick a new chapter from the list, drag this scrubber, and
    // playback jumped back into the old one — the scrubber, the pill and the time row all showing
    // the new chapter the whole time. Reading them through `rememberUpdatedState` keeps the
    // gesture coroutine alive (re-keying it would cancel an in-flight drag) while still calling
    // the latest lambda. The Material sliders never had this: `Slider` does the same internally.
    val latestScrubStart = rememberUpdatedState(onScrubStart)
    val latestScrub = rememberUpdatedState(onScrub)
    val latestScrubEnd = rememberUpdatedState(onScrubEnd)

    Canvas(
        modifier
            .fillMaxWidth()
            // Each design declares the height it needs — Horizon's curve wants room the hairline
            // does not — and the 28dp floor keeps every one of them a comfortable target.
            .height(style.height)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val target = (offset.x / size.width).coerceIn(0f, 1f)
                    haptics.play(Feel.Select)
                    latestScrubStart.value()
                    latestScrub.value(target)
                    latestScrubEnd.value(target)
                }
            }
            .pointerInput(Unit) {
                // Tracked here rather than in composition: the drag callbacks need the latest
                // value synchronously on release, and a recomposition may not have run yet.
                var latest = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        latest = (offset.x / size.width).coerceIn(0f, 1f)
                        dragging = true
                        lastDetent = (latest * detents).toInt()
                        haptics.play(Feel.Grab)
                        latestScrubStart.value()
                        latestScrub.value(latest)
                    },
                    onDragEnd = { dragging = false; haptics.play(Feel.Release); latestScrubEnd.value(latest) },
                    onDragCancel = { dragging = false; haptics.play(Feel.Release); latestScrubEnd.value(latest) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        latest = (change.position.x / size.width).coerceIn(0f, 1f)
                        val detent = (latest * detents).toInt()
                        if (detent != lastDetent) {
                            lastDetent = detent
                            // The ends are walls, not notches: running out of chapter should feel
                            // different from crossing into the next tenth of it.
                            if (latest <= 0.0005f || latest >= 0.9995f) haptics.play(Feel.Boundary)
                            else haptics.play(Feel.Step)
                        }
                        latestScrub.value(latest)
                    }
                )
            }
    ) {
        drawScrubber(style, f, swell, accent, trackColor)
    }
}

/** Elapsed, a dim whole-book figure, and time left — one row where there used to be two. */
@Composable
private fun ThreeUpTimeRow(left: String, center: String, right: String, color: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(left, style = MaterialTheme.typography.labelMedium, color = color)
        if (center.isNotEmpty()) {
            Text(
                center,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(right, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** One slot of the band-4 utility rail. Active slots tint their own ground rather than
 *  announcing themselves with a text label. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UtilitySlot(
    icon: Painter,
    label: String,
    active: Boolean,
    accent: Color,
    idleTint: Color,
    onClick: () -> Unit,
    trailing: String? = null,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .clip(Pill)
            .background(if (active) accent.copy(alpha = 0.24f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, label, Modifier.size(20.dp), tint = if (active) accent else idleTint)
        if (trailing != null) {
            Spacer(Modifier.width(5.dp))
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = accent)
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
    val haptics = LocalHaptics.current
    PressFeel(Feel.Transport) {
    Box(
        Modifier.size(56.dp).clip(Pill)
            .combinedClickable(
                onClick = onClick,
                // combinedClickable does not haptic on its own, and a long press that opens an
                // editor with no confirmation of the threshold is the classic "did that work?"
                onLongClick = { haptics.longPress(); onLongPress() }
            ),
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
        confirmButton = { HapticTextButton(onClick = { onConfirm(secs) }) { Text("Save") } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
