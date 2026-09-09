package com.betteraudio.ui.home

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.home.HomeScreen as ImmersiveHomeScreen
import com.betteraudio.ui.material.home.HomeScreen as MaterialHomeScreen

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. */
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenBook: (Long) -> Unit = {},
    onOpenBookInfo: (Long) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSeries: (Long) -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveHomeScreen(
            onOpenSettings, onOpenBook, onOpenBookInfo, onOpenSearch, onOpenSeries, onOpenAuthor, viewModel
        )
        AppTheme.MATERIAL_YOU -> MaterialHomeScreen(
            onOpenSettings, onOpenBook, onOpenBookInfo, onOpenSearch, onOpenSeries, onOpenAuthor, viewModel
        )
    }
}
