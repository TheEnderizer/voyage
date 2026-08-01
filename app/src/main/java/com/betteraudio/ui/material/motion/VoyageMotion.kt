package com.betteraudio.ui.material.motion

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.compositionLocalOf

/**
 * Material You's motion vocabulary — the one place every spec under the `ui.material` package
 * tree and the shared morph primitives (see [ContainerMorph]) should draw from, instead of the
 * dozen ad-hoc `tween()`/`spring()` literals this replaces.
 *
 * These values are the Material 3 Expressive spring tokens, copied by hand rather than consumed
 * from `MaterialTheme.motionScheme` — verified against the resolved material3 1.4.0 sources (the
 * version Compose BOM `2026.06.00` pins): `MotionScheme`, `LocalMotionScheme`, and
 * `MaterialExpressiveTheme` are all declared `internal` there (`MotionScheme.kt:42`,
 * `MaterialTheme.kt:141/155/185`), so the `ExperimentalMaterial3ExpressiveApi` opt-in already in
 * `app/build.gradle.kts` does not unlock them. The app's pre-existing [com.betteraudio.ui.theme.MotionTokens]
 * (now Immersive-only) already approximated the Expressive *default* tier (spatial 0.82/380 vs.
 * 0.8/380, effects 1.0/1600 exact) — what this adds is the missing fast/slow tiers plus one
 * shared vocabulary for Material You, not a different feel.
 */
object VoyageMotionTokens {
    // Spatial — position/size/shape changes. Slight overshoot, "alive" motion.
    const val SpatialDefaultDamping = 0.8f
    const val SpatialDefaultStiffness = 380f
    const val SpatialFastDamping = 0.6f
    const val SpatialFastStiffness = 800f
    const val SpatialSlowDamping = 0.8f
    const val SpatialSlowStiffness = 200f

    // Effects — alpha/color changes. Critically damped, no overshoot.
    const val EffectsDefaultDamping = Spring.DampingRatioNoBouncy
    const val EffectsDefaultStiffness = 1600f
    const val EffectsFastDamping = Spring.DampingRatioNoBouncy
    const val EffectsFastStiffness = 3800f
    const val EffectsSlowDamping = Spring.DampingRatioNoBouncy
    const val EffectsSlowStiffness = 800f

    // Press/release feedback — not an Expressive token; carried over from the app's own
    // MotionTokens.pressDamping/pressStiffness (≈ Expressive's fast-spatial tier already).
    const val PressDamping = 0.6f
    const val PressStiffness = 900f
}

/** One resolved motion spec per role, as [Float] specs (the common case — [androidx.compose.animation.core.animateFloatAsState],
 *  `graphicsLayer` scale/alpha, etc.). For any other animated type (a `Dp` position/size, a
 *  `Color`, …), use the `*Of<T>()` generic functions below instead — same damping/stiffness,
 *  typed for what `animateXAsState` actually needs. */
class VoyageMotion internal constructor() {
    val spatialDefault = spring<Float>(VoyageMotionTokens.SpatialDefaultDamping, VoyageMotionTokens.SpatialDefaultStiffness)
    val spatialFast = spring<Float>(VoyageMotionTokens.SpatialFastDamping, VoyageMotionTokens.SpatialFastStiffness)
    val spatialSlow = spring<Float>(VoyageMotionTokens.SpatialSlowDamping, VoyageMotionTokens.SpatialSlowStiffness)
    val effectsDefault = spring<Float>(VoyageMotionTokens.EffectsDefaultDamping, VoyageMotionTokens.EffectsDefaultStiffness)
    val effectsFast = spring<Float>(VoyageMotionTokens.EffectsFastDamping, VoyageMotionTokens.EffectsFastStiffness)
    val effectsSlow = spring<Float>(VoyageMotionTokens.EffectsSlowDamping, VoyageMotionTokens.EffectsSlowStiffness)
    val press = spring<Float>(VoyageMotionTokens.PressDamping, VoyageMotionTokens.PressStiffness)

    fun <T> spatialDefaultOf(): FiniteAnimationSpec<T> =
        spring(VoyageMotionTokens.SpatialDefaultDamping, VoyageMotionTokens.SpatialDefaultStiffness)
    fun <T> spatialFastOf(): FiniteAnimationSpec<T> =
        spring(VoyageMotionTokens.SpatialFastDamping, VoyageMotionTokens.SpatialFastStiffness)
    fun <T> spatialSlowOf(): FiniteAnimationSpec<T> =
        spring(VoyageMotionTokens.SpatialSlowDamping, VoyageMotionTokens.SpatialSlowStiffness)
    fun <T> effectsDefaultOf(): FiniteAnimationSpec<T> =
        spring(VoyageMotionTokens.EffectsDefaultDamping, VoyageMotionTokens.EffectsDefaultStiffness)
}

private val defaultVoyageMotion = VoyageMotion()

/** Material You's motion vocabulary. Immersive doesn't provide this — it keeps reading
 *  [com.betteraudio.ui.theme.MotionTokens] directly, per the theme-split convention. */
val LocalVoyageMotion = compositionLocalOf { defaultVoyageMotion }
