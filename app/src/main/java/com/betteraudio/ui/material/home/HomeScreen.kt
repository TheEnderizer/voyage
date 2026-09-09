package com.betteraudio.ui.material.home

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.home.HomeScreenContent
import com.betteraudio.ui.home.HomeViewModel

/** Material You Home screen — thin wrapper around the shared body; see HomeScreenContent. */
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
    HomeScreenContent(
        onOpenSettings = onOpenSettings,
        onOpenBook = onOpenBook,
        onOpenBookInfo = onOpenBookInfo,
        onOpenSearch = onOpenSearch,
        onOpenSeries = onOpenSeries,
        onOpenAuthor = onOpenAuthor,
        viewModel = viewModel,
        style = MaterialHomeStyle
    )
}
