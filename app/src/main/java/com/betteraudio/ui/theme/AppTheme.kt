package com.betteraudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The two app-wide looks, chosen by the user on first launch (and in Settings → Theme):
 * - [MATERIAL_YOU] — opaque tonal Material 3 surfaces; colors from the system wallpaper
 *   (Android 12+) or the playing book's cover, per [ThemeColorSource].
 * - [IMMERSIVE] — the cover-driven look: the playing cover fills the whole app behind a heavy
 *   blur, screens are translucent over it, and ALL text is tinted from the cover accent.
 */
enum class AppTheme {
    MATERIAL_YOU, IMMERSIVE;

    companion object {
        /** "" (not chosen yet) and unknown values render as MATERIAL_YOU behind the prompt. */
        fun from(s: String): AppTheme = entries.find { it.name == s } ?: MATERIAL_YOU
    }
}

/** Where the Material You theme takes its colors from. */
enum class ThemeColorSource {
    WALLPAPER, COVER,
    // A user-picked color: a preset, a hex value, or a custom 4-role palette from the Theme
    // Creator (see SettingsStore.customThemeColor / ThemeSeedPaletteCodec).
    CUSTOM;

    companion object {
        fun from(s: String): ThemeColorSource = entries.find { it.name == s } ?: WALLPAPER
    }
}

/** Dark-mode preference; AUTO follows the system setting. Applies to both app looks. */
enum class DarkMode {
    ON, OFF, AUTO;

    companion object {
        fun from(s: String): DarkMode = entries.find { it.name == s } ?: AUTO
    }
}

/** Provided by [VoyageTheme]; lets any composable branch its styling per theme. */
val LocalAppTheme = compositionLocalOf { AppTheme.MATERIAL_YOU }

@Composable
fun immersive(): Boolean = LocalAppTheme.current == AppTheme.IMMERSIVE

/** Scaffold/TopAppBar fill: transparent in Immersive (the blurred cover shows through). */
@Composable
fun appSurfaceColor(): Color =
    if (immersive()) Color.Transparent else MaterialTheme.colorScheme.background

/** Card/row fill: frosted translucent over the blur in Immersive, tonal surface otherwise. */
@Composable
fun appCardColor(): Color =
    if (immersive()) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f)
    else MaterialTheme.colorScheme.surfaceContainer

/** Elevated card/row fill (dialogs, raised rows). */
@Composable
fun appCardHighColor(): Color =
    if (immersive()) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    else MaterialTheme.colorScheme.surfaceContainerHigh

/**
 * Text drawn over dark cover scrims (player pages, grid-card gradients). In Immersive it is
 * near-white pulled toward the cover accent so ALL text follows the theme colour; in Material
 * You it stays plain white for stock M3 contrast.
 */
@Composable
fun scrimTextColor(muted: Boolean = false): Color {
    val accent = MaterialTheme.colorScheme.primary
    return if (immersive()) {
        if (muted) lerp(Color.White, accent, 0.30f).copy(alpha = 0.68f)
        else lerp(Color.White, accent, 0.22f)
    } else {
        if (muted) Color.White.copy(alpha = 0.68f) else Color.White
    }
}
