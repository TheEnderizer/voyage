package com.betteraudio.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.ui.immersive.HeroWindowState
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.immersive.LocalHeroWindow
import com.betteraudio.ui.immersive.heroScrim
import com.betteraudio.ui.immersive.heroWindowMask
import com.betteraudio.ui.immersive.rememberCoverIsLight
import java.io.File
import kotlin.math.roundToInt

/**
 * App-wide background: the currently-playing (or last-played) book cover under a very heavy
 * blur, veiled toward the theme background so foreground content stays readable. Sits behind
 * the NavHost; screens that want it visible use a transparent container color.
 *
 * Immersive-only in practice — `MainActivity` renders it solely under `AppTheme.IMMERSIVE`, and
 * this is its only call site — which is why it reads its veil from `ImmersiveStyle` despite
 * living in the shared `ui/components` package. Material You is unaffected by anything here.
 *
 * Prefers the same pre-baked blurred+reflected composite the player/book-info/series screens use
 * (see CoverEffectBaker / ReflectedProgressiveBlurCover) — same image everywhere the immersive
 * theme shows a cover backdrop, and the same top-anchored placement, so the sharp artwork's
 * center consistently sits ~1/4 down from the top across all of them. Only when no bake exists
 * yet does this fall back to live-blurring the sharp cover (API 31+; below that, just the tinted
 * theme background — a sharp cover behind everything would hurt readability).
 */
/** How soft the app-wide backdrop sits. Also the radius Home's clear window ramps *up to* — at
 *  which point the window has become the backdrop and stops being drawn at all. */
private val BACKDROP_BLUR = 10.dp

/** Quantised so a scroll costs a bounded number of recompositions — see the call site. */
@Composable
private fun windowBlurRadius(hero: HeroWindowState): Dp {
    val steps = BACKDROP_BLUR.value.toInt().coerceAtLeast(1)
    val step by remember {
        derivedStateOf { (hero.scrolledFraction.floatValue.coerceIn(0f, 1f) * steps).roundToInt() }
    }
    return step.dp
}

@Composable
fun AppBlurredBackdrop(coverPath: String?, bakedPath: String? = null, modifier: Modifier = Modifier) {
    val hero = LocalHeroWindow.current
    val density = LocalDensity.current
    // Drives whether the hero band darkens at all — see the scrim below.
    val coverIsLight = rememberCoverIsLight(coverPath)
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // AN-13 (Gate AN): remember'd so this File.exists() stat only runs when bakedPath itself
        // changes (roughly once per book), not on every recomposition — matches the same pattern
        // at ReflectedProgressiveBlurCover.kt.
        val baked = remember(bakedPath) { bakedPath?.takeIf { File(it).exists() } }
        // Nothing to draw a cover from (fresh install / nothing ever played, or pre-API-31 with
        // no bake): a soft theme-tinted wash instead of a flat wall of `background`, so the
        // Immersive look still has some depth before the first book plays. It sits under the
        // 0.78-alpha background tint below, which mutes it into a subtle gradient.
        val hasArt = baked != null || (coverPath != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        if (!hasArt) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        0.45f to MaterialTheme.colorScheme.background,
                        1f to MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                )
            )
        }
        if (baked != null) {
            Crossfade(targetState = baked, animationSpec = tween(600), label = "appBackdropBaked") { path ->
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    // The bake itself is already blurred (see CoverEffectBaker) — the player/
                    // book-info/series screens show it as-is, but Immersive's app-wide backdrop
                    // wants to read a bit softer still, so a little extra blur goes on TOP of the
                    // bake here only (this composable, not ReflectedProgressiveBlurCover itself).
                    val overscan = Modifier.graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }
                    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    val extraBlurModifier =
                        if (canBlur) overscan.blur(BACKDROP_BLUR) else Modifier
                    ReflectedProgressiveBlurCover(
                        coverPath = coverPath,
                        bakedPath = path,
                        modifier = Modifier.fillMaxWidth().then(extraBlurModifier)
                    )

                    // ── Home's clear window ──────────────────────────────────────────────
                    // A SECOND copy of the exact same image, with the exact same modifier chain
                    // and therefore the exact same geometry — only its blur radius differs, and a
                    // mask limits it to the top of the screen. That is what makes the window read
                    // as "this part of the background is in focus" rather than as a separate cover
                    // image sitting on top: there is no crop, scale or offset that could disagree
                    // with the blurred copy underneath.
                    //
                    // The radius is QUANTISED to whole dp. Modifier.blur takes a plain Dp, so it
                    // cannot be read in a deferred draw lambda the way the rest of this file's
                    // scroll-driven values are; quantising caps the recompositions across an entire
                    // scroll at BACKDROP_BLUR's dp value (~10) instead of one per frame. The
                    // AsyncImage inside is served from Coil's memory cache, so those are cheap.
                    if (canBlur && hero.active) {
                        val windowBlur = windowBlurRadius(hero)
                        if (windowBlur < BACKDROP_BLUR) {
                            ReflectedProgressiveBlurCover(
                                coverPath = coverPath,
                                bakedPath = path,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(overscan)
                                    .blur(windowBlur)
                                    .heroWindowMask(hero, fadePx = with(density) { 56.dp.toPx() })
                            )
                        }
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Crossfade(targetState = coverPath, animationSpec = tween(600), label = "appBackdrop") { path ->
                if (path != null) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            // Overscan so the blur's clamped edges are cropped away.
                            .graphicsLayer { scaleX = 1.18f; scaleY = 1.18f }
                            .blur(28.dp)
                    )
                }
            }
        }
        // Veil the cover back toward the theme background for foreground legibility. This used to
        // be a FLAT sheet at alpha 0.78 — which left the artwork about 22% visible and made the
        // "immersive" cover read as grey-blue fog on every screen. It is now a top-light,
        // bottom-heavy gradient (see ImmersiveStyle.backdropVeil): the artwork keeps its strength
        // where it is the subject, and the veil only gets heavy toward the bottom, where the
        // library grid and the docked player/nav glass need a dark ground to sit on.
        //
        // The hero scrim rides on top of the veil: it darkens the band the hero's title and the
        // status tabs sit in, and fades out just below the tab row instead of ending on a line.
        Box(
            Modifier
                .fillMaxSize()
                .background(ImmersiveStyle.backdropVeil())
                .then(
                    // Only over LIGHT artwork. A dark cover already gives near-white text all the
                    // contrast it needs, and darkening it further just muddies a cover that was
                    // fine — the band would be doing nothing but dulling the picture.
                    if (hero.active && coverIsLight)
                        Modifier.heroScrim(hero, ImmersiveStyle.coverInk(), maxAlpha = 0.62f)
                    else Modifier
                )
        )
    }
}
