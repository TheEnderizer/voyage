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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

    /** Open a book/group in the full player (expands the sheet). */
    fun open(bookId: Long = -1L, groupId: Long = -1L, startInfo: Boolean = false, startPlaying: Boolean = true) {
        target = PlayerTarget(bookId, groupId, startInfo, startPlaying)
        expandToken++
    }

    fun expandCurrent() { if (target != null) expandToken++ }
    fun collapse() { collapseToken++ }
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
    modifier: Modifier = Modifier
) {
    val playback by playerController.playbackState.collectAsStateWithLifecycle()
    val target = controller.target

    // Mirror the playing book into the target so the mini bar is ready to expand.
    LaunchedEffect(playback.bookId, playback.groupId) {
        if (playback.bookId != -1L) controller.prime(playback.bookId, playback.groupId)
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

    val expanded = progressAnim.value > 0.5f
    LaunchedEffect(expanded) { controller.setExpanded(expanded) }


    Box(modifier.fillMaxSize().onSizeChanged { heightPx = it.height }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = travelPx * (1f - progressAnim.value) }
        ) {
            // ── Full player (fades in as it expands) ───────────────────────────
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
                        .graphicsLayer { alpha = progressAnim.value }
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
                            onDragStopped = { velocity ->
                                // velocity > 0 = downward fling → collapse
                                val goExpand = velocity < -1000f || (velocity <= 1000f && progressAnim.value > 0.5f)
                                scope.launch {
                                    progressAnim.animateTo(
                                        if (goExpand) 1f else 0f,
                                        spring(dampingRatio = 0.85f, stiffness = 380f)
                                    )
                                }
                            }
                        )
                ) {
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
                                startPlaying = it.arguments?.getBoolean("startPlaying") ?: true
                            )
                        }
                    }
                }
            }

            // ── Mini bar (fades out as it expands; only interactive when collapsed) ──
            MiniPlayerBar(
                title = if (playback.groupId != -1L) playback.groupName else playback.bookTitle,
                coverPath = playback.coverArtUri?.removePrefix("file://"),
                isPlaying = playback.isPlaying,
                progress = if (playback.bookTotalDurationMs > 0)
                    (playback.bookPositionMs.toFloat() / playback.bookTotalDurationMs).coerceIn(0f, 1f) else 0f,
                enabled = !expanded,
                onTap = {
                    if (playback.groupId != -1L) controller.open(groupId = playback.groupId)
                    else if (playback.bookId != -1L) controller.open(bookId = playback.bookId)
                    else controller.expandCurrent()
                },
                onPlayPause = { playerController.togglePlayPause() },
                onSkip = { playerController.skipForward() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = (1f - progressAnim.value * 2f).coerceIn(0f, 1f) }
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            scope.launch {
                                progressAnim.snapTo((progressAnim.value - delta / travelPx).coerceIn(0f, 1f))
                            }
                        },
                        onDragStopped = { velocity ->
                            val goExpand = velocity < -1000f || (velocity <= 1000f && progressAnim.value > 0.5f)
                            scope.launch {
                                progressAnim.animateTo(if (goExpand) 1f else 0f,
                                    spring(dampingRatio = 0.85f, stiffness = 380f))
                            }
                        }
                    )
            )
        }
    }
}

@Composable
private fun MiniPlayerBar(
    title: String,
    coverPath: String?,
    isPlaying: Boolean,
    progress: Float,
    enabled: Boolean,
    onTap: () -> Unit,
    onPlayPause: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                Modifier.fillMaxSize().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
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
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onPlayPause, enabled = enabled) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play/pause")
                }
                IconButton(onClick = onSkip, enabled = enabled) {
                    Icon(Icons.Default.FastForward, "Skip forward")
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp).align(Alignment.BottomCenter),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.Transparent
            )
        }
    }
}
