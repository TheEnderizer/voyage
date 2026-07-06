package com.betteraudio.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds a [ColorScheme] for [coverPath] by recolouring [base] (the app's brand scheme) with
 * the cover's dominant colours: accents come from the art, while the warm dark/light surfaces
 * are kept and tinted slightly toward the accent so the whole app shifts with the playing book.
 * Returns null until the bitmap is decoded, or if [coverPath] is null/unreadable.
 */
@Composable
fun rememberCoverScheme(
    coverPath: String?,
    base: ColorScheme,
    darkTheme: Boolean,
    // Immersive theme tints text (on*) colours toward the cover accent too; Material You keeps
    // the base text colours for stock M3 contrast.
    tintText: Boolean = true
): ColorScheme? {
    var scheme by remember(coverPath, darkTheme, base, tintText) { mutableStateOf<ColorScheme?>(null) }

    LaunchedEffect(coverPath, darkTheme, base, tintText) {
        if (coverPath.isNullOrBlank()) { scheme = null; return@LaunchedEffect }
        val palette = withContext(Dispatchers.IO) {
            try {
                val file = File(coverPath)
                if (!file.exists()) return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                val bmp = BitmapFactory.decodeFile(coverPath, opts) ?: return@withContext null
                Palette.from(bmp).maximumColorCount(16).generate()
            } catch (_: Exception) { null }
        } ?: run { scheme = null; return@LaunchedEffect }
        scheme = base.recolouredFrom(palette, darkTheme, tintText)
    }

    return scheme
}

/** Animates [target] toward — used so the whole app transitions smoothly between books. */
@Composable
fun rememberAnimatedScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(700)
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "tertiary")
    val background by animateColorAsState(target.background, spec, label = "background")
    val onBackground by animateColorAsState(target.onBackground, spec, label = "onBackground")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")
    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow
    )
}

private fun ColorScheme.recolouredFrom(palette: Palette, dark: Boolean, tintText: Boolean): ColorScheme {
    val fallback = if (dark) 0xFFFFA552.toInt() else 0xFFE07B3E.toInt()
    // Prefer light swatches so accents are legible on the dark background
    val primary = palette.lightVibrantSwatch?.rgb?.let { Color(it) }
        ?: palette.vibrantSwatch?.rgb?.let { Color(it) }
        ?: palette.lightMutedSwatch?.rgb?.let { Color(it) }
        ?: Color(palette.getDominantColor(fallback))
    val secondary = Color(palette.getMutedColor(primary.toArgb()))
    val tertiary = Color(palette.getLightVibrantColor(palette.getDarkVibrantColor(primary.toArgb())))

    val bgTint = if (dark) 0.13f else 0.07f
    val surfTint = if (dark) 0.17f else 0.10f

    // Text follows the cover too (Immersive only): body text shifts toward a legible tint of
    // the accent (lightened on dark, darkened on light), muted text a touch more so hierarchy
    // holds. With tintText off the base text colours pass straight through.
    val textAccent = if (dark) primary.lighten(0.62f) else primary.darken(0.5f)
    val mutedAccent = if (dark) primary.lighten(0.45f) else primary.darken(0.38f)
    val tintedOn = if (tintText) lerp(onSurface, textAccent, 0.35f) else onSurface
    val tintedOnMuted = if (tintText) lerp(onSurfaceVariant, mutedAccent, 0.42f) else onSurfaceVariant
    val tintedOnSecondaryContainer =
        if (tintText) lerp(onSecondaryContainer, textAccent, 0.35f) else onSecondaryContainer

    return copy(
        primary = primary,
        onPrimary = onColorFor(primary),
        primaryContainer = if (dark) primary.darken(0.5f) else primary.lighten(0.55f),
        onPrimaryContainer = if (dark) primary.lighten(0.72f) else primary.darken(0.55f),
        secondary = secondary,
        onSecondary = onColorFor(secondary),
        secondaryContainer = if (dark) secondary.darken(0.5f) else secondary.lighten(0.6f),
        onSecondaryContainer = tintedOnSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onColorFor(tertiary),
        background = lerp(background, primary, bgTint),
        onBackground = tintedOn,
        surface = lerp(surface, primary, bgTint),
        onSurface = tintedOn,
        surfaceVariant = lerp(surfaceVariant, primary, surfTint),
        onSurfaceVariant = tintedOnMuted,
        surfaceContainer = lerp(surfaceContainer, primary, surfTint),
        surfaceContainerHigh = lerp(surfaceContainerHigh, primary, surfTint),
        surfaceContainerHighest = lerp(surfaceContainerHighest, primary, surfTint),
        surfaceContainerLow = lerp(surfaceContainerLow, primary, surfTint * 0.7f),
        surfaceContainerLowest = lerp(surfaceContainerLowest, primary, bgTint)
    )
}

private fun onColorFor(c: Color): Color =
    if (c.luminance() > 0.5f) Color.Black else Color.White

private fun Color.darken(f: Float) =
    copy(red = red * (1 - f), green = green * (1 - f), blue = blue * (1 - f))

private fun Color.lighten(f: Float) =
    copy(red = red + (1 - red) * f, green = green + (1 - green) * f, blue = blue + (1 - blue) * f)
