package com.betteraudio.ui.theme

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InspectorInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object MotionTokens {
    /** Page/element motion — slight overshoot then settle. */
    const val spatialDamping   = 0.82f
    const val spatialStiffness = 380f
    /** Alpha/fade — critically damped (no overshoot). */
    const val effectsDamping   = Spring.DampingRatioNoBouncy
    const val effectsStiffness = 1600f
    /** Press/release feedback — pronounced overshoot on release. */
    const val pressDamping     = 0.6f
    const val pressStiffness   = 900f

    val floatSpatial = spring<Float>(dampingRatio = spatialDamping,  stiffness = spatialStiffness)
    val floatEffects = spring<Float>(dampingRatio = effectsDamping,  stiffness = effectsStiffness)
    val floatPress   = spring<Float>(dampingRatio = pressDamping,    stiffness = pressStiffness)
}

/**
 * Scales the composable down on press and springs back with an overshoot on release.
 * Stacks cleanly with clickable/combinedClickable — does not consume pointer events.
 *
 * Modifier.Node-based (not `composed {}`) so the modifier chain stays comparable across
 * recompositions — a `composed {}` factory makes the whole chain uncomparable, which prevents a
 * composable receiving it as a parameter from skipping (see AN-7 in the Gate AN plan).
 */
fun Modifier.pressScale(pressedScale: Float = 0.96f, enabled: Boolean = true): Modifier =
    if (!enabled) this else this then PressScaleElement(pressedScale)

private data class PressScaleElement(
    private val pressedScale: Float
) : androidx.compose.ui.node.ModifierNodeElement<PressScaleNode>() {
    override fun create(): PressScaleNode = PressScaleNode(pressedScale)
    override fun update(node: PressScaleNode) {
        node.pressedScale = pressedScale
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "pressScale"
        properties["pressedScale"] = pressedScale
    }
}

private class PressScaleNode(
    var pressedScale: Float
) : androidx.compose.ui.node.DelegatingNode(), androidx.compose.ui.node.LayoutModifierNode {
    private val scale = Animatable(1f)

    init {
        delegate(
            androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    coroutineScope.launch { scale.animateTo(pressedScale, MotionTokens.floatPress) }
                    waitForUpOrCancellation()
                    coroutineScope.launch { scale.animateTo(1f, MotionTokens.floatPress) }
                }
            }
        )
    }

    override fun androidx.compose.ui.layout.MeasureScope.measure(
        measurable: androidx.compose.ui.layout.Measurable,
        constraints: androidx.compose.ui.unit.Constraints
    ): androidx.compose.ui.layout.MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}

/**
 * Container-transform: morph this composable's bounds to/from the matching player screen so a
 * home now-playing card visibly expands up into the full player (and collapses back). Both the
 * source (card) and target (player root) tag the same `player-<bookId>` key. A no-op when the
 * shared-transition scopes aren't supplied or there's no book to key on, so it stacks safely on
 * any modifier chain.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerContainerTransform(
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    bookId: Long
): Modifier {
    if (sharedScope == null || animScope == null || bookId == -1L) return this
    return with(sharedScope) {
        this@playerContainerTransform.sharedBounds(
            rememberSharedContentState(key = "player-$bookId"),
            animatedVisibilityScope = animScope
        )
    }
}

/**
 * Cover-as-hero transform: the book's cover image is the single shared element that morphs
 * between a home card / grid tile and the full player backdrop. The small cover visibly expands
 * and translates to become the player background (whose top *is* the same cover, so the swap is
 * seamless), while the rest of each screen's content fades via the nav transition. Tag the cover
 * on the source (card / grid tile) and the backdrop on the player with the same `cover-<bookId>`
 * key. No-op when scopes/bookId are absent so it stacks safely.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.coverTransform(
    sharedScope: SharedTransitionScope?,
    animScope: AnimatedVisibilityScope?,
    bookId: Long
): Modifier {
    if (sharedScope == null || animScope == null || bookId == -1L) return this
    return with(sharedScope) {
        this@coverTransform.sharedBounds(
            rememberSharedContentState(key = "cover-$bookId"),
            animatedVisibilityScope = animScope
        )
    }
}

// NavHost screen transitions live per-theme now: ui/material/MaterialMotion.kt and
// ui/immersive/ImmersiveMotion.kt (see CLAUDE.md's theming split convention).

/**
 * Tracks a predictive-back gesture for any dismissible overlay/panel/sheet in the app — not just
 * the top-level home↔player sheet — so back visually "peeks" everywhere, not only on the one
 * screen that had bespoke wiring for it. Returns live progress (0 = settled/not gesturing, 1 =
 * fully committed) for the caller to drive its own scale/fade/slide; [onCommit] fires once the
 * gesture completes (or on a plain, non-gesture back press — [PredictiveBackHandler] handles both
 * transparently), where the caller should perform its actual dismiss/collapse/pop action. On
 * cancel, progress springs back to 0 on its own; this function never dismisses anything itself.
 */
@Composable
fun rememberPredictiveBackProgress(enabled: Boolean, onCommit: () -> Unit): State<Float> {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnCommit by rememberUpdatedState(onCommit)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event -> progress.snapTo(event.progress) }
            currentOnCommit()
        } catch (_: CancellationException) {
            scope.launch { progress.animateTo(0f, MotionTokens.floatSpatial) }
        }
    }
    // Reset if this handler is disabled mid-gesture (e.g. the caller already dismissed via
    // another path) so a stale non-zero progress doesn't linger on the next time it's shown.
    DisposableEffect(enabled) {
        if (!enabled) scope.launch { progress.snapTo(0f) }
        onDispose { }
    }
    return remember { derivedStateOf { progress.value } }
}
