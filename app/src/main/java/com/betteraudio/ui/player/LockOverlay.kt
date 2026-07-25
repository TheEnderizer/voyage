package com.betteraudio.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val UNLOCK_HOLD_MS = 1_500L

/**
 * Blocks all touches to the player underneath (accidental taps in a pocket/bag, or a toddler's
 * hands) while playback keeps running normally — playback stays controllable from the
 * notification, lock screen, and widget. The caller (PlayerScreen) also hides the on-screen
 * transport/scrubber/secondary-action rows while [locked], keeping only the title and a
 * read-only progress indicator visible underneath this overlay. A long press on this overlay
 * unlocks; system back is consumed instead of collapsing the player, so a stray back gesture
 * can't dismiss it either.
 *
 * Purely a UI-layer lock, not tied to playback state — [locked] is owned by the caller
 * (PlayerScreen), reset when the player closes.
 *
 * The hold-to-unlock gesture is detected across the ENTIRE overlay (not just the visible
 * indicator), so [alignment]/[contentPadding]/[contentColor] only reposition/restyle where the
 * decorative indicator+label are drawn — e.g. Material You's player places it where the
 * transport/secondary-action rows sit, so it smoothly appears in the space those vacate on lock,
 * instead of this overlay's own default bottom-of-screen placement (Immersive keeps that).
 */
@Composable
fun LockOverlay(
    locked: Boolean,
    onUnlock: () -> Unit,
    alignment: Alignment = Alignment.BottomCenter,
    contentPadding: PaddingValues = PaddingValues(bottom = 64.dp),
    contentColor: Color = Color.White
) {
    BackHandler(enabled = locked) { /* consume — a stray back gesture must not collapse the player while locked */ }

    AnimatedVisibility(visible = locked, enter = fadeIn(), exit = fadeOut()) {
        var holdProgress by remember { mutableFloatStateOf(0f) }
        var holdJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.001f)) // fully transparent, but an opaque touch target
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                val start = System.currentTimeMillis()
                                while (isActive) {
                                    val elapsed = System.currentTimeMillis() - start
                                    holdProgress = (elapsed / UNLOCK_HOLD_MS.toFloat()).coerceIn(0f, 1f)
                                    if (holdProgress >= 1f) {
                                        onUnlock()
                                        return@launch
                                    }
                                    delay(16)
                                }
                            }
                            tryAwaitRelease()
                            holdJob?.cancel()
                            holdProgress = 0f
                        }
                    )
                },
            contentAlignment = alignment
        ) {
            Column(
                Modifier.padding(contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { holdProgress },
                        modifier = Modifier.size(56.dp),
                        color = contentColor,
                        trackColor = contentColor.copy(alpha = 0.25f)
                    )
                    Icon(
                        if (holdProgress > 0f) Icons.Default.LockOpen else Icons.Default.Lock,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Hold to unlock",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.85f)
                )
            }
        }
    }
}
