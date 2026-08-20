package com.betteraudio.ui.immersive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
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

    /**
     * The cover's own dark — black pulled toward the cover-derived accent, so every scrim and
     * glass fill in the theme carries the artwork's hue instead of landing neutral grey. This is
     * Law 03 ("colour is borrowed, never chosen") of the cover-first pass; see
     * `docs/immersive-redesign.html`. Use this anywhere a surface used to darken toward
     * [Color.Black].
     */
    @Composable
    fun coverInk(): Color = lerp(Color.Black, MaterialTheme.colorScheme.primary, 0.12f)

    /**
     * The app backdrop's veil, replacing the flat `background @ 0.78` sheet that used to sit over
     * the whole blurred cover (which left the artwork ~22% visible — the thing the cover-first
     * pass exists to fix). Light at the top where the artwork lives, heavy at the bottom where
     * content is dense: legibility comes from a *shape* rather than a wall. Law 01.
     *
     * Read by [com.betteraudio.ui.components.AppBlurredBackdrop], which is Immersive-only by
     * construction (MainActivity renders it solely under `AppTheme.IMMERSIVE`) even though it
     * lives in the shared `ui/components` package.
     */
    @Composable
    fun backdropVeil(dim: Float = com.betteraudio.data.settings.BACKDROP_DIM_DEFAULT): Brush {
        val base = MaterialTheme.colorScheme.background
        val d = dim.coerceIn(0f, 1f)
        val mid = com.betteraudio.data.settings.BACKDROP_DIM_DEFAULT
        // Three anchors, not two, so the slider's midpoint is exactly the veil this theme
        // shipped with: 0 = the blurred cover with nothing on top of it, mid = that tuned
        // top-light/bottom-heavy wash, 1 = the lower backdrop taken all the way to solid
        // black. Interpolating every stop separately (rather than scaling one alpha) keeps
        // the fall-off gradual down the screen at every setting — the darkening arrives the
        // same way the blur does, never as a band with an edge.
        fun stop(off: Float, litAlpha: Float, inkAlpha: Float): Pair<Float, Color> {
            val color = if (d <= mid) {
                base.copy(alpha = litAlpha * (if (mid <= 0f) 1f else d / mid))
            } else {
                val t = if (mid >= 1f) 1f else (d - mid) / (1f - mid)
                // Past the midpoint the veil stops being "the background colour" and becomes
                // ink, so the end of the travel is real black rather than a heavier tint.
                lerp(base.copy(alpha = litAlpha), Color.Black.copy(alpha = inkAlpha), t)
            }
            return off to color
        }
        return Brush.verticalGradient(
            stop(0f, 0.30f, 0.62f),
            stop(0.46f, 0.52f, 0.88f),
            stop(1f, 0.80f, 1f)
        )
    }

    /**
     * Darkener for the "liquid glass" pills, bottom-weighted so a pill's top edge keeps the
     * cover's colour while its lower half stays dark enough to carry text. Averages out near the
     * flat `Black @ 0.30` it replaces, so pill contrast is unchanged — what changes is that the
     * smudged cover underneath (see ImmersiveGlass) is no longer neutralised into grey.
     */
    @Composable
    fun glassVeil(): Brush {
        val ink = coverInk()
        return Brush.verticalGradient(
            0f to ink.copy(alpha = 0.22f),
            1f to ink.copy(alpha = 0.42f)
        )
    }

    /** Card/row fill: frosted translucent over the blur, darkened toward black so rows/pills/
     *  buttons (e.g. Settings) read clearly against the bright parts of the blurred cover.
     *  Alpha carries a little more weight than it used to — [backdropVeil] lets substantially
     *  more cover through, so these fills do more of the legibility work than they did under the
     *  old flat 0.78 dim. */
    @Composable
    fun cardColor(): Color =
        lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color.Black, 0.35f).copy(alpha = 0.52f)

    /** Elevated card/row fill (dialogs, raised rows) — same darkening, slightly more opaque. */
    @Composable
    fun cardHighColor(): Color =
        lerp(MaterialTheme.colorScheme.surfaceContainerHigh, Color.Black, 0.35f).copy(alpha = 0.64f)

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
