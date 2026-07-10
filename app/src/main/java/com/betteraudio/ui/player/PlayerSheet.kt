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
import coil.compose.AsyncImage
import com.betteraudio.playback.PlayerController
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import java.io.File
import kotlinx.coroutines.launch

/** Which book/group the expanded player should show. */
data class PlayerTarget(
    val bookId: Long = -1L,
    val groupId: Long = -1L,
    val startInfo: Boolean = false,
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

    /** Set the target without expanding (used to show the mini bar for the last-played book). */
    fun prime(bookId: Long = -1L, groupId: Long = -1L) {
        if (target == null) target = PlayerTarget(bookId, groupId, startInfo = false)
    }

    /** Cold-start restore of the mini bar (collapsed): load the last-played book PAUSED so the
     *  mini bar shows it even when the playback service was killed. Unlike [prime], this forces
     *  startPlaying = false so reopening the app never auto-resumes. */
    fun restore(bookId: Long) {
        if (target == null && bookId != -1L)
            target = PlayerTarget(bookId = bookId, startInfo = false, startPlaying = false)
    }

    /** Open a book/group in the full player (expands the sheet). */
    fun open(bookId: Long = -1L, groupId: Long = -1L, startInfo: Boolean = false, startPlaying: Boolean = true) {
        target = PlayerTarget(bookId, groupId, startInfo, startPlaying)
        expandToken++
    }

    fun expandCurrent() { if (target != null) expandToken++ }
    fun collapse() { collapseToken++ }

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
    // "Read from here" (player overflow) needs to collapse this sheet and navigate to the reader
    // route underneath it — that navigation lives outside the sheet's own nested NavHost.
    onOpenReader: (Long) -> Unit = {}
) {
    val playback by playerController.playbackState.collectAsStateWithLifecycle()
    // Read ONLY inside MiniPlayerBar's deferred progress lambda — reading `position` anywhere
    // in this composable's body would recompose the whole sheet on every 500ms tick.
    val position by playerController.positionState.collectAsStateWithLifecycle()
    val target = controller.target

    // Mirror the playing book into the target so the mini bar is ready to expand.
    LaunchedEffect(playback.bookId, playback.groupId) {
        if (playback.bookId != -1L) controller.prime(playback.bookId, playback.groupId)
    }

    // Cold-start restore (PlayerSheetController.restore): `target` is set to the last-played book,
    // but PlayerController.playbackState stays empty until playback actually starts — which is why
    // the mini bar used to render blank while the full player (which loads its book straight from
    // Room) worked fine. Load that same book/progress data here as a fallback the mini bar can show
    // until real playback state takes over.
    val restoreVm: MiniPlayerRestoreViewModel = hiltViewModel()
    val usingLivePlayback = playback.bookId != -1L || playback.groupId != -1L
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

    var heightPx by remember { mutableStateOf(0) }
    val miniPx = with(density) { MINI_HEIGHT_DP.dp.toPx() }
    // Sit the mini bar 20dp above the system navigation bar (gesture bar / button bar).
    val bottomNavInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val navReservePx = with(density) { (bottomNavInset + 20.dp).toPx() }
    val travelPx = (heightPx - miniPx - navReservePx).coerceAtLeast(1f)

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
    val expanded by remember { derivedStateOf { progressAnim.value > 0.5f } }
    LaunchedEffect(expanded) { controller.setExpanded(expanded) }

    // Shared-element morph plumbing: expansion progress + the mini bar's element bounds,
    // all as State so the full player reads them inside graphicsLayer lambdas only.
    val progressState = remember { derivedStateOf { progressAnim.value } }
    val miniCoverRect = remember { mutableStateOf(Rect.Zero) }
    val miniTitleRect = remember { mutableStateOf(Rect.Zero) }
    val miniControlsRect = remember { mutableStateOf(Rect.Zero) }
    val transition = remember {
        PlayerExpandTransition(progressState, miniCoverRect, miniTitleRect, miniControlsRect)
    }

    fun settle(velocity: Float) {
        val goExpand = velocity < -1000f || (velocity <= 1000f && progressAnim.value > 0.5f)
        scope.launch {
            progressAnim.animateTo(
                if (goExpand) 1f else 0f,
                spring(dampingRatio = 0.85f, stiffness = 380f)
            )
        }
    }

    Box(modifier.fillMaxSize().onSizeChanged { heightPx = it.height }) {
        // ── Mini bar — docked at the bottom, drawn UNDER the full player so the morphing
        // cover/title/controls (which start exactly on top of their mini counterparts) read as
        // the same element travelling, not a crossfade. The mini content hides the moment the
        // morph takes over; only the pill surface fades out.
        if (!(hideMiniBar && !expanded)) MiniPlayerBar(
            title = when {
                usingLivePlayback && playback.groupId != -1L -> playback.groupName
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
                if (playback.groupId != -1L) controller.open(groupId = playback.groupId)
                else if (playback.bookId != -1L) controller.open(bookId = playback.bookId)
                else controller.expandCurrent()
            },
            onPlayPause = { playerController.togglePlayPause() },
            onSkip = { playerController.skipForward() },
            onCoverBounds = { miniCoverRect.value = it },
            onTitleBounds = { miniTitleRect.value = it },
            onControlsBounds = { miniControlsRect.value = it },
            expandProgress = progressState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomNavInset + 20.dp)
                .graphicsLayer { alpha = (1f - progressAnim.value * 2.5f).coerceIn(0f, 1f) }
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        scope.launch {
                            progressAnim.snapTo((progressAnim.value - delta / travelPx).coerceIn(0f, 1f))
                        }
                    },
                    // A firm downward fling while collapsed closes the book (stops playback and
                    // dismisses the mini bar); otherwise settle open/closed as usual.
                    onDragStopped = { velocity ->
                        if (velocity > 1800f && progressAnim.value < 0.15f) {
                            playerController.stop()
                            controller.clear()
                        } else settle(velocity)
                    }
                )
        )

        // ── Full player — fixed full-screen; its elements morph out of the mini bar. When
        // fully collapsed it's parked offscreen so the app underneath stays interactive.
        if (target != null) {
            val nested = rememberNavController()
            LaunchedEffect(target) {
                nested.navigate(
                    "player?bookId=${target!!.bookId}&groupId=${target!!.groupId}" +
                    "&startInfo=${target!!.startInfo}&startPlaying=${target!!.startPlaying}"
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
                        translationY = if (progressAnim.value <= 0.001f) heightPx.toFloat() else 0f
                    }
                    // draggable on the container — activates only after the touch-slop
                    // threshold, so buttons/menus inside still receive their own taps.
                    .draggable(
                        enabled = expanded,
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                progressAnim.snapTo((progressAnim.value - delta / travelPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragStopped = { velocity -> settle(velocity) }
                    )
            ) {
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
                            route = "player?bookId={bookId}&groupId={groupId}&startInfo={startInfo}&startPlaying={startPlaying}",
                            arguments = listOf(
                                navArgument("bookId") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("groupId") { type = NavType.LongType; defaultValue = -1L },
                                navArgument("startInfo") { type = NavType.BoolType; defaultValue = false },
                                navArgument("startPlaying") { type = NavType.BoolType; defaultValue = true }
                            )
                        ) {
                            PlayerContent(
                                onCollapse = { controller.collapse() },
                                initiallyShowInfo = it.arguments?.getBoolean("startInfo") ?: false,
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
    expandProgress: androidx.compose.runtime.State<Float>? = null
) {
    // The mini content disappears the instant the full player's morphing counterparts (which
    // start exactly on top of it) take over — so the cover/title/play button visibly TRAVEL.
    val handOff = Modifier.graphicsLayer {
        alpha = if ((expandProgress?.value ?: 0f) > 0.02f) 0f else 1f
    }
    Surface(
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(MINI_HEIGHT_DP.dp)
            .pressScale(enabled = enabled)
            .clickable(enabled = enabled, onClick = onTap)
    ) {
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
}
