package com.betteraudio.ui.series

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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

/**
 * Drives the series info overlay: which series (if any) is currently open. Kept at the app root
 * ([com.betteraudio.MainActivity]) and handed to Home, which calls [open] when a series card is
 * tapped — [SeriesOverlay] then draws the screen as an overlay ABOVE Home rather than as a
 * NavHost destination that replaces it.
 *
 * This is the fix for the series open/close cover-morph animation: it morphs to/from a library
 * grid card's bounds via [com.betteraudio.ui.player.CoverBoundsRegistry], which requires the grid
 * to still be composed underneath during the animation. A NavHost destination tears the previous
 * screen (and its grid card) down as soon as `navigate()` is called, so the morph had nothing to
 * animate from/to on either open or close — Book Info's identical morph looks right only because
 * it's hosted the same way, inside [com.betteraudio.ui.player.PlayerSheet], which is a persistent
 * overlay drawn on top of Home, never a route that replaces it.
 */
@Stable
class SeriesOverlayController {
    var seriesId by mutableStateOf(-1L)
        private set
    fun open(id: Long) { seriesId = id }
    fun close() { seriesId = -1L }
}

@Composable
fun rememberSeriesOverlayController(): SeriesOverlayController = remember { SeriesOverlayController() }

/**
 * Hosts [SeriesDetailScreen] in a tiny nested NavHost — purely so [SeriesDetailViewModel] keeps
 * getting its `seriesId` via SavedStateHandle from a nav-arg route (unchanged) and a fresh
 * ViewModel per series opened (`popUpTo(0)` clears the nested back stack on every open). This
 * mirrors [com.betteraudio.ui.player.PlayerSheet]'s own nested-NavHost recipe for Book Info
 * verbatim. Place this as a sibling drawn AFTER the app's root NavHost (so Home stays
 * mounted/visible underneath, which the cover morph depends on) and before any always-on-top
 * chrome (mini player, floating nav pill).
 */
@Composable
fun SeriesOverlay(
    controller: SeriesOverlayController,
    onOpenPlayer: (bookId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controller.seriesId == -1L) return
    val nested = rememberNavController()
    LaunchedEffect(controller.seriesId) {
        nested.navigate("series/${controller.seriesId}") {
            popUpTo(0) { inclusive = true }
        }
    }
    Box(modifier.fillMaxSize()) {
        NavHost(
            navController = nested,
            startDestination = "blank",
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("blank") { Box(Modifier.fillMaxSize()) }
            composable(
                route = "series/{seriesId}",
                arguments = listOf(navArgument("seriesId") { type = NavType.LongType })
            ) {
                SeriesDetailScreen(
                    onBack = { controller.close() },
                    onOpenPlayer = onOpenPlayer,
                )
            }
        }
    }
}
