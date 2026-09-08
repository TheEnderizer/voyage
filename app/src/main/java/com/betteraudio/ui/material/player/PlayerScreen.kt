package com.betteraudio.ui.material.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.betteraudio.ui.companion.companionMorphSource
import com.betteraudio.ui.components.frostedWhenVisible
import com.betteraudio.ui.history.BookHistoryOverlay
import com.betteraudio.ui.isLandscapeWindow
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.home.PlaybackOptions
import com.betteraudio.ui.material.coverCropMorph
import com.betteraudio.ui.material.motion.LocalVoyageMotion
import com.betteraudio.ui.player.AudioSettingsSheet
import com.betteraudio.ui.player.BookmarkSheet
import com.betteraudio.ui.player.ChapterOverlay
import com.betteraudio.ui.player.ChapterRow
import com.betteraudio.ui.player.LocalPlayerExpand
import com.betteraudio.ui.player.LockOverlay
import com.betteraudio.ui.player.PlayerViewModel
import com.betteraudio.ui.player.activeChapterRowIndex
import com.betteraudio.ui.player.SkipSilenceSettingsSheet
import com.betteraudio.ui.player.SleepTimerSheet
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.zIndex
import com.betteraudio.ui.player.PlayerChoreography
import com.betteraudio.ui.player.elementMotion
import com.betteraudio.ui.player.rememberUncoverPoint
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.theme.Pill
import java.io.File
import com.betteraudio.ui.haptics.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerContent(
    onCollapse: () -> Unit,
    startPlaying: Boolean = true,
    onOpenReader: (bookId: Long, fromSync: Boolean) -> Unit = { _, _ -> },
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
    // rememberSaveable, unlike the other overlay flags on this screen: those are sheets and
    // dialogs a rotation can reasonably dismiss, while the deck is a full screen the user
    // was reading. Rotating a phone to get a wider cast grid should give them the wider grid,
    // not throw them back to the player.
    var showCompanion      by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    // The companion deck's morph driver, owned here rather than inside the deck: the deck travels
    // FROM this screen's transport row, so both ends of the animation have to read one number.
    // Spatial spring, not a tween — it is the same class of movement as the mini-bar morph.
    val deckProgress = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (showCompanion) 1f else 0f,
        animationSpec = com.betteraudio.ui.theme.MotionTokens.floatSpatial,
        label = "companionDeck"
    )
    val deckMorph = com.betteraudio.ui.companion.rememberCompanionMorphAnchors()
    val coverSearchOpen    by viewModel.coverSearchOpen.collectAsStateWithLifecycle()
    var showSleepTimer     by remember { mutableStateOf(false) }
    var showSkipSilenceSettings by remember { mutableStateOf(false) }
    var showBookmarks      by remember { mutableStateOf(false) }
    var showAddBookmark    by remember { mutableStateOf(false) }
    var bookmarkComment    by remember { mutableStateOf("") }
    var showAudioSettings  by remember { mutableStateOf(false) }
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
    // ── Morph choreography (Material You portrait only) — ui/player/ElementMotion.kt ──
    // The four elements that morph out of the mini player share one downward dip on the driver
    // spring's overshoot; the transport buttons are revealed by the play button rather than fading.
    val playRect = remember { mutableStateOf(Rect.Zero) }
    val skipFwdRect = remember { mutableStateOf(Rect.Zero) }
    val nextChRect = remember { mutableStateOf(Rect.Zero) }
    val skipFwdReveal = rememberUncoverPoint(skipFwdRect, expand.miniControls, playRect, 0.70f)
    val nextChReveal = rememberUncoverPoint(nextChRect, expand.miniControls, playRect, 0.40f)
    val expandProgress = expand.progress
    // Stable "available space" rect for the cover's parent Box — used by coverCropMorph as the
    // progress-1 target; doesn't change as the cover's own animated size changes (see
    // coverCropMorph's doc for why that stability matters).
    val coverParentBounds = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }

    // Built once, shared by the portrait top bar and the landscape body's top bar — both call
    // into the SAME PlayerTopBar (PlayerControls.kt), whose "Lock screen" item invokes this.
    val onLockPlayer: () -> Unit = {
        isLocked = true
        // A sheet hosted above LockOverlay would otherwise stay reachable while "locked" —
        // close everything first.
        showChapters = false
        showBookOptions = false
        showSleepTimer = false
        showSkipSilenceSettings = false
        showBookmarks = false
        showAddBookmark = false
        showAudioSettings = false
        showHistory = false
    }

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

        val motion = LocalVoyageMotion.current
        val accent = MaterialTheme.colorScheme.primary
        // Material You: tonal player (rounded cover card on an opaque background), standard
        // onSurface text.
        val onScrim = MaterialTheme.colorScheme.onSurface
        val onScrimMuted = MaterialTheme.colorScheme.onSurfaceVariant

        // For a book in a series, optionally show the series cover instead of the book's own.
        val useSeriesCover = inSeries && showSeriesCover && seriesCover != null
        val coverPath = when {
            useSeriesCover -> seriesCover
            else -> book?.coverArtPath
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

        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        val sliderColors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = trackColor
        )

        // Shared by both layouts' cover image — hoisted here (rather than computed inside each
        // cover Box) so portrait and landscape don't each build their own ImageRequest/cacheKey.
        val cacheKey = if (!useSeriesCover && book != null) "cover-${book.id}" else null
        val imageModel = remember(coverPath, cacheKey) {
            coverPath?.let {
                coil3.request.ImageRequest.Builder(context)
                    .data(File(it))
                    .memoryCacheKey(cacheKey)
                    .build()
            }
        }
        // State<Float> (not `by`-delegated) so the graphicsLayer below reads it deferred, at draw
        // time, instead of recomposing this whole screen on every lock-toggle animation frame.
        val lockAnim = animateFloatAsState(
            if (isLocked) 1f else 0f, motion.effectsDefault, label = "lockAnim"
        )

        // Background is provided by PlayerSheet's expandingContainer, which grows out of the
        // mini bar's pill instead of fading in — no dim/solidify overlay needed here.
        Box(
            Modifier
                .fillMaxSize()
                .frostedWhenVisible(showHistory || showChapters || showCompanion)
        ) {
            if (isLandscapeWindow()) {
                // Two landscape layouts, one portrait — the user picks in Settings → Theme.
                // Both are called from HERE, inside the same Box, so LocalPlayerExpand still
                // resolves to PlayerSheet's instance and the mini-bar morph is untouched by the
                // choice; switching styles is purely a swap of which body composes.
                val landscapeStyle by viewModel.landscapePlayerStyle.collectAsStateWithLifecycle()
                if (landscapeStyle == LandscapePlayerStyle.STAGE) {
                    PlayerLandscapeStageBody(
                        padding = padding,
                        book = book,
                        author = effectiveAuthor,
                        inSeries = inSeries,
                        showSeriesCover = showSeriesCover,
                        isPlaying = state.isPlaying,
                        serviceHasBook = serviceHasBook,
                        bookPos = bookPos,
                        bookTotal = bookTotal,
                        cur = cur,
                        hasMultipleChapters = chapterTimeline.hasMultiple,
                        chapterNavCount = chapterNav.count,
                        hasPrevChapter = chapterNav.hasPrev,
                        hasNextChapter = chapterNav.hasNext,
                        positionStack = positionStack,
                        hasJumpRestore = jumpRestore != null,
                        skipForwardMs = skipForwardMs,
                        skipBackMs = skipBackMs,
                        sleepTimerRemainingMs = position.sleepTimerRemainingMs,
                        isLocked = isLocked,
                        coverModel = imageModel,
                        expand = expand,
                        coverParentBounds = coverParentBounds,
                        lockAnim = lockAnim,
                        sliderColors = sliderColors,
                        accent = accent,
                        onScrim = onScrim,
                        onScrimMuted = onScrimMuted,
                        trackColor = trackColor,
                        onBack = onBack,
                        onBookOptions = { showBookOptions = true },
                        onAddBookmark = { showAddBookmark = true },
                        onToggleSeriesCover = { viewModel.toggleShowSeriesCover() },
                        onHistory = { showHistory = true },
                        onReadFromHere = { viewModel.readFromHere { bookId -> onOpenReader(bookId, true) } },
                        onRefreshCoverEffect = { viewModel.refreshCoverEffect() },
                        onLock = onLockPlayer,
                        onOpenCompanion = { showCompanion = true },
                        onOpenChapters = { showChapters = true },
                        onPlayPause = { if (!serviceHasBook) viewModel.play() else viewModel.togglePlayPause() },
                        onSkipForward = { viewModel.skipForward() },
                        onSkipBack = { viewModel.skipBack() },
                        onEditSkip = { forward -> skipEditForward = forward },
                        onPrevChapter = { viewModel.prevChapter() },
                        onNextChapter = { viewModel.nextChapter() },
                        onSeekBook = { viewModel.bookSeekTo(it) },
                        onScrubSeek = { from, to -> viewModel.onScrubSeek(from, to) },
                        onReturnJump = { viewModel.returnFromJump() },
                        onReturnToIndex = { viewModel.returnToIndex(it) },
                        onConfirmPosition = { viewModel.confirmPosition() },
                        onRestoreJump = { jumpRestore?.let { viewModel.restoreFromJump(it.preJumpBookPosMs) } },
                        onDismissJumpRestore = { viewModel.dismissJumpRestore() },
                        onToggleSkipSilence = { viewModel.setSkipSilenceEnabled(book?.skipSilenceEnabled != true) },
                        onSkipSilenceLongPress = { showSkipSilenceSettings = true },
                        onAudioSettings = { showAudioSettings = true },
                        onBookmarksClick = { showBookmarks = true },
                        onSleepTap = {
                            if (position.sleepTimerRemainingMs > 0L) {
                                viewModel.playerController.setSleepTimer(0L)
                            } else {
                                viewModel.playerController.setSleepTimer(sleepTimerMinutes * 60_000L)
                            }
                        },
                        onSleepLongPress = { showSleepTimer = true }
                    )
                    return@Box
                }
                PlayerLandscapeBody(
                    padding = padding,
                    book = book,
                    author = effectiveAuthor,
                    inSeries = inSeries,
                    showSeriesCover = showSeriesCover,
                    isPlaying = state.isPlaying,
                    serviceHasBook = serviceHasBook,
                    bookPos = bookPos,
                    bookTotal = bookTotal,
                    cur = cur,
                    hasMultipleChapters = chapterTimeline.hasMultiple,
                    chapterNavCount = chapterNav.count,
                    hasPrevChapter = chapterNav.hasPrev,
                    hasNextChapter = chapterNav.hasNext,
                    positionStack = positionStack,
                    hasJumpRestore = jumpRestore != null,
                    skipForwardMs = skipForwardMs,
                    skipBackMs = skipBackMs,
                    sleepTimerRemainingMs = position.sleepTimerRemainingMs,
                    sleepTimerMinutes = sleepTimerMinutes,
                    isLocked = isLocked,
                    coverModel = imageModel,
                    expand = expand,
                    coverParentBounds = coverParentBounds,
                    lockAnim = lockAnim,
                    sliderColors = sliderColors,
                    accent = accent,
                    onScrim = onScrim,
                    onScrimMuted = onScrimMuted,
                    trackColor = trackColor,
                    onBack = onBack,
                    onBookOptions = { showBookOptions = true },
                    onAddBookmark = { showAddBookmark = true },
                    onToggleSeriesCover = { viewModel.toggleShowSeriesCover() },
                    onHistory = { showHistory = true },
                    onReadFromHere = { viewModel.readFromHere { bookId -> onOpenReader(bookId, true) } },
                    onRefreshCoverEffect = { viewModel.refreshCoverEffect() },
                    onLock = onLockPlayer,
                    onOpenCompanion = { showCompanion = true },
                    onOpenChapters = { showChapters = true },
                    onPlayPause = { if (!serviceHasBook) viewModel.play() else viewModel.togglePlayPause() },
                    onSkipForward = { viewModel.skipForward() },
                    onSkipBack = { viewModel.skipBack() },
                    onEditSkip = { forward -> skipEditForward = forward },
                    onPrevChapter = { viewModel.prevChapter() },
                    onNextChapter = { viewModel.nextChapter() },
                    onSeekBook = { viewModel.bookSeekTo(it) },
                    onScrubSeek = { from, to -> viewModel.onScrubSeek(from, to) },
                    onReturnJump = { viewModel.returnFromJump() },
                    onReturnToIndex = { viewModel.returnToIndex(it) },
                    onConfirmPosition = { viewModel.confirmPosition() },
                    onRestoreJump = { jumpRestore?.let { viewModel.restoreFromJump(it.preJumpBookPosMs) } },
                    onDismissJumpRestore = { viewModel.dismissJumpRestore() },
                    onToggleSkipSilence = { viewModel.setSkipSilenceEnabled(book?.skipSilenceEnabled != true) },
                    onSkipSilenceLongPress = { showSkipSilenceSettings = true },
                    onAudioSettings = { showAudioSettings = true },
                    onBookmarksClick = { showBookmarks = true },
                    onSleepTap = {
                        if (position.sleepTimerRemainingMs > 0L) {
                            viewModel.playerController.setSleepTimer(0L)
                        } else {
                            viewModel.playerController.setSleepTimer(sleepTimerMinutes * 60_000L)
                        }
                    },
                    onSleepLongPress = { showSleepTimer = true }
                )
                return@Box
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
            ) {
                // ── Top bar (shared with the landscape body — PlayerControls.kt) ────────
                PlayerTopBar(
                    motion = PlayerChoreography.topBar,
                    seriesLabel = book?.seriesName?.takeIf { it.isNotBlank() },
                    inSeries = inSeries,
                    showSeriesCover = showSeriesCover,
                    hasEbook = book?.ebookPath != null,
                    onScrimMuted = onScrimMuted,
                    expandProgress = expandProgress,
                    onBack = onBack,
                    onBookOptions = { showBookOptions = true },
                    onAddBookmark = { showAddBookmark = true },
                    onToggleSeriesCover = { viewModel.toggleShowSeriesCover() },
                    onHistory = { showHistory = true },
                    onReadFromHere = { viewModel.readFromHere { bookId -> onOpenReader(bookId, true) } },
                    onRefreshCoverEffect = { viewModel.refreshCoverEffect() },
                    onLock = onLockPlayer
                )

                // ── Large rounded cover card in the leftover space — the SAME element that
                // travels out of the mini player's cover slot (it stays as the player cover
                // instead of dissolving into a full-bleed backdrop). Grows a touch on lock (see
                // lockAnim below) as a quiet cue that the transport controls beneath it have
                // stepped aside. ──
                // TopStart (not Center): when morphing from a grid card, coverCropMorph positions
                // the cover with an absolute offset computed from this Box's own top-left, so any
                // implicit centering here would double up with that math.
                Box(
                    Modifier
                        .weight(1f)
                        // Above the controls column: the chapter pill starts behind this cover and
                        // drops out from under it, which only works if the cover paints on top.
                        .zIndex(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .onGloballyPositioned { coverParentBounds.value = it.boundsInRoot() }
                        .graphicsLayer {
                            val s = 1f + 0.05f * lockAnim.value
                            scaleX = s
                            scaleY = s
                        },
                    contentAlignment = Alignment.TopStart
                ) {
                    if (expand.sourceIsGridCard) {
                        // Grid card → this full player, opened directly with no live mini bar
                        // (e.g. Book Info's Resume button, or a fresh unplayed book): aspect-aware
                        // crop morph (see MaterialMotion.kt) so the same crop window the grid card
                        // shows continuously resizes into the natural square crop — no reload, no
                        // aspect "pop". Not Book Info's own cover — that's a separate morphFrom-
                        // based morph in BookInfoScreen.kt.
                        AsyncImage(
                            model = imageModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .coverCropMorph(
                                    parentBounds = coverParentBounds,
                                    source = expand.miniCover,
                                    progress = expandProgress,
                                    sourceRadius = expand.coverSourceRadius,
                                    destRadius = 28.dp
                                )
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                    } else {
                        // Mini-bar → full player: the mini cover is already near-square, so the
                        // simple uniform morph reads fine.
                        AsyncImage(
                            model = imageModel,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                // Largest square that fits the leftover space.
                                .aspectRatio(1f)
                                .elementMotion(
                                    PlayerChoreography.cover, expandProgress,
                                    source = expand.miniCover
                                )
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                    }
                }

                // ── Player controls ─────────────────────────────────────────────────
                Column(Modifier.fillMaxWidth()) {

                // ── Bottom control cluster ──────────────────────────────
                if (!isLocked && chapterTimeline.hasMultiple && cur != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .elementMotion(PlayerChoreography.chapterPill, expandProgress)
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
                    text = book?.displayTitle ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    color = onScrim,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Moves/enlarges out of the mini player's title as the sheet opens.
                    modifier = Modifier.fillMaxWidth().elementMotion(
                        PlayerChoreography.title, expandProgress,
                        source = expand.miniTitle
                    )
                )
                if (!effectiveAuthor.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = effectiveAuthor,
                        style = MaterialTheme.typography.titleSmall,
                        color = onScrimMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().elementMotion(
                            PlayerChoreography.author, expandProgress,
                            source = expand.miniAuthor
                        )
                    )
                }

                // ── Return / Confirm + unexpected-jump-restore pills (shared with the
                // landscape body — PlayerControls.kt; each is a no-op composable when it has
                // nothing to show, so no manual Spacer/if-gating is needed here). ────────────
                if (!isLocked) {
                    ReturnConfirmPills(
                        positionStack = positionStack,
                        expandProgress = expandProgress,
                        onReturn = { viewModel.returnFromJump() },
                        onReturnToIndex = { viewModel.returnToIndex(it) },
                        onConfirm = { viewModel.confirmPosition() },
                        modifier = Modifier.padding(top = 14.dp)
                    )
                    JumpRestorePill(
                        visible = jumpRestore != null,
                        expandProgress = expandProgress,
                        onRestore = { jumpRestore?.let { viewModel.restoreFromJump(it.preJumpBookPosMs) } },
                        onDismiss = { viewModel.dismissJumpRestore() },
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                if (isLocked) {
                    // ── Locked: read-only progress only, no draggable slider ────
                    TimeRow(formatDuration(bookPos), formatDuration(bookTotal), onScrimMuted)
                    Spacer(Modifier.height(6.dp))
                    CompactBookProgress(bookPos, bookTotal, accent, onScrimMuted, trackColor, readOnly = true) {}
                } else {
                // ── Scrubber (reveals as the sheet opens) ───────────────
                Column(Modifier.fillMaxWidth().elementMotion(PlayerChoreography.seekBar, expandProgress)) {
                if (chapterTimeline.hasMultiple && cur != null) {
                    val chDur = (cur.endMs - cur.startMs).coerceAtLeast(1L)
                    val livePos = (bookPos - cur.startMs).coerceIn(0L, chDur)
                    val chDisplayFrac = chapterDragFrac ?: (livePos.toFloat() / chDur).coerceIn(0f, 1f)
                    val chDisplayPos = (chDisplayFrac * chDur).toLong()
                    // The seek bar is a user choice in BOTH looks now (Settings → Theme → Seek
                    // bar); CLASSIC is this screen's original HapticSlider, unchanged and still
                    // the default here. See ui/components/VoyageScrubber.kt.
                    com.betteraudio.ui.components.VoyageScrubber(
                        fraction = chDisplayFrac,
                        accent = accent,
                        trackColor = trackColor,
                        sliderColors = sliderColors,
                        onScrubStart = { chapterScrubStartMs = bookPos },
                        onScrub = { f -> chapterDragFrac = f },
                        onScrubEnd = { f ->
                            val target = cur.startMs + (f * chDur).toLong()
                            viewModel.bookSeekTo(target)
                            if (chapterScrubStartMs >= 0L)
                                viewModel.onScrubSeek(chapterScrubStartMs, target)
                            chapterScrubStartMs = -1L
                            chapterDragFrac = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeRow(formatDuration(chDisplayPos), "-${formatDuration(chDur - chDisplayPos)}", onScrimMuted)
                    Spacer(Modifier.height(2.dp))
                    CompactBookProgress(bookPos, bookTotal, accent, onScrimMuted, trackColor) { target ->
                        val before = bookPos
                        viewModel.bookSeekTo(target)
                        viewModel.onScrubSeek(before, target)
                    }
                } else {
                    val liveFrac = if (bookTotal > 0) (bookPos.toFloat() / bookTotal).coerceIn(0f, 1f) else 0f
                    val bookDisplayFrac = bookDragFrac ?: liveFrac
                    val bookDisplayPos = (bookDisplayFrac * bookTotal).toLong()
                    com.betteraudio.ui.components.VoyageScrubber(
                        fraction = bookDisplayFrac,
                        accent = accent,
                        trackColor = trackColor,
                        sliderColors = sliderColors,
                        onScrubStart = { bookScrubStartMs = bookPos },
                        onScrub = { f -> bookDragFrac = f },
                        onScrubEnd = { f ->
                            val target = (f * bookTotal).toLong()
                            viewModel.bookSeekTo(target)
                            if (bookScrubStartMs >= 0L)
                                viewModel.onScrubSeek(bookScrubStartMs, target)
                            bookScrubStartMs = -1L
                            bookDragFrac = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TimeRow(formatDuration(bookDisplayPos), formatDuration(bookTotal), onScrimMuted)
                }
                } // end Column (scrubber reveal)
                } // end else (unlocked scrubber)

                Spacer(Modifier.height(10.dp))

                // ── Transport + secondary actions — "the music controls" — slide down and off
                // the bottom together as one unit when locking (Material You only; see the lock
                // param block below LockOverlay's call site). Title/author and the progress
                // readout above are NOT part of this group, so they never move. ──
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isLocked,
                    enter = androidx.compose.animation.fadeIn() +
                        androidx.compose.animation.expandVertically(expandFrom = Alignment.Top),
                    exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) +
                        androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) +
                        androidx.compose.animation.fadeOut()
                ) {
                Column(Modifier.fillMaxWidth()) {
                // ── Transport — the play button GROWS out of the mini player's accent play
                // button (same round accent visual); the skip controls reveal around it. ──
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Where the companion deck's dock comes from, and what hands off to it.
                        .companionMorphSource(deckMorph, deckProgress),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (chapterNav.count > 1) {
                        val enabled = serviceHasBook && chapterNav.hasPrev
                        HapticIconButton(
                            onClick = { viewModel.prevChapter() },
                            enabled = enabled,
                            modifier = Modifier.elementMotion(
                                PlayerChoreography.prevChapter, expandProgress, playRect = playRect
                            )
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Previous chapter", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                    Box(Modifier.elementMotion(
                        PlayerChoreography.skipBack, expandProgress, playRect = playRect
                    )) {
                        SkipButton(seconds = (skipBackMs / 1000).toInt(), forward = false, tint = onScrim,
                            onLongPress = { skipEditForward = false }) { viewModel.skipBack() }
                    }
                    Box(
                        Modifier
                            // Above its siblings: it has to occlude the buttons it deposits.
                            .zIndex(1f)
                            // Reported BEFORE the motion modifier, so this is the resting rect the
                            // coverage sampling and the slide-out sources are measured against.
                            .onGloballyPositioned { playRect.value = it.boundsInRoot() }
                            .elementMotion(
                                PlayerChoreography.play, expandProgress,
                                source = expand.miniControls
                            )
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
                    Box(Modifier
                        .onGloballyPositioned { skipFwdRect.value = it.boundsInRoot() }
                        .elementMotion(
                            PlayerChoreography.skipForward, expandProgress, visibleFrom = skipFwdReveal
                        )) {
                        SkipButton(seconds = (skipForwardMs / 1000).toInt(), forward = true, tint = onScrim,
                            onLongPress = { skipEditForward = true }) { viewModel.skipForward() }
                    }
                    if (chapterNav.count > 1) {
                        val enabled = serviceHasBook && chapterNav.hasNext
                        HapticIconButton(
                            onClick = { viewModel.nextChapter() },
                            enabled = enabled,
                            modifier = Modifier
                                .onGloballyPositioned { nextChRect.value = it.boundsInRoot() }
                                .elementMotion(
                                    PlayerChoreography.nextChapter, expandProgress,
                                    visibleFrom = nextChReveal
                                )
                        ) {
                            Icon(Icons.Default.SkipNext, "Next chapter", Modifier.size(26.dp),
                                tint = if (enabled) onScrim else onScrimMuted.copy(alpha = 0.4f))
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Secondary actions (shared with the landscape body — PlayerControls.kt) ──
                PlayerSecondaryActionsRow(
                    motion = PlayerChoreography.secondaryRow,
                    skipSilenceOn = book?.skipSilenceEnabled == true,
                    accent = accent,
                    onScrim = onScrim,
                    onScrimMuted = onScrimMuted,
                    sleepRemainingMs = position.sleepTimerRemainingMs,
                    expandProgress = expandProgress,
                    onToggleSkipSilence = { viewModel.setSkipSilenceEnabled(book?.skipSilenceEnabled != true) },
                    onSkipSilenceLongPress = { showSkipSilenceSettings = true },
                    onAudioSettings = { showAudioSettings = true },
                    onBookmarks = { showBookmarks = true },
                    onOpenCompanion = { showCompanion = true },
                    onSleepTap = {
                        if (position.sleepTimerRemainingMs > 0L) {
                            viewModel.playerController.setSleepTimer(0L)
                        } else {
                            viewModel.playerController.setSleepTimer(sleepTimerMinutes * 60_000L)
                        }
                    },
                    onSleepLongPress = { showSleepTimer = true }
                )
                } // end Column (transport + secondary actions group)
                } // end AnimatedVisibility (music controls slide-away on lock)

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

        // Material You: the hold-to-unlock indicator appears roughly where the transport/
        // secondary-action rows sit (rather than LockOverlay's own default bottom-of-screen
        // placement), tinted to match the tonal player instead of Immersive's white-on-scrim.
        // Landscape's transport/secondary-actions vacate the right rail on lock (not the bottom
        // of the screen, as in portrait), so the hold-to-unlock indicator follows it there.
        if (isLandscapeWindow()) {
            LockOverlay(
                locked = isLocked,
                onUnlock = { isLocked = false },
                alignment = Alignment.CenterEnd,
                contentPadding = PaddingValues(end = 30.dp),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        } else {
            LockOverlay(
                locked = isLocked,
                onUnlock = { isLocked = false },
                contentPadding = PaddingValues(bottom = 90.dp),
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        }

        if (viewModel.bookId != -1L) {
            val deckTransport = com.betteraudio.ui.companion.rememberCompanionTransport(
                title = book?.displayTitle.orEmpty(),
                coverPath = coverPath,
                isPlaying = state.isPlaying,
                position = position,
                revealedMs = bwp?.progress?.revealedMs ?: 0L,
                onPlayPause = { viewModel.playerController.togglePlayPause() },
                onSkipBack = { viewModel.playerController.skipBack() },
                onSkipForward = { viewModel.playerController.skipForward() }
            )
            com.betteraudio.ui.companion.CompanionDeck(
                progress = deckProgress,
                bookId = viewModel.bookId,
                transport = deckTransport,
                onDismiss = { showCompanion = false },
                morph = deckMorph
            )
        }

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
                onConnectEpub = { path -> viewModel.connectEpub(path) },
                onDisconnectEpub = { viewModel.disconnectEpub() },
                onOpenReader = { bwp?.book?.id?.let { onOpenReader(it, false) } },
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

// CompactBookProgress, TimeRow, SkipButton, SkipValueDialog, SecondaryIcon, formatDuration and
// formatDurationHuman moved to PlayerControls.kt (shared with the landscape body).
