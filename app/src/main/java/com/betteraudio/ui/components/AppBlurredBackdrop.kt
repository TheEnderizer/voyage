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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * App-wide background: the currently-playing (or last-played) book cover under a very heavy
 * blur, dimmed toward the theme background so foreground content stays readable. Sits behind
 * the NavHost; screens that want it visible use a transparent container color.
 *
 * Prefers the same pre-baked blurred+reflected composite the player/book-info/series screens use
 * (see CoverEffectBaker / ReflectedProgressiveBlurCover) — same image everywhere the immersive
 * theme shows a cover backdrop, and the same top-anchored placement, so the sharp artwork's
 * center consistently sits ~1/4 down from the top across all of them. Only when no bake exists
 * yet does this fall back to live-blurring the sharp cover (API 31+; below that, just the tinted
 * theme background — a sharp cover behind everything would hurt readability).
 */
@Composable
fun AppBlurredBackdrop(coverPath: String?, bakedPath: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val baked = bakedPath?.takeIf { File(it).exists() }
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
                    val extraBlurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Modifier
                            // Overscan so the blur's clamped edges don't show at the frame edge.
                            .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f }
                            .blur(10.dp)
                    } else Modifier
                    ReflectedProgressiveBlurCover(
                        coverPath = coverPath,
                        bakedPath = path,
                        modifier = Modifier.fillMaxWidth().then(extraBlurModifier)
                    )
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
        // Tint back toward the theme background for foreground legibility.
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.78f))
        )
    }
}
