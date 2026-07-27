package com.betteraudio.ui.player

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import com.betteraudio.playback.PlayerController
import com.betteraudio.ui.immersive.IMMERSIVE_MINI_BAR_FADE_RATE
import com.betteraudio.ui.material.expandingContainer
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Mini bar's Pill shape radius at its actual 64dp height (RoundedCornerShape(percent = 50) on a
 *  64dp-tall bar rounds to half its height). Used as the source radius for Material You's
 *  container-growth (see [com.betteraudio.ui.material.expandingContainer]). */
private val MINI_BAR_RADIUS = 32.dp

/** Which book the expanded player should show. */
data class PlayerTarget(
    val bookId: Long = -1L,
    // false on cold-start restore so the player doesn't auto-play when the app opens
    val startPlaying: Boolean = true,
)

/**
 * Drives the persistent player sheet. Remembered at the app root and handed to every screen that
 * opens the player. The visual drag/expand lives in [PlayerSheet]; this just holds the current
 * target and forwards expand/collapse intents (wired by the sheet).
 */
@Stable
class PlayerSheetController {
    var target by mutableStateOf<PlayerTarget?>(null)
        private set
    // Bumped to request expand / collapse. The sheet observes these via LaunchedEffect, so the
    // intent survives even if it's issued before the sheet has finished composing (e.g. cold start).
    var expandToken by mutableStateOf(0)
        private set
    var collapseToken by mutableStateOf(0)
        private set
    /** Whether the sheet is currently expanded (kept in sync by the sheet). */
    var isExpanded by mutableStateOf(false)
        private set
    internal fun setExpanded(v: Boolean) { isExpanded = v }

    /** Live expand progress (0 collapsed → 1 expanded), mirrored from the sheet's drag anim.
     *  For chrome that must move in lockstep with the sheet (the floating nav pill) — read it
     *  only inside graphicsLayer/draw lambdas to avoid per-frame recomposition. */
    val expandProgress: androidx.compose.runtime.MutableFloatState =
        androidx.compose.runtime.mutableFloatStateOf(0f)

    /** Set the target without expanding (used to show the mini bar for the last-played book). */
    fun prime(bookId: Long = -1L) {
        if (target == null) target = PlayerTarget(bookId)
    }

    /** Cold-start restore of the mini bar (collapsed): load the last-played book PAUSED so the
     *  mini bar shows it even when the playback service was killed. Unlike [prime], this forces
     *  startPlaying = false so reopening the app never auto-resumes. */
    fun restore(bookId: Long) {
        if (target == null && bookId != -1L)
            target = PlayerTarget(bookId = bookId, startPlaying = false)
    }

    /** Open a book in the full player (expands the sheet). */
    fun open(bookId: Long = -1L, startPlaying: Boolean = true) {
        target = PlayerTarget(bookId, startPlaying)
        expandToken++
    }

    fun expandCurrent() { if (target != null) expandToken++ }
    fun collapse() { collapseToken++ }

    /** Material You predictive back: while a back gesture is in flight, mirrors its progress
     *  (0 = just started, 1 = fully committed) directly onto the sheet's collapse animation so it
     *  shrinks in lockstep with the finger/swipe instead of only reacting after the gesture
     *  commits. [PlayerSheet] observes this via `snapTo`, not an animated `animateTo`, so it
     *  tracks the gesture exactly. Null (the default / after [cancelSeek]) means "not seeking" —
     *  the normal expand/collapse token animations take over as usual. */
    var seekProgress by mutableStateOf<Float?>(null)
        private set
    fun seek(backGestureProgress: Float) { seekProgress = (1f - backGestureProgress).coerceIn(0f, 1f) }
    /** Gesture cancelled: stop seeking and spring back open from wherever the gesture left it. */
    fun cancelSeek() { seekProgress = null; expandCurrent() }
    /** Gesture committed: stop seeking and let the normal collapse animation finish the job from
     *  wherever the gesture left it. */
    fun commitSeek() { seekProgress = null; collapse() }

    /** Re-point the (already-open) player at a new book without re-animating — used when a series
     *  auto-advances so the full player follows into the next book. */
    fun follow(bookId: Long) {
        if (bookId != -1L && target?.bookId != bookId) {
            target = PlayerTarget(bookId = bookId, startPlaying = false)
        }
    }

    /** Drop the loaded book so the sheet (and mini bar) disappear — used when the book is closed. */
    fun clear() { target = null }
}

@Composable
fun rememberPlayerSheetController(): PlayerSheetController = remember { PlayerSheetController() }

private const val MINI_HEIGHT_DP = 64

/**
 * Persistent player surface layered over the app. Collapsed = a mini bar docked near the bottom;
 * drag up (or tap) to grow it continuously into the full blurred player; drag down / back to
 * shrink. The full content is hosted in a tiny nested NavHost so each book gets proper
 * ViewModel + SavedStateHandle scoping (no shared-element transitions involved).
 */
@Composable
fun PlayerSheet(
    controller: PlayerSheetController,
    playerController: PlayerController,
    modifier: Modifier = Modifier,
    // Hide the collapsed mini bar on screens that shouldn't show it (e.g. Settings). The expanded
    // player can't coexist with those routes, so only the collapsed bar needs gating.
    hideMiniBar: Boolean = false,
    // On routes showing the floating nav pill (home), the mini bar floats above the pill instead
    // of hugging the bottom edge.
    liftForNavPill: Boolean = false,
    // "Read from here" (player overflow) needs to collapse this sheet and navigate to the reader
    // route underneath it — that navigation lives outside the sheet's own nested NavHost.
    onOpenReader: (Long) -> Unit = {}
) {
    val playback by playerController.playbackState.collectAsStateWithLifecycle()
    // Read ONLY inside MiniPlayerBar's deferred progress lambda — reading `position` anywhere
    // in this composable's body would recompose the whole sheet on every 500ms tick.
    val position by playerController.positionState.collectAsStateWithLifecycle()
    val target = controller.target
    // Captured here (composable scope) rather than inside graphicsLayer lambdas below, since
    // CompositionLocal.current isn't safe to read from those deferred draw-phase blocks.
    val isMaterialYou = LocalAppTheme.current == AppTheme.MATERIAL_YOU

    // Mirror the playing book into the target so the mini bar is ready to expand.
    LaunchedEffect(playback.bookId) {
        if (playback.bookId != -1L) controller.prime(playback.bookId)
    }

    // Cold-start restore (PlayerSheetController.restore): `target` is set to the last-played book,
    // but PlayerController.playbackState stays empty until playback actually starts — which is why
    // the mini bar used to render blank while the full player (which loads its book straight from
    // Room) worked fine. Load that same book/progress data here as a fallback the mini bar can show
    // until real playback state takes over.
    val restoreVm: MiniPlayerRestoreViewModel = hiltViewModel()
    val usingLivePlayback = playback.bookId != -1L
    LaunchedEffect(target?.bookId, usingLivePlayback) {
        restoreVm.setBookId(if (!usingLivePlayback) target?.bookId ?: -1L else -1L)
    }
    val restoreInfo by restoreVm.info.collectAsStateWithLifecycle()

    // When a series auto-advances into the next book, follow it in the open full player.
    LaunchedEffect(Unit) {
        playerController.seriesAdvanced.collect { nextBookId ->
            if (controller.isExpanded) controller.follow(nextBookId)
        }
    }

    if (target == null && playback.bookId == -1L) return  // nothing ever played → no sheet

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val progressAnim = remember { Animatable(0f) }   // 0 = collapsed, 1 = expanded
    // The single value every visual consumer below reads. progressAnim only drives ANIMATED
    // transitions (settle, expand/collapse tokens, predictive-back seek) — a live drag writes
    // this directly and synchronously instead, so N per-delta coroutines can never race an
    // in-flight animateTo the way writing progressAnim.value from onDelta used to (AN-2).
    val dragProgress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // True for the duration of a live drag on either the mini bar or the full container. Gates
    // the mirror below so a stale/still-finishing animateTo (the interrupt case) can't stomp
    // dragProgress while a drag is in control of it.
    var isDragging by remember { mutableStateOf(false) }
    // Captured by the mini bar's onDragStarted (see below) — whether THIS drag gesture began
    // from a fully settled mini bar, which is the only case a firm downward fling may close the
    // book (see Feature 10 in the mini bar's draggable onDragStopped).
    var gestureStartedSettled by remember { mutableStateOf(true) }

    // Mirror progressAnim's animated value into dragProgress whenever an animation (not a live
    // drag) is driving progress. Silent while isDragging — see above.
    LaunchedEffect(progressAnim) {
        androidx.compose.runtime.snapshotFlow { progressAnim.value }
            .collect { if (!isDragging) dragProgress.floatValue = it }
    }
    // Mirror the live progress onto the controller so MainActivity-level chrome (the floating
    // nav pill) slides in lockstep with the sheet, including mid-drag.
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { dragProgress.floatValue }
            .collect { controller.expandProgress.floatValue = it }
    }

    // Material You predictive back: while MainActivity's PredictiveBackHandler reports gesture
    // progress via controller.seek(...), track it exactly (snapTo, not animateTo) so the sheet
    // shrinks in lockstep with the finger. Cancel/commit clear seekProgress and hand off to the
    // normal expand/collapse token animations (see PlayerSheetController.cancelSeek/commitSeek).
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { controller.seekProgress }
            .filterNotNull()
            .collect { progressAnim.snapTo(it) }
    }

    var heightPx by remember { mutableStateOf(0) }
    var widthPx by remember { mutableStateOf(0) }
    val miniPx = with(density) { MINI_HEIGHT_DP.dp.toPx() }
    // Sit the mini bar 20dp above the system navigation bar (gesture bar / button bar).
    val bottomNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navReservePx = with(density) { (bottomNavInset + 20.dp).toPx() }
    val travelPx = (heightPx - miniPx - navReservePx).coerceAtLeast(1f)
    // Material You's expandingContainer needs the full player's TRUE (unparked) size — NOT
    // measured via onGloballyPositioned on a descendant of the parked/translated container, since
    // boundsInRoot() there wasn't reliably reflecting the un-parked position (the container was
    // rendering as an opaque box sitting on top of the mini bar even while collapsed). Passing the
    // already-tracked outer size directly sidesteps that.
    val ownSizePx = remember { derivedStateOf { androidx.compose.ui.geometry.Size(widthPx.toFloat(), heightPx.toFloat()) } }

    // React to expand/collapse intents (token-based so they survive composition timing).
    LaunchedEffect(controller.expandToken) {
        if (controller.expandToken > 0)
            progressAnim.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f))
    }
    LaunchedEffect(controller.collapseToken) {
        if (controller.collapseToken > 0)
            progressAnim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 400f))
    }

    // derivedStateOf: recompose only when the threshold flips, not every animation frame.
    val expanded by remember { derivedStateOf { dragProgress.floatValue > 0.5f } }
    LaunchedEffect(expanded) { controller.setExpanded(expanded) }

    // Shared-element morph plumbing: expansion progress + the mini bar's element bounds,
    // all as State so the full player reads them inside graphicsLayer lambdas only.
    val progressState = remember { derivedStateOf { dragProgress.floatValue } }
    val miniCoverRect = remember { mutableStateOf(Rect.Zero) }
    val miniTitleRect = remember { mutableStateOf(Rect.Zero) }
    val miniControlsRect = remember { mutableStateOf(Rect.Zero) }
    // Material You only: the mini bar's own Surface bounds, so the full player's background can
    // grow out of the pill (see MaterialMotion.kt's expandingContainer) instead of crossfading.
    val miniBarRect = remember { mutableStateOf(Rect.Zero) }

    // Book Info now lives in its own overlay (see com.betteraudio.ui.bookinfo.BookInfoOverlay),
    // so the only remaining "no live mini bar" case here is opening the full player straight into
    // playback with nothing currently playing (e.g. resuming from Book Info, or a fresh book) —
    // that still needs to morph from the tapped grid card's published bounds rather than a
    // nonexistent mini bar.
    val coverBoundsRegistry = LocalCoverBoundsRegistry.current
    val sourceIsGridCard = !usingLivePlayback
    val effectiveCoverSource = remember(target?.bookId, usingLivePlayback) {
        if (sourceIsGridCard) {
            coverBoundsRegistry.boundsState(target?.bookId ?: -1L)
        } else {
            miniCoverRect
        }
    }
    val effectiveCoverRadius = remember(target?.bookId, usingLivePlayback) {
        if (sourceIsGridCard) {
            coverBoundsRegistry.radiusFor(target?.bookId ?: -1L)
        } else {
            12.dp
        }
    }
    // Tell the grid card whose cover is currently morphing so it can hide its own copy (prevents
    // seeing both the still grid card AND the traveling player cover at once).
    LaunchedEffect(sourceIsGridCard, target?.bookId) {
        coverBoundsRegistry.setActiveMorph(
            if (sourceIsGridCard) target?.bookId ?: -1L else -1L,
            progressState
        )
    }
    val transition = remember(effectiveCoverSource, effectiveCoverRadius, sourceIsGridCard) {
        PlayerExpandTransition(
            progressState, effectiveCoverSource, miniTitleRect, miniControlsRect, effectiveCoverRadius,
            miniBar = miniBarRect, miniBarRadius = MINI_BAR_RADIUS, sourceIsGridCard = sourceIsGridCard
        )
    }

    // Ends a drag gesture: hands the live value off to progressAnim with a single snapTo (not a
    // per-delta one, so it can't race — this also cancels any stale animateTo left over from an
    // interrupted settle/token animation) then lets the normal spring settle it open or closed.
    suspend fun settle(velocity: Float) {
        progressAnim.snapTo(dragProgress.floatValue)
        val goExpand = velocity < -1000f || (velocity <= 1000f && dragProgress.floatValue > 0.5f)
        progressAnim.animateTo(
            if (goExpand) 1f else 0f,
            spring(dampingRatio = 0.85f, stiffness = 380f)
        )
    }

    Box(modifier.fillMaxSize().onSizeChanged { heightPx = it.height; widthPx = it.width }) {
        // ── Mini bar — docked at the bottom, drawn UNDER the full player so the morphing
        // cover/title/controls (which start exactly on top of their mini counterparts) read as
        // the same element travelling, not a crossfade. The mini content hides the moment the
        // morph takes over; only the pill surface fades out.
        // The mini bar only exists when there's an active session (a book loaded in the service,
        // playing or paused) — e.g. opening Book Info alone (no auto-play) must not spawn a bar.
        // Cold-start `restore()` still loads the book (paused) into the service, so it satisfies
        // this and the restored mini bar keeps showing on launch.
        if (usingLivePlayback && !(hideMiniBar && !expanded)) MiniPlayerBar(
            title = when {
                usingLivePlayback -> playback.bookTitle
                else -> restoreInfo?.title.orEmpty()
            },
            coverPath = if (usingLivePlayback) playback.coverArtUri?.removePrefix("file://") else restoreInfo?.coverArtPath,
            isPlaying = usingLivePlayback && playback.isPlaying,
            progress = {
                when {
                    usingLivePlayback && position.bookTotalDurationMs > 0 ->
                        (position.bookPositionMs.toFloat() / position.bookTotalDurationMs).coerceIn(0f, 1f)
                    usingLivePlayback -> 0f
                    else -> restoreInfo?.progress ?: 0f
                }
            },
            enabled = !expanded,
            onTap = {
                if (playback.bookId != -1L) controller.open(bookId = playback.bookId)
                else controller.expandCurrent()
            },
            onPlayPause = { playerController.togglePlayPause() },
            onSkip = { playerController.skipForward() },
            onCoverBounds = { miniCoverRect.value = it },
            onTitleBounds = { miniTitleRect.value = it },
            onControlsBounds = { miniControlsRect.value = it },
            onBarBounds = { miniBarRect.value = it },
            expandProgress = progressState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    bottom = bottomNavInset + androidx.compose.animation.core.animateDpAsState(
                        // Float NAV_PILL_GAP above the pill on home; hug the bottom elsewhere.
                        if (liftForNavPill)
                            com.betteraudio.ui.components.NAV_PILL_BOTTOM_PADDING +
                                com.betteraudio.ui.components.NAV_PILL_HEIGHT +
                                com.betteraudio.ui.components.NAV_PILL_GAP
                        else 20.dp,
                        label = "miniBarLift"
                    ).value
                )
                .graphicsLayer {
                    // Material You: the pill stays fully visible — it's physically occluded by
                    // the full player's expandingContainer (same rect, grown from it) the moment
                    // the sheet starts opening, so no separate fade is needed. Immersive keeps the
                    // original crossfade look (no growing container there).
                    alpha = if (isMaterialYou) 1f
                            else (1f - dragProgress.floatValue * IMMERSIVE_MINI_BAR_FADE_RATE).coerceIn(0f, 1f)
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        // Synchronous, no coroutine — see dragProgress's declaration (AN-2).
                        dragProgress.floatValue = (dragProgress.floatValue - delta / travelPx).coerceIn(0f, 1f)
                    },
                    // Only a flick that BEGAN from a genuinely settled mini bar is eligible to
                    // close the book — otherwise a downward flick caught mid-expansion (tap to
                    // expand, then immediately flick down before the open animation finishes)
                    // would also satisfy "progress < 0.15f" at release and wrongly close it
                    // instead of just returning to the mini bar.
                    onDragStarted = {
                        isDragging = true
                        gestureStartedSettled = dragProgress.floatValue < 0.001f
                    },
                    // A firm downward fling starting from the settled mini bar closes the book
                    // (stops playback and dismisses the mini bar); otherwise settle open/closed
                    // as usual — which, for an in-flight expansion flicked back down, means
                    // returning to the mini bar rather than closing.
                    onDragStopped = { velocity ->
                        isDragging = false
                        if (velocity > 1800f && dragProgress.floatValue < 0.15f && gestureStartedSettled) {
                            playerController.stop()
                            controller.clear()
                        } else scope.launch { settle(velocity) }
                    }
                )
        )

        // ── Full player — fixed full-screen; its elements morph out of the mini bar. When
        // fully collapsed it's parked offscreen so the app underneath stays interactive.
        if (target != null) {
            val nested = rememberNavController()
            LaunchedEffect(target) {
                nested.navigate(
                    "player?bookId=${target!!.bookId}&startPlaying=${target!!.startPlaying}"
                ) {
                    // Clear the entire nested back stack so every book switch gets a fresh
                    // ViewModel. launchSingleTop is intentionally NOT set — it matches on
                    // route pattern, not the URL, so it would reuse the old book's entry.
                    popUpTo(0) { inclusive = true }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = if (dragProgress.floatValue <= 0.001f) heightPx.toFloat() else 0f
                    }
                    // draggable on the container — activates only after the touch-slop
                    // threshold, so buttons/menus inside still receive their own taps.
                    .draggable(
                        enabled = expanded,
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            // Synchronous, no coroutine — see dragProgress's declaration (AN-2).
                            dragProgress.floatValue = (dragProgress.floatValue - delta / travelPx).coerceIn(0f, 1f)
                        },
                        onDragStarted = { isDragging = true },
                        onDragStopped = { velocity ->
                            isDragging = false
                            scope.launch { settle(velocity) }
                        }
                    )
            ) {
                // Material You: the full player's background grows out of the mini bar's pill
                // instead of fading in over it — draws UNDER the NavHost content but ON TOP of
                // the mini bar (same z-order slot), so once progress > 0 it exactly occludes the
                // pill (same starting rect/radius) and visibly widens/heightens into the full
                // screen as the sheet opens. Immersive keeps its original look (no container).
                if (isMaterialYou) {
                    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    val bgColor = MaterialTheme.colorScheme.background
                    Box(
                        Modifier
                            .fillMaxSize()
                            .expandingContainer(
                                source = miniBarRect,
                                ownSizePx = ownSizePx,
                                progress = progressState,
                                sourceRadius = MINI_BAR_RADIUS,
                                destRadius = 0.dp
                            )
                            .drawBehind {
                                drawRect(
                                    androidx.compose.ui.graphics.lerp(
                                        barColor, bgColor, dragProgress.floatValue.coerceIn(0f, 1f)
                                    )
                                )
                            }
                    )
                }
                CompositionLocalProvider(LocalPlayerExpand provides transition) {
                    NavHost(
                        navController = nested,
                        startDestination = "blank",
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        composable("blank") { Box(Modifier.fillMaxSize()) }
                        composable(
                            route = "player?bookId={bookId}&startPlaying={startPlaying}",
                            arguments = listOf(
                                navArgument("bookId") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("startPlaying") { type = NavType.BoolType; defaultValue = true }
                            )
                        ) {
                            PlayerContent(
                                onCollapse = { controller.collapse() },
                                startPlaying = it.arguments?.getBoolean("startPlaying") ?: true,
                                onOpenReader = { bookId -> controller.collapse(); onOpenReader(bookId) }
                            )
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun MiniPlayerBar(
    title: String,
    coverPath: String?,
    isPlaying: Boolean,
    // Lambda so the (500ms-ticking) position State is read only inside the progress
    // indicator's deferred draw lambda — a plain Float param would recompose this whole bar
    // (and the caller's scope) on every tick.
    progress: () -> Float,
    enabled: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onCoverBounds: (Rect) -> Unit = {},
    onTitleBounds: (Rect) -> Unit = {},
    onControlsBounds: (Rect) -> Unit = {},
    onBarBounds: (Rect) -> Unit = {},
    expandProgress: androidx.compose.runtime.State<Float>? = null
) {
    // The mini content disappears the instant the full player's morphing counterparts (which
    // start exactly on top of it) take over — so the cover/title/play button visibly TRAVEL.
    val handOff = Modifier.graphicsLayer {
        alpha = if ((expandProgress?.value ?: 0f) > 0.02f) 0f else 1f
    }
    // PlayerSheet itself stays unsplit (it's all shared drag/morph logic), so — like the shared
    // Settings building blocks — the bar resolves its own fill per theme: "liquid glass" (aligned
    // blurred backdrop + darken) in Immersive, opaque tonal Surface in Material You.
    val isImmersive = LocalAppTheme.current == AppTheme.IMMERSIVE
    val barModifier = modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .height(MINI_HEIGHT_DP.dp)
        .onGloballyPositioned { onBarBounds(it.boundsInRoot()) }
        .pressScale(enabled = enabled)
        .clickable(enabled = enabled, onClick = onTap)
    val barContent: @Composable () -> Unit = {
        Box {
            Row(
                Modifier.fillMaxSize().padding(start = 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // handOff must precede background(): a graphicsLayer only affects what is drawn
                // by LATER modifiers + content, so placed after background() the fill would
                // stay visible (a ghost circle/square) while only the content hid.
                Box(
                    Modifier
                        .then(handOff)
                        .size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }
                ) {
                    AsyncImage(
                        model = coverPath?.let { File(it) },
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { onTitleBounds(it.boundsInRoot()) }
                        .then(handOff)
                )
                Spacer(Modifier.width(8.dp))
                // Skip-forward: a quiet secondary control — reveals into the full transport.
                Box(
                    Modifier
                        .then(handOff)
                        .size(38.dp)
                        .clip(Pill)
                        .clickable(enabled = enabled, onClick = onSkip),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.FastForward, "Skip forward",
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(6.dp))
                // Round accent play/pause — the SAME visual as the full player's big play
                // button, so it grows straight into it during the morph.
                Box(
                    Modifier
                        .then(handOff)
                        .size(44.dp)
                        .clip(Pill)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = enabled, onClick = onPlayPause)
                        .onGloballyPositioned { onControlsBounds(it.boundsInRoot()) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
    if (isImmersive) {
        com.betteraudio.ui.immersive.components.GlassPillSurface(
            shape = Pill,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = barModifier,
            content = barContent
        )
    } else {
        Surface(
            shape = Pill,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            // Explicit: contentColorFor() can't resolve a translucent fill and would fall back to
            // LocalContentColor — plain onSurface here, since no parent Surface provides one.
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 3.dp,
            shadowElevation = 10.dp,
            modifier = barModifier,
            content = barContent
        )
    }
}
