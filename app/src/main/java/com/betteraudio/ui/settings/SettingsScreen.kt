package com.betteraudio.ui.settings

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. Every section content builder (`rootSection`, `themeSection`,
 *  `librarySection`, etc. — one file per section, see e.g. LibrarySection.kt, PlaybackSection.kt)
 *  stays shared/unsplit: none of them branch on theme, they're called identically by both variant
 *  top composables. `SettingsComponents.kt` holds the primitives (SettingsCard, NavRow, ...) used
 *  across more than one section. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenWidgetGallery: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> com.betteraudio.ui.immersive.settings.SettingsScreen(
            onBack, onOpenWidgetGallery, viewModel
        )
        AppTheme.MATERIAL_YOU -> com.betteraudio.ui.material.settings.SettingsScreen(
            onBack, onOpenWidgetGallery, viewModel
        )
    }
}
