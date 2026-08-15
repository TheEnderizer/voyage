package com.betteraudio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.home.HomeSection
import com.betteraudio.ui.home.HomeViewMode
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.components.FloatingNavPill as ImmersiveFloatingNavPill
import com.betteraudio.ui.material.components.FloatingNavPill as MaterialFloatingNavPill

/** Height of the floating nav pill (the mini player floats [NAV_PILL_GAP] above it). */
val NAV_PILL_HEIGHT = 64.dp
/** Gap between the pill and the mini player above it. */
val NAV_PILL_GAP = 8.dp
/** Space between the system nav inset and the pill. */
val NAV_PILL_BOTTOM_PADDING = 12.dp

/** Preferred width of the mini player when it sits BESIDE the nav pill (landscape). The floor is
 *  the pill's own width, so the bar is never the runt of the pair — see [MiniBarSlot]. */
val LANDSCAPE_MINI_BAR_WIDTH = 340.dp
/** Horizontal gap between the mini player and the nav pill when they share a row. */
val LANDSCAPE_PAIR_GAP = 10.dp
/** `MiniPlayerBar` insets its own drawn surface by 12dp a side inside whatever width it is given.
 *  Added on top of the pill's width so the two DRAWN pills come out the same size, not just the
 *  two layout slots. */
private val MINI_BAR_CONTENT_INSET = 24.dp

/**
 * Where the collapsed mini player sits when landscape pairs it BESIDE the nav pill instead of
 * stacking it above (landscape is too short to spend a whole band on each).
 *
 * MainActivity computes this because it is the only place that knows how wide the pill actually
 * measured — the pill wraps its icon row, so its width isn't a constant. Both halves then take
 * their x-offset from bottom-centre out of the SAME instance ([offsetX] for the bar,
 * [navPillOffsetX] for the pill), which is what keeps the pair centred as a unit rather than each
 * guessing at the other's width.
 */
data class MiniBarSlot(
    val width: Dp,
    val navPillWidth: Dp,
    /** Both halves shift by this so a centred pair still clears an asymmetric side inset (a
     *  landscape nav bar or cutout down one edge only). Callers using this must NOT also apply
     *  their own start/end inset padding, or the correction lands twice. */
    val centerShift: Dp,
) {
    val offsetX: Dp get() = centerShift - (navPillWidth + LANDSCAPE_PAIR_GAP) / 2
    val navPillOffsetX: Dp get() = centerShift + (width + LANDSCAPE_PAIR_GAP) / 2
}

/**
 * The beside-the-pill slot for a window with [availableWidth] of inset-free room and a pill
 * measuring [navPillWidth] — or null when there isn't room for both at a decent size, in which
 * case the caller keeps the existing stacked layout.
 */
fun miniBarSlotBesideNavPill(
    availableWidth: Dp,
    navPillWidth: Dp,
    startInset: Dp,
    endInset: Dp,
): MiniBarSlot? {
    if (navPillWidth <= 0.dp) return null
    val free = availableWidth - navPillWidth - LANDSCAPE_PAIR_GAP
    // A bar narrower than the pill beside it reads as broken rather than paired; stack instead.
    val floor = navPillWidth + MINI_BAR_CONTENT_INSET
    if (free < floor) return null
    return MiniBarSlot(
        width = minOf(maxOf(LANDSCAPE_MINI_BAR_WIDTH, floor), free),
        navPillWidth = navPillWidth,
        centerShift = (startInset - endInset) / 2
    )
}

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. */
@Composable
fun FloatingNavPill(
    section: HomeSection,
    viewMode: HomeViewMode,
    onSelectSection: (HomeSection) -> Unit,
    onCycleViewMode: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    expandProgress: State<Float>,
    modifier: Modifier = Modifier,
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveFloatingNavPill(
            section, viewMode, onSelectSection, onCycleViewMode, onSearch, onSettings, expandProgress, modifier
        )
        AppTheme.MATERIAL_YOU -> MaterialFloatingNavPill(
            section, viewMode, onSelectSection, onCycleViewMode, onSearch, onSettings, expandProgress, modifier
        )
    }
}
