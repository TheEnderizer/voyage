package com.betteraudio.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/** Below this a landscape window has no room for a usable side-by-side layout — a 320dp-wide
 *  landscape window is a watch-sized freeform tile, not a phone on its side. */
private const val MIN_LANDSCAPE_WIDTH_DP = 480

/**
 * Pure window-geometry check, deliberately theme-agnostic: whether the current window is
 * landscape-shaped and wide enough to be worth a side-by-side layout. [Configuration] describes
 * the app WINDOW, not the display, so this is also correct in split-screen / freeform / on a
 * foldable's inner screen.
 *
 * This is geometry only, not a layout decision — see `ui.material.MaterialAdaptive` for the
 * theme-gated predicate every landscape branch should actually call.
 */
@Composable
@ReadOnlyComposable
fun isLandscapeWindow(): Boolean {
    val cfg = LocalConfiguration.current
    return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        cfg.screenWidthDp >= MIN_LANDSCAPE_WIDTH_DP
}
