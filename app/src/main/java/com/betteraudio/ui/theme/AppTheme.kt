package com.betteraudio.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

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

/** Provided by [VoyageTheme]; lets any composable branch its styling per theme. Used by the
 *  per-theme router composables in the `ui.material` and `ui.immersive` packages — see
 *  CLAUDE.md's theming section for the split convention. The old shared `immersive()`,
 *  `appSurfaceColor()`, `appCardColor()`, `appCardHighColor()`, `scrimTextColor()` helpers were
 *  removed once every branching screen was split; their equivalents now live in `MaterialStyle`
 *  and `ImmersiveStyle`. */
val LocalAppTheme = compositionLocalOf { AppTheme.MATERIAL_YOU }
