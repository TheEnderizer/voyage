package com.betteraudio.ui.series

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.series.SeriesDetailScreen as ImmersiveSeriesDetailScreen
import com.betteraudio.ui.material.series.SeriesDetailScreen as MaterialSeriesDetailScreen

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. */
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: (bookId: Long) -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveSeriesDetailScreen(onBack, onOpenPlayer, viewModel)
        AppTheme.MATERIAL_YOU -> MaterialSeriesDetailScreen(onBack, onOpenPlayer, viewModel)
    }
}
