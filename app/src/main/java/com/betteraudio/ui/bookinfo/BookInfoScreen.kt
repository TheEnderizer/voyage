package com.betteraudio.ui.bookinfo

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
import com.betteraudio.ui.immersive.bookinfo.BookInfoScreen as ImmersiveBookInfoScreen
import com.betteraudio.ui.material.bookinfo.BookInfoScreen as MaterialBookInfoScreen

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. Mirrors [com.betteraudio.ui.series.SeriesDetailScreen] verbatim,
 *  down to the overlay-hosting recipe below (was previously a mode inside the player itself). */
@Composable
fun BookInfoScreen(
    onBack: () -> Unit,
    onResume: (bookId: Long) -> Unit,
    viewModel: BookInfoViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersiveBookInfoScreen(onBack, onResume, viewModel)
        AppTheme.MATERIAL_YOU -> MaterialBookInfoScreen(onBack, onResume, viewModel)
    }
}

/** Drives the book info overlay: which book (if any) is currently open. Kept at the app root
 *  ([com.betteraudio.MainActivity]) and handed to Home, which calls [open] when a grid card is
 *  tapped for info — mirrors [com.betteraudio.ui.series.SeriesOverlayController] exactly (see its
 *  doc for why this is an overlay above Home rather than a NavHost destination: the cover-morph
 *  needs the tapped grid card still composed underneath during the animation). */
@Stable
class BookInfoOverlayController {
    var bookId by mutableStateOf(-1L)
        private set
    fun open(id: Long) { bookId = id }
    fun close() { bookId = -1L }
}

@Composable
fun rememberBookInfoOverlayController(): BookInfoOverlayController = remember { BookInfoOverlayController() }

/** Hosts [BookInfoScreen] in a tiny nested NavHost — mirrors
 *  [com.betteraudio.ui.series.SeriesOverlay] verbatim: a fresh ViewModel per book opened
 *  (`popUpTo(0)` clears the nested back stack on every open), placed as a sibling drawn AFTER the
 *  app's root NavHost so Home stays mounted/visible underneath (required for the cover morph) and
 *  before any always-on-top chrome (mini player, floating nav pill). */
@Composable
fun BookInfoOverlay(
    controller: BookInfoOverlayController,
    onResume: (bookId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (controller.bookId == -1L) return
    val nested = rememberNavController()
    LaunchedEffect(controller.bookId) {
        nested.navigate("bookinfo/${controller.bookId}") {
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
                route = "bookinfo/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.LongType })
            ) {
                BookInfoScreen(
                    onBack = { controller.close() },
                    onResume = { bookId -> controller.close(); onResume(bookId) },
                )
            }
        }
    }
}
