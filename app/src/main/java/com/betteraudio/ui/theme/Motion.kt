package com.betteraudio.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.NavBackStackEntry

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
 */
fun Modifier.pressScale(pressedScale: Float = 0.96f, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    return composed {
        var pressed by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = MotionTokens.floatPress,
            label = "pressScale"
        )
        graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
    }
}

// ── NavHost transition builders ───────────────────────────────────────────────
// Usage: composable(enterTransition = { colorOsEnter() }, exitTransition = { colorOsExit() }, ...)

fun AnimatedContentTransitionScope<NavBackStackEntry>.colorOsEnter(): EnterTransition =
    fadeIn(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleIn(initialScale = 0.92f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)) +
    slideInVertically(
        initialOffsetY = { (it * 0.04f).toInt() },
        animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.colorOsExit(): ExitTransition =
    fadeOut(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleOut(targetScale = 0.96f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

fun AnimatedContentTransitionScope<NavBackStackEntry>.colorOsPopEnter(): EnterTransition =
    fadeIn(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleIn(initialScale = 0.96f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

fun AnimatedContentTransitionScope<NavBackStackEntry>.colorOsPopExit(): ExitTransition =
    fadeOut(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleOut(targetScale = 0.92f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)) +
    slideOutVertically(
        targetOffsetY = { (it * 0.04f).toInt() },
        animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)
    )
