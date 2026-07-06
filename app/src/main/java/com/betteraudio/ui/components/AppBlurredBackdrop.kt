package com.betteraudio.ui.components

import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.io.File

/**
 * App-wide background: the currently-playing (or last-played) book cover under a very heavy
 * gaussian blur, dimmed toward the theme background so foreground content stays readable.
 * Sits behind the NavHost; screens that want it visible use a transparent container color.
 *
 * Modifier.blur needs API 31 (RenderEffect); below that we show just the tinted theme
 * background — a sharp cover behind everything would hurt readability.
 */
@Composable
fun AppBlurredBackdrop(coverPath: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Crossfade(targetState = coverPath, animationSpec = tween(600), label = "appBackdrop") { path ->
                if (path != null) {
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            // Overscan so the blur's clamped edges are cropped away.
                            .graphicsLayer { scaleX = 1.15f; scaleY = 1.15f }
                            .blur(22.dp)
                    )
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
}
