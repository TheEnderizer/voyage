package com.betteraudio.ui.bookinfo

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.AlertDialog
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
 * file supplies only what is specific to a book: its data, its overflow items, and the fact that
 * Resume has to shrink the page back onto the card before the player opens.
 *
 * No theme `when` here — the scaffold owns the split, so a single implementation serves both looks.
 */
@Composable
fun BookInfoScreen(
    onBack: () -> Unit,
    onResume: (bookId: Long) -> Unit,
    onOpenReader: (bookId: Long) -> Unit = {},
    /** The player sheet's expansion — this page is the player's morph source, so its panel needs
     *  to know how far the player has opened. See [InfoPageScaffold]. */
    sheetProgress: androidx.compose.runtime.State<Float>? = null,
    viewModel: BookInfoViewModel = hiltViewModel()
) {
    val bwp by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val chapterStarts by viewModel.chapterStarts.collectAsStateWithLifecycle()
    val synopsisGenerating by viewModel.synopsisGenerating.collectAsStateWithLifecycle()
    val book = bwp?.book

    var showBookOptions by remember { mutableStateOf(false) }
    var showCompanion by remember { mutableStateOf(false) }
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
            chapterStarts    = chapterStarts,
        ),
        onBack = onBack,
        // The page holds still and the player grows out of the cover you are looking at.
        //
        // It used to shrink back onto the grid card first and then start the book collapsed, on the
        // reasoning that two animations would fight if they overlapped. They do not overlap: the
        // player takes THIS page's cover as its morph source (see InfoPageScaffold's publishCover),
        // so there is one animation, not two, and the cover never travels away from the finger that
        // tapped Resume. The overlay stays mounted underneath and closes once the player has
        // covered it — see MainActivity.
        onResume = { onResume(viewModel.bookId) },
        sheetProgress = sheetProgress,
        overflowItems = { dismiss ->
            HapticDropdownMenuItem(
                text = { Text("Book options") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { dismiss(); showBookOptions = true }
            )
            // Reachable without starting playback — the reveal cursor only moves while listening
            // (docs/companion-packs.md §6), so opening the sheet from here cannot spoil anything
            // the listener has not already reached.
            HapticDropdownMenuItem(
                text = { Text("Companion") },
                leadingIcon = { Icon(Icons.Default.Groups, null) },
                onClick = { dismiss(); showCompanion = true }
            )
        }
    )

    if (showCompanion) {
        com.betteraudio.ui.companion.CompanionDeckDialog(
            bookId = viewModel.bookId,
            onDismiss = { showCompanion = false }
        )
    }

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
    onOpenReader: (bookId: Long) -> Unit = {},
    /** Forwarded to the page so Resume can become the player's play button — see
     *  [com.betteraudio.ui.player.ResumeChoreography]. */
    sheetProgress: androidx.compose.runtime.State<Float>? = null,
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
            // Navigation's AnimatedContent animates the CONTAINER'S SIZE between
            // destinations by default (ContentTransform's sizeTransform defaults to
            // SizeTransform(), which clips), and it places content at Alignment.TopStart.
            // Every destination here is full-screen, so that animation has nothing to
            // express -- but on the frame a navigate lands it clips the whole screen to a
            // box growing out of the top-left corner while the content inside is already
            // laid out at full size. That is the "swipe expanding from the top-left" on
            // the Resume leg: this NavHost navigates at the same moment the sheet expands,
            // so the wipe runs underneath the whole choreography. Explicitly no size
            // transform.
            sizeTransform = { null },
            popExitTransition = { ExitTransition.None },
        ) {
            composable("blank") { Box(Modifier.fillMaxSize()) }
            composable(
                route = "bookinfo/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.LongType })
            ) {
                BookInfoScreen(
                    onBack = { controller.close() },
                    // Deliberately does NOT close: the page is the player's morph source and has to
                    // still be composed while the player grows out of it.
                    onResume = { bookId -> onResume(bookId) },
                    onOpenReader = { bookId -> controller.close(); onOpenReader(bookId) },
                    sheetProgress = sheetProgress,
                )
            }
        }
    }
}
