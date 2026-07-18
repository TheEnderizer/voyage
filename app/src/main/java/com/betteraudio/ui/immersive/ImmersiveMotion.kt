package com.betteraudio.ui.immersive

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import com.betteraudio.ui.theme.MotionTokens

/**
 * Immersive-theme NavHost screen transitions — unchanged from the original shared `colorOs*`
 * builders (see CLAUDE.md's theming split convention: the animation layer used to be shared
 * between themes; this is the Immersive half after the split, kept pixel/timing-identical to
 * what both themes used before).
 */

fun AnimatedContentTransitionScope<NavBackStackEntry>.immersiveEnter(): EnterTransition =
    fadeIn(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleIn(initialScale = 0.92f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)) +
    slideInVertically(
        initialOffsetY = { (it * 0.04f).toInt() },
        animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)
    )

fun AnimatedContentTransitionScope<NavBackStackEntry>.immersiveExit(): ExitTransition =
    fadeOut(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleOut(targetScale = 0.96f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

fun AnimatedContentTransitionScope<NavBackStackEntry>.immersivePopEnter(): EnterTransition =
    fadeIn(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleIn(initialScale = 0.96f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness))

fun AnimatedContentTransitionScope<NavBackStackEntry>.immersivePopExit(): ExitTransition =
    fadeOut(animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness)) +
    scaleOut(targetScale = 0.92f, animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)) +
    slideOutVertically(
        targetOffsetY = { (it * 0.04f).toInt() },
        animationSpec = spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)
    )

/** Mini player bar collapse-to-mini fade fraction: the pill fades out as the full player takes
 *  over (crossfade look) — Immersive keeps this; Material You grows the pill instead (see
 *  MaterialMotion.kt). */
const val IMMERSIVE_MINI_BAR_FADE_RATE = 2.5f
