package com.betteraudio.ui.material

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import com.betteraudio.ui.isLandscapeWindow
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme

/**
 * The app's landscape adaptivity, gated to Material You. Nothing under `ui.immersive` imports
 * `ui.material`, so a landscape branch reached only through [isMaterialLandscape] can never reach
 * the Immersive look — a shared/unsplit file (PlayerSheet, ChapterSheet, BookHistoryOverlay) must
 * call this, never `LocalConfiguration`/`isLandscapeWindow` directly, so the theme guard travels
 * with the geometry check by construction rather than by convention.
 */
object MaterialAdaptive {

    /** True only when Material You is active AND the window is landscape-shaped. */
    @Composable
    @ReadOnlyComposable
    fun isMaterialLandscape(): Boolean =
        LocalAppTheme.current == AppTheme.MATERIAL_YOU && isLandscapeWindow()

    /** The home grid's own `contentPadding` (16dp start + 16dp end) and column spacing, from
     *  HomeScreenContent.kt — needed to turn a window width into a real cover width. */
    private const val GRID_SIDE_PADDING_DP = 32
    private const val GRID_COLUMN_SPACING_DP = 14

    /**
     * Home grid columns. PORTRAIT ALWAYS RETURNS 2 — today's exact value at
     * HomeScreenContent.kt:199 — so portrait renders identically on every device and in both
     * themes. (Widening a portrait tablet to more columns is a deliberate non-goal here.)
     *
     * Landscape spends its extra width on MORE COLUMNS, not bigger covers: it targets the cover
     * width this same window gets in portrait, so a book card is about the same physical size in
     * either orientation. A fixed ladder (3/4/5 columns by width) couldn't do that — 3 columns of
     * an 800dp landscape window are ~240dp covers against portrait's ~157dp, i.e. half again as
     * large purely because the device was turned.
     *
     * In a landscape [Configuration], `screenHeightDp` IS the window's short edge — the width it
     * has in portrait — so the portrait cover width is derivable here without a second measure.
     */
    @Composable
    @ReadOnlyComposable
    fun homeGridColumns(): Int {
        val cfg = LocalConfiguration.current
        if (cfg.orientation != Configuration.ORIENTATION_LANDSCAPE) return 2
        val portraitCover =
            (cfg.screenHeightDp - GRID_SIDE_PADDING_DP - GRID_COLUMN_SPACING_DP) / 2f
        if (portraitCover <= 0f) return 3
        // n columns of `portraitCover` each, with (n-1) gaps, must fill the usable width.
        val usable = cfg.screenWidthDp - GRID_SIDE_PADDING_DP + GRID_COLUMN_SPACING_DP
        return Math.round(usable / (portraitCover + GRID_COLUMN_SPACING_DP)).coerceIn(3, 8)
    }
}
