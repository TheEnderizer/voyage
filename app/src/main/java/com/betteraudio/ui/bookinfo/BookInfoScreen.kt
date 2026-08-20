package com.betteraudio.ui.bookinfo

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.betteraudio.ui.components.InfoPageData
import com.betteraudio.ui.components.InfoPageKind
import com.betteraudio.ui.components.InfoPageScaffold
import com.betteraudio.ui.components.rememberInfoPageState
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.haptics.*

/**
 * Book Info page. The page itself — cover morph out of the tapped grid card, reveal, backdrop, top
 * bar, info panel — is [InfoPageScaffold], shared verbatim with
 * [com.betteraudio.ui.series.SeriesDetailScreen] and split per theme inside it (see its doc). This
 * file supplies only what is specific to a book: its data, its overflow item, and the fact that
 * Resume has to shrink the page back onto the card before the player opens.
 *
 * No theme `when` here — the scaffold owns the split, so a single implementation serves both looks.
 */
@Composable
fun BookInfoScreen(
    onBack: () -> Unit,
    onResume: (bookId: Long) -> Unit,
    viewModel: BookInfoViewModel = hiltViewModel()
) {
    val bwp by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val synopsisGenerating by viewModel.synopsisGenerating.collectAsStateWithLifecycle()
    val book = bwp?.book

    var showBookOptions by remember { mutableStateOf(false) }
    val coverSearchOpen by viewModel.coverSearchOpen.collectAsStateWithLifecycle()
    val pageState = rememberInfoPageState()

    val seriesLabel = book?.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
        if (book.seriesOrder != null) "$series · #${book.seriesOrder}" else series
    }

    InfoPageScaffold(
        state = pageState,
        morphId = viewModel.bookId,
        kind = InfoPageKind.BOOK,
        data = InfoPageData(
            kindLabel        = "BOOK",
            coverPath        = book?.coverArtPath,
            bakedCoverPath   = book?.coverFxPath,
            // Shares the grid card's decoded bitmap, so the morph never re-decodes mid-flight.
            coverCacheKey    = book?.id?.let { "cover-$it" },
            title            = book?.displayTitle ?: "",
            author           = book?.displayAuthor,
            narrator         = book?.narrator,
            seriesLabel      = seriesLabel,
            status           = book?.status,
            progressFraction = bwp?.progressFraction ?: 0f,
            totalMs          = book?.totalDurationMs ?: 0L,
            synopsis         = book?.synopsis?.takeIf { it.isNotBlank() }
                               ?: book?.description?.takeIf { it.isNotBlank() }
                               ?: if (synopsisGenerating) "Generating synopsis…" else null,
        ),
        onBack = onBack,
        // Close the page onto the grid card FIRST, then open the player — the two animations fight
        // if they overlap.
        onResume = { pageState.closeWithMorph { onResume(viewModel.bookId) } },
        overflowItems = { dismiss ->
            HapticDropdownMenuItem(
                text = { Text("Book options") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { dismiss(); showBookOptions = true }
            )
        }
    )

    if (showBookOptions && bwp != null) {
        BookOptionsSheet(
            bwp = bwp,
            onDismiss = { showBookOptions = false },
            onUpdateMetadata = { title, author -> viewModel.updateMetadata(title, author) },
            onUpdateSeries = { name, order -> viewModel.updateSeriesInfo(name, order) },
            onUpdateStatus = { viewModel.updateStatus(it) },
            onSearchOnlineCover = { showBookOptions = false; viewModel.openCoverSearch() }
        )
    }

    if (coverSearchOpen) {
        com.betteraudio.ui.home.CoverSearchSheet(
            initialQuery = book?.let {
                listOf(it.displayTitle, it.displayAuthor).filter(String::isNotBlank).joinToString(" ")
            } ?: "",
            onSearch = { query -> viewModel.searchCovers(query) },
            onPick = { url -> viewModel.setCoverFromUrl(url) },
            onDismiss = { viewModel.closeCoverSearch() }
        )
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
