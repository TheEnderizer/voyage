package com.betteraudio.ui.player

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.runtime.key
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
import com.betteraudio.ui.immersive.components.fadeTrailingEdge
import com.betteraudio.ui.material.motion.morphingContainer
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import java.io.File
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Which published cover the player should grow out of, when it is not the mini bar's.
     *
     * Null means the ordinary two cases: a live mini bar to morph from, or a library grid card
     * ([CoverBoundsRegistry] keyed by book id) when there is no mini bar yet. A non-null key names
     * an info page's own cover instead (`CoverBoundsRegistry.bookInfoKey` /
     * `seriesInfoKey`) — Resume on Book Info, Play on the Series page — because that cover is the
     * one actually on screen at the moment the player is asked for, and the grid card it came from
     * is hidden behind it.
     */
    val coverSourceKey: String? = null,
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

    /** Open a book in the full player (expands the sheet). [coverSourceKey] names the cover it
     *  should grow out of when that is an info page's rather than a grid card's — see
     *  [PlayerTarget.coverSourceKey]. */
    fun open(bookId: Long = -1L, startPlaying: Boolean = true, coverSourceKey: String? = null) {
        target = PlayerTarget(bookId, startPlaying, coverSourceKey)
        expandToken++
    }

    /**
     * Start a book WITHOUT taking over the screen: playback begins and the mini bar appears, but
     * the sheet stays collapsed.
     *
     * Starting a book is not a request to be moved to another screen — a grid card's play button
     * has always worked this way, and every other "start listening" affordance should match it.
     * Unlike [prime] this replaces whatever target is loaded and does start playback; unlike
     * [open] it never bumps expandToken, so nothing expands.
     */
    fun startCollapsed(bookId: Long) {
        if (bookId == -1L) return
        target = PlayerTarget(bookId, startPlaying = true)
    }

    /**
     * Drops the info-page cover source once the player is fully open.
     *
     * The page that supplied it has closed by then (see MainActivity), so its published rect has
     * been reset to [androidx.compose.ui.geometry.Rect.Zero] — and a collapse reading that would
     * travel back to nothing instead of shrinking into the mini bar it is actually becoming. The
     * outward leg is finished at this point, so swapping the source back costs nothing visually.
     */
    internal fun clearCoverSource() {
        val current = target ?: return
        if (current.coverSourceKey != null) target = current.copy(coverSourceKey = null)
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

// MiniCoverStyle.RING geometry. The slot is the ring's outer box; the cover circle sits inside
// it with a hair of clearance so the ring reads as a border around the art, not a stroke on it.
/** How far the Immersive cover cap dissolves into the pill's glass at its trailing edge. Wide
 *  enough to read as a material transition, narrow enough that the artwork still reads full-width. */
private val MINI_COVER_FADE = 18.dp

private val MINI_RING_SLOT = 52.dp
private val MINI_RING_COVER = 42.dp
private val MINI_RING_STROKE = 3.dp

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
    // Landscape only: instead of floating ABOVE the nav pill, the mini bar sits BESIDE it, at the
    // width and bottom-centre offset the caller worked out (it's the only scope that knows how
    // wide the pill measured). Null = the usual stacked layout.
    miniBarSlot: com.betteraudio.ui.components.MiniBarSlot? = null,
    // "Read from here" (player overflow) needs to collapse this sheet and navigate to the reader
    // route underneath it — that navigation lives outside the sheet's own nested NavHost.
) {
    val playback by playerController.playbackState.collectAsStateWithLifecycle()
    // Read ONLY inside MiniPlayerBar's deferred progress lambda — reading `position` anywhere
    // in this composable's body would recompose the whole sheet on every 500ms tick.
    val position by playerController.positionState.collectAsStateWithLifecycle()
    val target = controller.target
    // Captured here (composable scope) rather than inside graphicsLayer lambdas below, since
    // CompositionLocal.current isn't safe to read from those deferred draw-phase blocks.
    val isMaterialYou = LocalAppTheme.current == AppTheme.MATERIAL_YOU
    // Immersive only, and read here as well as in MiniPlayerBar because the morph's source radius
    // and shape flag depend on which cover the bar actually drew.
    val miniCoverStyle = LocalMiniCoverStyle.current

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
    // ── Parking ────────────────────────────────────────────────────────────────────────────
    // "The sheet is allowed to occupy the screen." While collapsed the full player is pushed one
    // screen height down so the app underneath stays interactive.
    //
    // This is deliberately a COMPOSITION state and not a value read inside the parking modifier's
    // own lambda. Every morph modifier in the player records where it sits with
    // `onGloballyPositioned { positionInRoot() }`, and that includes the parking offset — so while
    // parked, every element believes it lives ~3168px below where it will actually draw. Only a
    // real re-measure/re-place re-fires those callbacks; changing a value a placement lambda reads
    // re-runs the placement but does not reliably refresh them, so the recorded bounds stayed
    // parked for the whole transition and every element travelled from one screen-height off the
    // top — invisible until the last third of the gesture, then arriving all at once. Flipping a
    // composition state swaps the modifier chain itself, which forces the relayout. It flips twice
    // per open/close, so it costs nothing.
    //
    // [forceUnpark] is the expand path's way of getting that relayout to happen BEFORE the first
    // animated frame rather than on it (see the expand effect below).
    var forceUnpark by remember { mutableStateOf(false) }
    // True for the duration of a live drag on either the mini bar or the full container. Gates
    // the mirror below so a stale/still-finishing animateTo (the interrupt case) can't stomp
    // dragProgress while a drag is in control of it.
    var isDragging by remember { mutableStateOf(false) }
    // Captured by the mini bar's onDragStarted (see below) — whether THIS drag gesture began
    // from a fully settled mini bar, which is the only case a firm downward fling may close the
    // book (see Feature 10 in the mini bar's draggable onDragStopped).
    var gestureStartedSettled by remember { mutableStateOf(true) }
    // Highest dragProgress reached during the CURRENT gesture, reset in onDragStarted. A flick
    // that visibly opened the sheet — even a little — before being flung back down is a
    // correction, not "close the book" intent; requiring the whole gesture to have stayed near
    // the bottom (not just ended there) prevents that from being misread as a close.
    var gesturePeak by remember { mutableStateOf(0f) }

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
            .collect { p ->
                controller.expandProgress.floatValue = p
                // The outward leg is over: hand the collapse back to the mini bar.
                if (p >= 0.995f) controller.clearCoverSource()
            }
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

    val unparked by remember {
        derivedStateOf { forceUnpark || dragProgress.floatValue > 0.001f }
    }

    var heightPx by remember { mutableStateOf(0) }
    var widthPx by remember { mutableStateOf(0) }
    val miniPx = with(density) { MINI_HEIGHT_DP.dp.toPx() }
    // Sit the mini bar 20dp above the system navigation bar (gesture bar / button bar).
    val bottomNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Landscape (a side nav bar, a long-edge display cutout) can put the bar under a horizontal
    // system inset the fixed 12dp content padding below doesn't account for — bottom-only inset
    // handling was enough while every window was portrait-shaped.
    val safeDrawingInsets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current
    val horizontalSafeInset = maxOf(
        safeDrawingInsets.calculateStartPadding(layoutDirection),
        safeDrawingInsets.calculateEndPadding(layoutDirection)
    )
    val navReservePx = with(density) { (bottomNavInset + 20.dp).toPx() }
    val travelPx = (heightPx - miniPx - navReservePx).coerceAtLeast(1f)
    // Drag sensitivity is a PHYSICAL distance, not a fraction of the window. On a landscape-shaped
    // window heightPx roughly halves, which would make every drag ~2x twitchier and could turn a
    // flick-up-then-correct-down on the mini bar into an accidental "close the book" fling (see
    // gesturePeak below). Widen the divisor so a given finger movement maps to roughly the same
    // expansion it does in portrait. widthPx > heightPx isn't exclusive to landscape devices (a
    // portrait-device split-screen half can be wider than tall too), but the effect is only ever
    // to REDUCE sensitivity, so it's harmless wherever it fires — including in both themes, since
    // this is pure window geometry and intentionally doesn't consult the current theme.
    val gestureTravelPx = if (widthPx > heightPx) maxOf(travelPx, widthPx * 0.55f) else travelPx
    // Material You's expandingContainer needs the full player's TRUE (unparked) size — NOT
    // measured via onGloballyPositioned on a descendant of the parked/translated container, since
    // boundsInRoot() there wasn't reliably reflecting the un-parked position (the container was
    // rendering as an opaque box sitting on top of the mini bar even while collapsed). Passing the
    // already-tracked outer size directly sidesteps that.
    val ownSizePx = remember { derivedStateOf { androidx.compose.ui.geometry.Size(widthPx.toFloat(), heightPx.toFloat()) } }

    // Material You's morph choreography is built around a visible rebound: progress deliberately
    // overshoots past 1.0 and the mini-player group (cover/title/author/play) absorbs that as ONE
    // rigid unit — see docs/motion-spec.md section 8 and ui/player/ElementMotion.kt.
    //
    // Immersive is the opposite instruction: NO bounce. It used to take the shared
    // MotionTokens.floatSpatial (damping 0.82), which is "slight overshoot then settle" — small,
    // but on a full-screen surface carrying a cover and a whole page of travelling furniture that
    // small overshoot is a visible wobble at the end of every open and close. Critically damped
    // here instead, with the stiffness raised to keep the same arrival speed a 380 spring had.
    // (This is the one sanctioned exception to AN-11's "no sheet-specific springs" unification:
    // the bounce IS the design in one theme and explicitly unwanted in the other.)
    val sheetSpring = remember(isMaterialYou) {
        if (isMaterialYou) androidx.compose.animation.core.spring<Float>(
            // 391ms settle, peak at 143ms, 5.4% overshoot. The first pass (0.60/380) took 580ms
            // and overshot 9.4%, which read as slow and wobbly rather than as a landing.
            dampingRatio = 0.68f, stiffness = 900f
        ) else androidx.compose.animation.core.spring<Float>(
            dampingRatio = 1f, stiffness = 520f
        )
    }

    // The nested player route, and with it "has the player for THIS book ever been laid out".
    //
    // Keyed remember, so switching books (or opening one with no mini bar at all) hands back a
    // fresh `false` on the very frame the target changes, while reopening the book the sheet
    // already holds keeps the `true` it earned. That is exactly the difference between the two
    // cases: an expand whose player is already on screen can start immediately; an expand that has
    // to build the player first has to wait for it, or the morph runs against Rect.Zero.
    // clearCoverSource() deliberately does not change this string (see its KDoc).
    // The book, and ONLY the book. startPlaying used to be part of this string, which made
    // "restore this book paused" and "resume this book" two different destinations for the same
    // player -- so pressing Resume on a book the sheet was already holding tore the whole player
    // down and rebuilt it (fresh ViewModel, fresh layout) on exactly the frame the expand started.
    // Every morph then ran against a player that did not exist yet: the page had handed its title,
    // track and Resume button over at p > 0.02 and there was nothing on the other side to catch
    // them. That is the "player wasn't already open" case. Whether to start playing is an ACTION,
    // not an address; it rides on the target and reaches the player as a parameter below.
    val playerRoute = target?.let { "player?bookId=${it.bookId}" }
    val playerReady = remember(playerRoute) { mutableStateOf(false) }

    // React to expand/collapse intents (token-based so they survive composition timing).
    LaunchedEffect(controller.expandToken) {
        if (controller.expandToken > 0) {
            // Let the player lay out first when it has not already. With a live mini bar it is
            // already up and this costs nothing; with no mini bar at all the whole subtree —
            // NavHost destination, ViewModel, the book's first Room emission — is being built from
            // scratch on this very frame, and running the choreography against it means running it
            // against nothing: every `own` rect is Rect.Zero and the page has already handed its
            // furniture over. Worth a beat of stillness; not worth a hang.
            if (!playerReady.value) withTimeoutOrNull(1200) {
                snapshotFlow { playerReady.value }.first { it }
            }
            // Unpark FIRST, and let a layout pass actually land, so every morph modifier in
            // the player has recorded its real on-screen bounds before progress leaves 0. Two
            // frames: the first commits the recomposition that swaps the parking modifier out,
            // the second is the layout pass whose onGloballyPositioned callbacks rewrite the
            // bounds. Without this the whole choreography runs against parked coordinates.
            try {
                forceUnpark = true
                androidx.compose.runtime.withFrameNanos { }
                androidx.compose.runtime.withFrameNanos { }
                progressAnim.animateTo(1f, sheetSpring)
            } finally {
                // Past this point `unparked` follows progress again, so a cancelled or finished
                // expand can never leave an invisible full-screen sheet eating touches.
                forceUnpark = false
            }
        }
    }
    LaunchedEffect(controller.collapseToken) {
        if (controller.collapseToken > 0)
            progressAnim.animateTo(0f, sheetSpring)
    }

    // derivedStateOf: recompose only when the threshold flips, not every animation frame.
    val expanded by remember { derivedStateOf { dragProgress.floatValue > 0.5f } }
    val sheetHaptics = com.betteraudio.ui.haptics.LocalHaptics.current
    var sheetSeen by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        controller.setExpanded(expanded)
        // The drag is continuous, so the only moment worth marking is the one where it commits to
        // being open or closed — which is the same threshold the rest of the sheet reacts to.
        if (expanded != sheetSeen) {
            sheetSeen = expanded
            sheetHaptics.play(
                if (expanded) com.betteraudio.ui.haptics.Feel.Reveal
                else com.betteraudio.ui.haptics.Feel.Dismiss
            )
        }
    }

    // Shared-element morph plumbing: expansion progress + the mini bar's element bounds,
    // all as State so the full player reads them inside graphicsLayer lambdas only.
    val progressState = remember { derivedStateOf { dragProgress.floatValue } }
    val miniCoverRect = remember { mutableStateOf(Rect.Zero) }
    val miniTitleRect = remember { mutableStateOf(Rect.Zero) }
    val miniAuthorRect = remember { mutableStateOf(Rect.Zero) }
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
    // An info page opened the player: its cover is what is on screen, and the grid card the page
    // grew out of is hidden behind it. This one outranks a live mini bar rather than deferring to
    // it — the bar is a 64dp pill at the bottom of the screen and the cover is the thing under the
    // finger that asked for the player, so it wins even when something else is already playing.
    val infoCoverKey = target?.coverSourceKey
    // "The source is a rectangle that is not the mini bar's cover slot" — which is what Material
    // You branches on to pick the aspect-aware coverCropMorph, and what decides whether its
    // background grows out of that rectangle too.
    val sourceIsGridCard = !usingLivePlayback || infoCoverKey != null
    val effectiveCoverSource = remember(target?.bookId, infoCoverKey, usingLivePlayback) {
        when {
            infoCoverKey != null -> coverBoundsRegistry.infoCoverBounds(infoCoverKey)
            sourceIsGridCard -> coverBoundsRegistry.boundsState(target?.bookId ?: -1L)
            else -> miniCoverRect
        }
    }
    val effectiveCoverRadius = remember(target?.bookId, infoCoverKey, usingLivePlayback, isMaterialYou, miniCoverStyle) {
        if (infoCoverKey != null) {
            coverBoundsRegistry.infoCoverRadius(infoCoverKey)
        } else if (sourceIsGridCard) {
            coverBoundsRegistry.radiusFor(target?.bookId ?: -1L)
        } else if (isMaterialYou) {
            12.dp   // matches the mini bar's 48dp thumbnail clip
        } else if (miniCoverStyle == MiniCoverStyle.RING) {
            // A full corner radius on the source, so the one element that still uses the plain
            // morphFrom here — the blurred backdrop, which is transparent at progress 0 anyway —
            // starts round rather than square-cornered next to a circular cover.
            MINI_RING_COVER / 2
        } else {
            // Immersive's mini cover is a "D": the pill's 32dp left cap on one side, a 6dp
            // trailing edge on the other. The morph carries a single radius, so this is their
            // average — close enough that the first frame sits on the cover it grows out of.
            16.dp
        }
    }
    // Tell the grid card whose cover is currently morphing so it can hide its own copy (prevents
    // seeing both the still grid card AND the traveling player cover at once).
    // Deliberately NOT registered for an info-page source: the page is still on screen and has its
    // own setActiveMorph registration live for the same book (that is how it hides the grid card it
    // grew out of), and two writers of one slot would leave whichever finished last in charge.
    LaunchedEffect(sourceIsGridCard, infoCoverKey, target?.bookId) {
        coverBoundsRegistry.setActiveMorph(
            if (sourceIsGridCard && infoCoverKey == null) target?.bookId ?: -1L else -1L,
            progressState
        )
    }
    // Material You's background grows out of a rect too, and with no mini bar there is no pill to
    // grow from — miniBarRect stays Rect.Zero, which morphingContainer reads as "no morph" and
    // paints the whole screen on the first frame. Starting it from the same cover the transition is
    // travelling from means the panel unfolds out of the picture instead of appearing behind it.
    val containerSource = if (sourceIsGridCard) effectiveCoverSource else miniBarRect
    val containerRadius = if (sourceIsGridCard) effectiveCoverRadius else MINI_BAR_RADIUS
    // ── The resume leg ──────────────────────────────────────────────────────────────────────
    // An info page publishes its own title, author, whole-book track and Resume button while it is
    // on screen (see CoverBoundsRegistry.publishInfoSlot). When the player was opened from that
    // page, those become the sources its counterparts travel out of — so the title/author simply
    // point somewhere else and need no change in either player, while the track and the button are
    // new travellers that only exist on this leg.
    val resumeTitle = remember(infoCoverKey) {
        infoCoverKey?.let { coverBoundsRegistry.infoSlotBounds(it, InfoSlot.TITLE) }
    }
    val resumeAuthor = remember(infoCoverKey) {
        infoCoverKey?.let { coverBoundsRegistry.infoSlotBounds(it, InfoSlot.AUTHOR) }
    }
    val resumeTrack = remember(infoCoverKey) {
        infoCoverKey?.let { coverBoundsRegistry.infoSlotBounds(it, InfoSlot.TRACK) }
    }
    val resumeButton = remember(infoCoverKey) {
        infoCoverKey?.let { coverBoundsRegistry.infoSlotBounds(it, InfoSlot.BUTTON) }
    }
    val resumeIcon = remember(infoCoverKey) {
        infoCoverKey?.let { coverBoundsRegistry.infoSlotBounds(it, InfoSlot.BUTTON_ICON) }
    }
    val resumeLabel = remember(infoCoverKey) {
        infoCoverKey?.let { coverBoundsRegistry.infoSlotBounds(it, InfoSlot.BUTTON_LABEL) }
    }
    // The page has to have actually published a button for this leg to be runnable — a page that
    // hasn't laid out yet, or one that shows no Resume button at all, falls back to the ordinary
    // reveal rather than travelling out of Rect.Zero.
    // derivedStateOf: the page republishes these rects on every layout pass, and only the flip from
    // "nothing published" to "published" is worth a recomposition here.
    val resumeActive by remember(resumeButton) {
        derivedStateOf { (resumeButton?.value ?: Rect.Zero) != Rect.Zero }
    }

    val transition = remember(
        effectiveCoverSource, effectiveCoverRadius, containerSource, containerRadius,
        sourceIsGridCard, miniCoverStyle, isMaterialYou, resumeActive, infoCoverKey, playerReady
    ) {
        PlayerExpandTransition(
            progressState, effectiveCoverSource,
            miniTitle = if (resumeActive) resumeTitle!! else miniTitleRect,
            miniControls = miniControlsRect,
            coverSourceRadius = effectiveCoverRadius,
            miniBar = containerSource, miniBarRadius = containerRadius, sourceIsGridCard = sourceIsGridCard,
            miniAuthor = if (resumeActive) resumeAuthor!! else miniAuthorRect,
            resumeActive = resumeActive,
            infoTrack = resumeTrack ?: miniTitleRect,
            resumeButton = resumeButton ?: miniControlsRect,
            resumeIcon = resumeIcon ?: miniControlsRect,
            resumeLabel = resumeLabel ?: miniControlsRect,
            playerReady = playerReady,
            // A grid card is always a rectangle, so the circular morph only applies when the live
            // mini bar is genuinely the source.
            coverSourceIsCircle = !isMaterialYou && !sourceIsGridCard &&
                miniCoverStyle == MiniCoverStyle.RING
        )
    }

    // Ends a drag gesture: hands the live value off to progressAnim with a single snapTo (not a
    // per-delta one, so it can't race — this also cancels any stale animateTo left over from an
    // interrupted settle/token animation) then lets the normal spring settle it open or closed.
    suspend fun settle(velocity: Float) {
        progressAnim.snapTo(dragProgress.floatValue)
        val goExpand = velocity < -1000f || (velocity <= 1000f && dragProgress.floatValue > 0.5f)
        progressAnim.animateTo(if (goExpand) 1f else 0f, sheetSpring)
    }

    // The animated PART of the mini bar's bottom lift (on top of the fixed baseline padding
    // applied at its call site below) — kept as State<Dp>, read only inside that graphicsLayer,
    // per C3-5.
    val miniBarLift = androidx.compose.animation.core.animateDpAsState(
        when {
            // Beside the pill (landscape): same height as it, so line their bottoms up instead of
            // clearing it. The baseline padding below is navInset + 20dp; the pill's is
            // navInset + NAV_PILL_BOTTOM_PADDING, so this drops the bar by the difference.
            miniBarSlot != null ->
                com.betteraudio.ui.components.NAV_PILL_BOTTOM_PADDING - 20.dp
            liftForNavPill ->
                com.betteraudio.ui.components.NAV_PILL_BOTTOM_PADDING +
                    com.betteraudio.ui.components.NAV_PILL_HEIGHT +
                    com.betteraudio.ui.components.NAV_PILL_GAP - 20.dp
            else -> 0.dp
        },
        label = "miniBarLift"
    )

    // ── Dismissal ──────────────────────────────────────────────────────────────────────────
    // A fling-down close used to be instantaneous: playerController.stop() and controller.clear()
    // on the same frame, so the bar was simply not there on the next one. Everything else in this
    // app tells you where a thing went; this one blinked out. 0 = resting, 1 = gone.
    //
    // Driven from the draw phase through the layer the bar already has, so the dismissal costs no
    // layout passes (C3-5, the same reason miniBarLift translates rather than pads).
    val dismissAnim = remember { androidx.compose.animation.core.Animatable(0f) }
    var dismissing by remember { mutableStateOf(false) }

    // How far it falls: its own height plus the padding beneath it, so it is clear of the display
    // edge rather than merely mostly gone. Falls back to a sensible constant before the bar has
    // ever reported bounds.
    val dismissDropPx = (miniBarRect.value.height.takeIf { it > 1f } ?: 220f) +
        with(density) { (bottomNavInset + 20.dp).toPx() }

    suspend fun dismissMiniBar() {
        if (dismissing) return
        dismissing = true
        try {
            // Accelerating out, not a spring: this is a thing leaving, and a spring's settle
            // implies it is coming to rest somewhere — which it is not.
            dismissAnim.animateTo(
                1f,
                androidx.compose.animation.core.tween(
                    durationMillis = 260,
                    easing = androidx.compose.animation.core.FastOutLinearInEasing
                )
            )
            playerController.stop()
            controller.clear()
        } finally {
            // Reset before the next book opens, so a bar that returns does not start invisible
            // and one screen low if this composable outlives the clear.
            dismissAnim.snapTo(0f)
            dismissing = false
        }
    }

    // The full player's own resume action (PlayerViewModel.play(), with series context and the
    // isCompleted-safe start position — see AudioCascade.resolveStart), captured from whichever
    // PlayerViewModel is currently backing the nested NavHost's player route below. The mini
    // bar's play button uses this instead of a bare playerController.togglePlayPause() whenever
    // the service doesn't actually have a queue loaded (see hasLoadedQueue's KDoc for why
    // relying on playback.bookId alone isn't enough — it goes stale in exactly the same way).
    var resumeAction by remember { mutableStateOf<(() -> Unit)?>(null) }

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
            author = if (usingLivePlayback) playback.author else "",
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
            onPlayPause = {
                // The service can report a book here (playback.bookId, hence usingLivePlayback)
                // while its actual queue is empty — that public state is never reset when the
                // playback service is torn down and rebuilt, so it can go stale exactly like the
                // private currentBookId field used to (see hasLoadedQueue's KDoc). Calling
                // togglePlayPause() on an empty queue used to STATE_ENDED the player and wrongly
                // mark the book finished. Route through the full player's own resume action —
                // same series context and isCompleted-safe start position as every other resume
                // entry point — whenever there's nothing actually loaded to toggle.
                if (playerController.hasLoadedQueue()) playerController.togglePlayPause()
                else resumeAction?.invoke()
            },
            onSkip = { playerController.skipForward() },
            onCoverBounds = { miniCoverRect.value = it },
            onTitleBounds = { miniTitleRect.value = it },
            onAuthorBounds = { miniAuthorRect.value = it },
            onControlsBounds = { miniControlsRect.value = it },
            onBarBounds = { miniBarRect.value = it },
            expandProgress = progressState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Landscape pairs the bar with the nav pill on one row: a fixed width and an
                // x-shift off bottom-centre, both sized by the caller so the pair reads as centred.
                // MiniPlayerBar's own fillMaxWidth() then resolves to exactly this width.
                .then(
                    miniBarSlot?.let { Modifier.offset(x = it.offsetX).width(it.width) }
                        ?: Modifier
                )
                // Paired: the slot's width and centerShift were derived from an already
                // inset-free available width, so adding the inset here would shrink it twice.
                .padding(horizontal = if (miniBarSlot != null) 0.dp else horizontalSafeInset)
                // Fixed baseline padding (no animation → no per-frame remeasure); the animated
                // part of the lift moves via graphicsLayer's translationY below instead of a
                // second, animated padding value (C3-5: padding()-driven animation forces a
                // layout pass every frame it's running; a layer translation doesn't).
                .padding(bottom = bottomNavInset + 20.dp)
                .graphicsLayer {
                    // Float NAV_PILL_GAP above the pill on home; hug the bottom (the padding
                    // above already accounts for) elsewhere. Deferred (draw-phase) read of
                    // miniBarLift.value, same discipline as dragProgress/alpha below.
                    // The dismissal rides on top of the lift: it falls from wherever it was
                    // sitting, rather than snapping to a baseline first.
                    translationY = -miniBarLift.value.toPx() + dismissAnim.value * dismissDropPx
                    // Material You: the pill stays fully visible — it's physically occluded by
                    // the full player's morphingContainer (same rect, grown from it) the moment
                    // the sheet starts opening, so no separate fade is needed. Immersive keeps the
                    // original crossfade look (no growing container there).
                    val expandAlpha = if (isMaterialYou) 1f
                            else (1f - dragProgress.floatValue * IMMERSIVE_MINI_BAR_FADE_RATE).coerceIn(0f, 1f)
                    // Fading FASTER than it falls, so it is gone before it reaches the bottom of
                    // its travel: a bar still faintly visible as it clears the edge reads as
                    // sliding behind the display rather than being dismissed.
                    val dismissAlpha = (1f - dismissAnim.value * 1.6f).coerceIn(0f, 1f)
                    alpha = expandAlpha * dismissAlpha
                }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        // Synchronous, no coroutine — see dragProgress's declaration (AN-2).
                        dragProgress.floatValue = (dragProgress.floatValue - delta / gestureTravelPx).coerceIn(0f, 1f)
                        if (dragProgress.floatValue > gesturePeak) gesturePeak = dragProgress.floatValue
                    },
                    // Only a flick that BEGAN from a genuinely settled mini bar is eligible to
                    // close the book — otherwise a downward flick caught mid-expansion (tap to
                    // expand, then immediately flick down before the open animation finishes)
                    // would also satisfy "progress < 0.15f" at release and wrongly close it
                    // instead of just returning to the mini bar.
                    onDragStarted = {
                        isDragging = true
                        gestureStartedSettled = dragProgress.floatValue < 0.001f
                        gesturePeak = dragProgress.floatValue
                    },
                    // A firm downward fling starting from the settled mini bar closes the book
                    // (stops playback and dismisses the mini bar); otherwise settle open/closed
                    // as usual — which, for an in-flight expansion flicked back down, means
                    // returning to the mini bar rather than closing. gesturePeak < 0.15f requires
                    // the gesture to have STAYED near the bottom throughout, not merely ended
                    // there — a strict tightening that can only ever prevent an accidental close.
                    onDragStopped = { velocity ->
                        isDragging = false
                        if (velocity > 1800f && dragProgress.floatValue < 0.15f &&
                            gestureStartedSettled && gesturePeak < 0.15f) {
                            scope.launch { dismissMiniBar() }
                        } else scope.launch { settle(velocity) }
                    }
                )
        )

        // ── Full player — fixed full-screen; its elements morph out of the mini bar. When
        // fully collapsed it's parked offscreen so the app underneath stays interactive.
        if (target != null) {
            // The NavHost below STARTS on the player route; it never navigates. See the comment on
            // `key(playerRoute)` there for why, and for what a book switch does instead.
            Box(
                Modifier
                    .fillMaxSize()
                    // Parked offscreen while collapsed, so the app underneath stays interactive.
                    //
                    // This MUST be a placement offset and not a graphicsLayer translation. Every
                    // morph modifier in the player records its own bounds with
                    // `onGloballyPositioned { positionInRoot() }`, and positionInRoot includes any
                    // ancestor layer transform — so while the sheet was parked, every element in
                    // the player believed it lived one whole screen height below where it actually
                    // draws. A graphicsLayer change does not relayout, so those bounds were never
                    // corrected: the pill, the title and the transport spent the whole transition
                    // translated ~3000px off the top of the screen, sliding in from nowhere around
                    // p = 0.3. Invisible with a live mini bar (the player had already been laid out
                    // unparked at some point, so its bounds were right), and the whole of the
                    // "only when the mini player is closed" bug. `offset` runs in the layout phase,
                    // reads dragProgress deferred exactly like the layer did, and re-fires
                    // onGloballyPositioned the moment it unparks.
                    .then(
                        if (unparked) Modifier
                        else Modifier.offset { androidx.compose.ui.unit.IntOffset(0, heightPx) }
                    )
                    // draggable on the container — activates only after the touch-slop
                    // threshold, so buttons/menus inside still receive their own taps.
                    .draggable(
                        enabled = expanded,
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            // Synchronous, no coroutine — see dragProgress's declaration (AN-2).
                            dragProgress.floatValue = (dragProgress.floatValue - delta / gestureTravelPx).coerceIn(0f, 1f)
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
                    // Deferred (draw-phase) read of dragProgress, same discipline the old
                    // drawBehind{} here already followed — only barColor/bgColor are captured at
                    // composition time (unchanged from before this migration).
                    val growingColor = remember(barColor, bgColor) {
                        derivedStateOf {
                            androidx.compose.ui.graphics.lerp(
                                barColor, bgColor, dragProgress.floatValue.coerceIn(0f, 1f)
                            )
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                // The resume leg does NOT unfold. Growing this container out of
                                // the cover is right when the player is arriving over some other
                                // screen, and wrong here: the page underneath is this same book's
                                // page, its title/author/track/button are travelling into their
                                // player positions, and a window opening out of the cover drags
                                // all of that sideways behind a corner wipe -- which reads as
                                // "the page expanded from the top left", the very gesture the
                                // library -> Book Info leg already owns. So the ground simply
                                // becomes opaque in place, on the same p^2 ramp Immersive's
                                // backdrop uses, which also keeps the first ~0.16 of the
                                // transition (the leaving synopsis, chip and % row) visible.
                                if (resumeActive) Modifier.drawBehind {
                                    val p = dragProgress.floatValue.coerceIn(0f, 1f)
                                    drawRect(bgColor.copy(alpha = p * p))
                                }
                                else Modifier.morphingContainer(
                                    source = containerSource,
                                    ownSizePx = ownSizePx,
                                    progress = progressState,
                                    sourceRadius = containerRadius,
                                    destRadius = 0.dp,
                                    color = growingColor
                                )
                            )
                    )
                }
                CompositionLocalProvider(LocalPlayerExpand provides transition) {
                    // ── Why this NavHost starts on the player route and never navigates ──────────
                    //
                    // A NavHost renders its destinations inside an AnimatedContent. On the frame a
                    // navigate lands, that AnimatedContent clips the incoming destination to a
                    // window that opens downward from the top, while the content inside it is
                    // already laid out at full size. Measured on the emulator, Resume leg, the
                    // destination's own boundsInRoot against its measured height of 3168:
                    //
                    //     p=0.00  h=3168  clipped 0..791
                    //     p=0.31  h=3168  clipped 0..1683
                    //     p=0.73  h=3168  clipped 0..2546
                    //     p=1.00  h=3168  clipped 0..3168
                    //
                    // The play button sits at 2652..2868, so it is cut away entirely until p≈0.77
                    // and then appears to grow downward. That is the reported "rectangle that is
                    // expanding": the transport and the utility row are not late, they are clipped.
                    // The Box wrapping this NavHost measures 0..3168 unclipped throughout, so the
                    // clip is introduced strictly between it and the destination.
                    //
                    // `sizeTransform = { null }` and EnterTransition.None do NOT remove it, which is
                    // why this is structural rather than another transition argument. The mini-bar
                    // leg never shows the fault because the NavHost settled on this route long ago:
                    // there, the same probe fires once, unclipped. The Resume leg navigates on the
                    // very frame the sheet expands, so the wipe runs underneath the choreography.
                    //
                    // So: do not navigate. `target != null` guards this whole block, so playerRoute
                    // is always a real route here and the old "blank" start destination was only
                    // ever a placeholder to navigate away from. `key(playerRoute)` gives a book
                    // switch what `popUpTo(0) { inclusive = true }` used to give it — a brand new
                    // controller, back stack and ViewModel — by rebuilding the NavHost outright
                    // instead of animating between two destinations.
                    key(playerRoute) {
                    NavHost(
                        navController = rememberNavController(),
                        startDestination = playerRoute!!,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        sizeTransform = { null },
                        popExitTransition = { ExitTransition.None }
                    ) {
                        composable(
                            route = "player?bookId={bookId}",
                            arguments = listOf(
                                navArgument("bookId") { type = NavType.LongType; defaultValue = -1L }
                            )
                        ) { backStackEntry ->
                            // Obtained explicitly (rather than via PlayerContent's own default
                            // hiltViewModel()) purely so this composable can also capture it —
                            // hiltViewModel() called with this entry resolves to the SAME instance
                            // PlayerContent gets internally, since both are scoped to it.
                            val playerViewModel: PlayerViewModel = hiltViewModel(backStackEntry)
                            LaunchedEffect(playerViewModel) { resumeAction = { playerViewModel.play() } }
                            PlayerContent(
                                onCollapse = { controller.collapse() },
                                // Read live off the target rather than frozen into the route, so a
                                // restore (false) that the user then resumes (true) flips this in
                                // place instead of rebuilding the screen. The players key their
                                // auto-play effect on it.
                                startPlaying = target?.startPlaying ?: true,
                                viewModel = playerViewModel
                            )
                        }
                    }
                    }
                }
            }
        }

    }
}

/**
 * The circular mini cover and the progress ring around it.
 *
 * [onCoverBounds] reports the COVER DISC, not the ring outer box — the full player artwork grows
 * out of the picture, and starting it a ring-width too large would show as a jump on the first
 * frame of the drag. [progress] stays a lambda all the way into the draw block, so the 500ms
 * position tick redraws this ring and recomposes nothing.
 */
@Composable
private fun MiniCoverRing(
    coverPath: String?,
    progress: () -> Float,
    modifier: Modifier = Modifier,
    onCoverBounds: (Rect) -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = com.betteraudio.ui.immersive.ImmersiveStyle.scrimText().copy(alpha = 0.22f)
    Box(modifier.size(MINI_RING_SLOT), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = MINI_RING_STROKE.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val corner = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = corner, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
            )
            val p = progress().coerceIn(0f, 1f)
            if (p > 0f) {
                drawArc(
                    color = accent, startAngle = -90f, sweepAngle = 360f * p, useCenter = false,
                    topLeft = corner, size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                )
            }
        }
        Box(
            Modifier
                .size(MINI_RING_COVER)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }
        ) {
            AsyncImage(
                model = coverPath?.let { File(it) },
                contentDescription = null,
                // Crop, not FillWidth: the slot is a fixed circle, and the travelling copy the full
                // player grows is scaled to the same centre crop (see morphFromCircle).
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    title: String,
    author: String,
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
    onAuthorBounds: (Rect) -> Unit = {},
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
    val coverStyle = LocalMiniCoverStyle.current
    // The circle is a cover-first look too, just a different one: instead of the cover BEING the
    // pill leading edge, it is a disc inside the pill wearing its own progress. Immersive only —
    // Material You keeps its inset square thumbnail and its straight bar underneath.
    val useRing = isImmersive && coverStyle == MiniCoverStyle.RING
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
                // Immersive's cover is the pill's own left cap, so it starts hard against the
                // edge with no inset to sit in.
                // The cap has to start hard against the edge because it IS the edge; the ring is
                // an inset element and needs the same breathing room Material You gives its thumb.
                Modifier.fillMaxSize().padding(start = if (isImmersive && !useRing) 0.dp else 8.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // handOff must precede background(): a graphicsLayer only affects what is drawn
                // by LATER modifiers + content, so placed after background() the fill would
                // stay visible (a ghost circle/square) while only the content hid.
                //
                // Immersive: full-height flush square rather than a 48dp thumbnail inset in a
                // 64dp bar. In a cover-first theme the cover was the smallest thing on the most
                // frequently visible surface; now it IS the bar's leading form, clipped into a
                // "D" by the pill's own left cap (GlassPillSurface clips its children), with just
                // enough radius on the trailing edge to stop it reading as a cut.
                // Immersive sizes the cap by the cover's REAL aspect rather than forcing a square.
                // The full player's artwork keeps that same aspect (the bake does, and the
                // travelling sharp copy now matches it), so source and destination are the same
                // shape and the morph between them is one uniform scale — no squash, no re-crop,
                // nothing to land crooked. Material You is untouched and keeps its square thumb.
                val miniAspect =
                    if (isImmersive) com.betteraudio.ui.components.rememberCoverAspect(coverPath) else 1f
                if (useRing) {
                    MiniCoverRing(
                        coverPath = coverPath,
                        progress = progress,
                        modifier = Modifier.then(handOff),
                        onCoverBounds = onCoverBounds
                    )
                } else {
                    Box(
                        Modifier
                            .then(handOff)
                            .then(
                                if (isImmersive)
                                    Modifier
                                        .height(MINI_HEIGHT_DP.dp)
                                        .width(MINI_HEIGHT_DP.dp * miniAspect)
                                        // Was a 6dp trailing radius, which only ever softened the
                                        // corners of a cut that still ran straight down the middle
                                        // of the pill. The cover now runs out into the glass
                                        // instead — see fadeTrailingEdge.
                                        .fadeTrailingEdge(MINI_COVER_FADE)
                                else
                                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .onGloballyPositioned { onCoverBounds(it.boundsInRoot()) }
                    ) {
                        AsyncImage(
                            model = coverPath?.let { File(it) },
                            contentDescription = null,
                            // FillWidth in Immersive to match the full player and the bake; Crop
                            // elsewhere, where the slot is a fixed square.
                            contentScale = if (isImmersive) androidx.compose.ui.layout.ContentScale.FillWidth
                                           else androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f).then(handOff),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { onTitleBounds(it.boundsInRoot()) }
                    )
                    if (author.isNotBlank()) {
                        Text(
                            author,
                            style = MaterialTheme.typography.bodySmall,
                            // Immersive tints ALL text toward the cover accent; the mini bar was
                            // the one surface still using the raw theme colours.
                            color = if (isImmersive) com.betteraudio.ui.immersive.ImmersiveStyle.scrimText(muted = true)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned { onAuthorBounds(it.boundsInRoot()) }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Skip-forward: a quiet secondary control — reveals into the full transport.
                com.betteraudio.ui.haptics.PressFeel(com.betteraudio.ui.haptics.Feel.Transport) {
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
                        Modifier.size(19.dp),
                        // Immersive tints every glyph toward the cover accent (Law 03); this one
                        // was the last raw-M3 colour left on the pill, and read as a foreign grey
                        // sitting between accent-tinted text and an accent play button.
                        tint = if (isImmersive)
                            com.betteraudio.ui.immersive.ImmersiveStyle.scrimText(muted = true)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                }
                Spacer(Modifier.width(6.dp))
                // Round accent play/pause — the SAME visual as the full player's big play
                // button, so it grows straight into it during the morph.
                com.betteraudio.ui.haptics.PressFeel(com.betteraudio.ui.haptics.Feel.Transport) {
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
            }
            if (useRing) {
                // Nothing here: the ring around the cover IS this bar progress readout. Drawing
                // the pill outline as well would state it twice.
            } else if (isImmersive) {
                // Progress traces the pill's own outline instead of running straight under it —
                // see PillPerimeterProgress for why the straight bar was wrong on this shape.
                com.betteraudio.ui.immersive.components.PillPerimeterProgress(
                    progress = progress,
                    color = MaterialTheme.colorScheme.primary,
                    // Finer than the 2.5dp default: with the rim gone (edgeLight = false below)
                    // this is the ONLY line on the pill, so it no longer has to out-weigh a
                    // competing outline to be seen as the meaningful one.
                    strokeWidth = 2.dp,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }
    if (isImmersive) {
        com.betteraudio.ui.immersive.components.GlassPillSurface(
            shape = Pill,
            contentColor = com.betteraudio.ui.immersive.ImmersiveStyle.scrimText(),
            // Borderless: at 64dp tall and full-bleed wide this is the biggest floating surface in
            // the app, and the 1dp rim that flatters the small nav pill reads here as an outline
            // drawn around a card. What separates it from the backdrop instead is depth — a
            // wider, softer shadow than the nav pill's, so the pill sits *above* the artwork
            // rather than being *cut out* of it.
            edgeLight = false,
            shadowElevation = 18.dp,
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
