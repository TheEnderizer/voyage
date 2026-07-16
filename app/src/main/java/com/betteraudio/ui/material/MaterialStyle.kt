package com.betteraudio.ui.material

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Style constants for the Material You-theme screen variants (the `ui.material` package). Values
 * copied verbatim from the old shared helpers in `ui/theme/AppTheme.kt` (`appSurfaceColor`,
 * `appCardColor`, `appCardHighColor`, `scrimTextColor`) so the split screens render
 * pixel-identically to before. This is the one place to change if Material You's look should
 * diverge from Immersive's going forward — see CLAUDE.md's theming section for the split convention.
 */
object MaterialStyle {
    /** Scaffold/TopAppBar fill: the opaque tonal background. */
    @Composable
    fun surfaceColor(): Color = MaterialTheme.colorScheme.background

    /** Card/row fill: the standard tonal surface. */
    @Composable
    fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainer

    /** Elevated card/row fill (dialogs, raised rows). */
    @Composable
    fun cardHighColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

    /** Text drawn over dark cover scrims: plain white for stock M3 contrast. */
    @Composable
    fun scrimText(muted: Boolean = false): Color =
        if (muted) Color.White.copy(alpha = 0.68f) else Color.White
}
