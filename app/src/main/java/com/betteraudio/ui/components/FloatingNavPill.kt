package com.betteraudio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
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
