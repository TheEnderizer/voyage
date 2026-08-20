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
 *
 * [accentOverride] is the colour the user pinned for this cover in Settings. When set it replaces
 * the automatic swatch choice outright; everything derived from the accent (surfaces, containers,
 * tinted text) still follows, so pinning recolours the whole app the way an automatic pick would.
 */
@Composable
fun rememberCoverScheme(
    coverPath: String?,
    base: ColorScheme,
    darkTheme: Boolean,
    // Immersive theme tints text (on*) colours toward the cover accent too; Material You keeps
    // the base text colours for stock M3 contrast.
    tintText: Boolean = true,
    accentOverride: Color? = null
): ColorScheme? {
    var scheme by remember(coverPath, darkTheme, base, tintText, accentOverride) {
        mutableStateOf<ColorScheme?>(null)
    }

    LaunchedEffect(coverPath, darkTheme, base, tintText, accentOverride) {
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
        scheme = base.recolouredFrom(palette, darkTheme, tintText, accentOverride)
    }

    return scheme
}

private fun ColorScheme.recolouredFrom(
    palette: Palette,
    dark: Boolean,
    tintText: Boolean,
    accentOverride: Color? = null
): ColorScheme {
    val fallback = if (dark) 0xFFFFA552.toInt() else 0xFFE07B3E.toInt()
    // A pinned accent short-circuits the chain below. That chain is exactly where the instability
    // lives — each `?:` is a hard switch to a different swatch category, so a cover that only just
    // yields a lightVibrant swatch on one decode can land somewhere else entirely on the next.
    val primary = accentOverride
        // Prefer light swatches so accents are legible on the dark background
        ?: palette.lightVibrantSwatch?.rgb?.let { Color(it) }
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
