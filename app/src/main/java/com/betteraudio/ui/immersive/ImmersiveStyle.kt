package com.betteraudio.ui.immersive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Style constants for the Immersive-theme screen variants (the `ui.immersive` package). Values
 * copied verbatim from the old shared helpers in `ui/theme/AppTheme.kt` (`appSurfaceColor`,
 * `appCardColor`, `appCardHighColor`, `scrimTextColor`) so the split screens render
 * pixel-identically to before. This is the one place to change if Immersive's look should diverge
 * from Material You's going forward — see CLAUDE.md's theming section for the split convention.
 */
object ImmersiveStyle {
    /** Scaffold/TopAppBar fill: transparent so the blurred cover backdrop shows through. */
    @Composable
    fun surfaceColor(): Color = Color.Transparent

    /** Card/row fill: frosted translucent over the blur. */
    @Composable
    fun cardColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f)

    /** Elevated card/row fill (dialogs, raised rows). */
    @Composable
    fun cardHighColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)

    /** Dropdown/context menu fill: near-opaque frosted — menus float directly over cover art, so
     *  they need to stay readable while still belonging to the translucent Immersive family. */
    @Composable
    fun menuColor(): Color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f)

    /** Alert dialog fill: near-opaque frosted. Dialogs already sit over the system dim scrim, so
     *  a whisper of translucency is enough to keep them in the family without hurting reading. */
    @Composable
    fun dialogColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.93f)

    /** Text drawn over dark cover scrims: near-white pulled toward the cover accent so all text
     *  follows the theme color. */
    @Composable
    fun scrimText(muted: Boolean = false): Color {
        val accent = MaterialTheme.colorScheme.primary
        return if (muted) lerp(Color.White, accent, 0.30f).copy(alpha = 0.68f)
               else lerp(Color.White, accent, 0.22f)
    }
}
