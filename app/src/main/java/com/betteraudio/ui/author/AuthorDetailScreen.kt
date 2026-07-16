package com.betteraudio.ui.author

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.author.AuthorDetailScreen as ImmersiveAuthorDetailScreen
import com.betteraudio.ui.material.author.AuthorDetailScreen as MaterialAuthorDetailScreen

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. */
@Composable
fun AuthorDetailScreen(
    authorName: String,
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit,
    viewModel: AuthorDetailViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveAuthorDetailScreen(authorName, onBack, onBookClick, viewModel)
        AppTheme.MATERIAL_YOU -> MaterialAuthorDetailScreen(authorName, onBack, onBookClick, viewModel)
    }
}
