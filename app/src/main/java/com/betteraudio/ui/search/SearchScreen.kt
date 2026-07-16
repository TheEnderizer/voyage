package com.betteraudio.ui.search

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.search.SearchScreen as ImmersiveSearchScreen
import com.betteraudio.ui.material.search.SearchScreen as MaterialSearchScreen

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention (this file stays a thin router; the real content lives in
 *  `ui/immersive/search/SearchScreen.kt` / `ui/material/search/SearchScreen.kt`). */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveSearchScreen(onBack, onBookClick, viewModel)
        AppTheme.MATERIAL_YOU -> MaterialSearchScreen(onBack, onBookClick, viewModel)
    }
}
