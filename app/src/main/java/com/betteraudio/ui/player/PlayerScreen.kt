package com.betteraudio.ui.player

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.player.PlayerContent as ImmersivePlayerContent
import com.betteraudio.ui.material.player.PlayerContent as MaterialPlayerContent

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. The real content lives in `ui/immersive/player/PlayerScreen.kt` /
 *  `ui/material/player/PlayerScreen.kt`; this file only exists so PlayerSheet's nested NavHost
 *  keeps calling `PlayerContent(...)` from a stable package. */
@Composable
fun PlayerContent(
    onCollapse: () -> Unit,
    startPlaying: Boolean = true,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersivePlayerContent(onCollapse, startPlaying, viewModel)
        AppTheme.MATERIAL_YOU -> MaterialPlayerContent(onCollapse, startPlaying, viewModel)
    }

}
