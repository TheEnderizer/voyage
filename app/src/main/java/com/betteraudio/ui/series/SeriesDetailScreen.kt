package com.betteraudio.ui.series

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil3.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.components.InfoPageData
import com.betteraudio.ui.components.InfoPageKind
import com.betteraudio.ui.components.InfoPageScaffold
import com.betteraudio.ui.components.appDialogColor
import com.betteraudio.ui.components.appSheetColor
import com.betteraudio.ui.components.rememberInfoPageState
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.home.SeriesOptions
import com.betteraudio.ui.isLandscapeWindow
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import com.betteraudio.ui.theme.rememberPredictiveBackProgress
import java.io.File
import kotlinx.coroutines.launch
import com.betteraudio.ui.haptics.*

/**
 * Series page. The page itself — cover morph out of the tapped grid card, reveal, backdrop, top
 * bar, info panel — is [InfoPageScaffold], shared verbatim with
 * [com.betteraudio.ui.bookinfo.BookInfoScreen] and split per theme inside it (see its doc). This
 * file supplies only what is specific to a series: its data, its three overflow items, the
 * swipe-up-for-books affordance, and the books panel that slides up over the page (add / remove /
 * reorder), plus the sheets and dialog that panel opens.
 *
 * No theme `when` here — the scaffold owns the split, so a single implementation serves both looks;
 * the handful of places the panel's own fills differ resolve their color inline against
 * [LocalAppTheme], the same way [appSheetColor] does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    onBack: () -> Unit,
    onOpenPlayer: (bookId: Long) -> Unit,
    viewModel: SeriesDetailViewModel = hiltViewModel()
) {
    val series by viewModel.series.collectAsStateWithLifecycle()
    val books by viewModel.books.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val candidates by viewModel.candidateBooks.collectAsStateWithLifecycle()
    val synopsisGenerating by viewModel.synopsisGenerating.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }

    // Open the player on whichever book started playing (resume book, or a tapped book).
    LaunchedEffect(Unit) { viewModel.openPlayer.collect { onOpenPlayer(it) } }

    val scope = rememberCoroutineScope()
    val pageState = rememberInfoPageState()

    // 0 = info page, 1 = books panel fully up. panelAnim only drives ANIMATED transitions
    // (settle, predictive-back cancel) — a live drag writes dragProgress directly and
    // synchronously instead, mirroring PlayerSheet's AN-2 fix: N per-delta coroutines writing
    // panelAnim.snapTo() directly could otherwise race an in-flight animateTo.
    val panelAnim = remember { Animatable(0f) }
    val dragProgress = remember { mutableFloatStateOf(0f) }
    // True for the duration of a live drag — gates the mirror below so a still-finishing
    // animateTo (the interrupt case) can't stomp dragProgress while a drag is in control of it.
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(panelAnim) {
        snapshotFlow { panelAnim.value }.collect { if (!isDragging) dragProgress.floatValue = it }
    }
    // derivedStateOf: recompose only when the threshold flips, not every animation/drag frame —
    // a composition-phase `panelAnim.value > 0.5f` would recompose this whole screen on every
    // frame of the panel spring and every drag delta.
    val panelOpen by remember { derivedStateOf { dragProgress.floatValue > 0.5f } }
    val panelSpec = panelSpring()
    fun settlePanel(open: Boolean) = scope.launch {
        panelAnim.snapTo(dragProgress.floatValue)
        panelAnim.animateTo(if (open) 1f else 0f, panelSpec)
    }
    // Predictive back drives the panel closed in lockstep with the gesture (not just a commit-only
    // BackHandler) — mirrors PlayerSheet's seek pattern for the mini↔full player. Registered BEFORE
    // the scaffold's own back handler and mutually exclusive with it (`backEnabled = !panelOpen`),
    // so back closes the panel first and the page second.
    val panelBackProgress = rememberPredictiveBackProgress(enabled = panelOpen) { settlePanel(false) }
    LaunchedEffect(panelBackProgress.value) {
        if (panelOpen) panelAnim.snapTo(1f - panelBackProgress.value)
    }

    val coverPath = series?.coverArtPath
        ?: books.firstOrNull { it.coverArtPath != null }?.coverArtPath
    val effectiveAuthor = series?.author?.takeIf { it.isNotBlank() }
        ?: books.firstOrNull { it.displayAuthor.isNotBlank() }?.displayAuthor

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A 0.84 panel over a short landscape window leaves a cramped strip of the info page
        // above it — take almost the whole window instead. panelHeightPx and the Surface's own
        // fillMaxHeight below MUST use the same fraction or the drag distance stops matching how
        // far the panel actually travels.
        val panelFraction = if (isLandscapeWindow()) 0.94f else 0.84f
        val panelHeightPx = constraints.maxHeight * panelFraction

        fun dragBy(delta: Float) {
            // Synchronous, no coroutine — see dragProgress's declaration above (AN-2).
            dragProgress.floatValue = (dragProgress.floatValue - delta / panelHeightPx).coerceIn(0f, 1f)
        }
        fun dragStopped(velocity: Float) {
            isDragging = false
            val open = velocity < -900f || (velocity <= 900f && dragProgress.floatValue > 0.4f)
            settlePanel(open)
        }

        InfoPageScaffold(
            state = pageState,
            morphId = viewModel.seriesId,
            kind = InfoPageKind.SERIES,
            data = InfoPageData(
                kindLabel        = "SERIES",
                coverPath        = coverPath,
                bakedCoverPath   = series?.coverFxPath,
                title            = series?.name ?: "",
                author           = effectiveAuthor,
                narrator         = series?.narrator,
                seriesLabel      = "Series · ${books.size} book${if (books.size != 1) "s" else ""}",
                status           = null,
                progressFraction = progress.fraction,
                totalMs          = progress.totalMs,
                synopsis         = series?.description?.takeIf { it.isNotBlank() }
                                   ?: if (synopsisGenerating) "Generating synopsis…" else null,
            ),
            onBack = onBack,
            // Unlike a book's Resume, this doesn't close the page: playback starts underneath and
            // the openPlayer event above hands off to the player sheet.
            onResume = { viewModel.playSeries() },
            // The panel's own back handler above takes priority while it's showing.
            backEnabled = !panelOpen,
            // Swipe up anywhere on the info page to reveal the books panel.
            contentModifier = Modifier.draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { dragBy(it) },
                onDragStarted = { isDragging = true },
                onDragStopped = { dragStopped(it) }
            ),
            overflowItems = { dismiss ->
                HapticDropdownMenuItem(
                    text = { Text("Series options") },
                    leadingIcon = { Icon(Icons.Default.Tune, null) },
                    onClick = { dismiss(); showOptions = true }
                )
                HapticDropdownMenuItem(
                    text = { Text("Rename series") },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { dismiss(); showRename = true }
                )
                HapticDropdownMenuItem(
                    text = { Text("Add books") },
                    leadingIcon = { Icon(Icons.Default.Add, null) },
                    onClick = { dismiss(); showAdd = true }
                )
                // Opens on the member the listener is actually in — see
                // SeriesDetailViewModel.companionBookId for why a series cannot have one cursor.
                HapticDropdownMenuItem(
                    text = { Text("Companion") },
                    leadingIcon = { Icon(Icons.Default.Groups, null) },
                    onClick = { dismiss(); viewModel.openCompanion() }
                )
            },
            belowPanel = {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(Pill)
                        .clickable { settlePanel(true) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, null, Modifier.size(18.dp), tint = swipeHintColor())
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Swipe up for books",
                        style = MaterialTheme.typography.labelMedium,
                        color = swipeHintColor()
                    )
                }
            }
        )

        // ── Books panel (slides up over the info page) ─────────────────────────
        // Sibling of the scaffold rather than a child, so it isn't clipped by the page's own
        // container reveal and can align itself to the window's bottom edge.
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = panelColor(),
            // Explicit: contentColorFor() can't resolve Immersive's translucent fill, and this
            // screen has no parent Surface/Scaffold to inherit a sane LocalContentColor from.
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Capped BEFORE fillMaxWidth() so the cap actually narrows the node — a wide
                // landscape window otherwise leaves the list swimming in 890dp of whitespace.
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(panelFraction)
                .graphicsLayer { translationY = panelHeightPx * (1f - dragProgress.floatValue) }
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header — drag down here to close.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { dragBy(it) },
                            onDragStarted = { isDragging = true },
                            onDragStopped = { dragStopped(it) }
                        )
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 10.dp)
                            .size(width = 42.dp, height = 4.dp)
                            .clip(Pill)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Books",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                "${books.size} in this series · tap to play",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HapticIconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, "Add books")
                        }
                        HapticIconButton(onClick = { settlePanel(false) }) {
                            Icon(Icons.Default.KeyboardArrowDown, "Close book list")
                        }
                    }
                }

                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(books, key = { _, b -> b.id }) { index, book ->
                        SeriesBookRow(
                            book = book,
                            index = index + 1,
                            canMoveUp = index > 0,
                            canMoveDown = index < books.size - 1,
                            onClick = { viewModel.playFromBook(book.id) },
                            onMoveUp = { viewModel.moveBook(book.id, up = true) },
                            onMoveDown = { viewModel.moveBook(book.id, up = false) },
                            onRemove = { viewModel.removeBook(book.id) },
                            modifier = Modifier.animateItem()
                        )
                    }

                    if (books.isEmpty()) {
                        item {
                            Text(
                                "This series has no books yet. Tap + to add some.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showOptions) {
        series?.let { s ->
            BookOptionsSheet(
                seriesOptions = SeriesOptions(
                    series = s,
                    onSave = { viewModel.saveOptions(it) }
                ),
                onDismiss = { showOptions = false }
            )
        }
    }

    if (showAdd) {
        AddBooksSheet(
            candidates = candidates,
            onAdd = { viewModel.addBook(it) },
            onDismiss = { showAdd = false }
        )
    }

    // -1 means "not open" AND "no member to open on" at once, which is why this is a plain id
    // rather than a boolean beside one: an empty series simply never shows the sheet.
    val companionBookId by viewModel.companionBookId.collectAsStateWithLifecycle()
    if (companionBookId != -1L) {
        com.betteraudio.ui.companion.CompanionDeckDialog(
            bookId = companionBookId,
            onDismiss = { viewModel.closeCompanion() }
        )
    }

    if (showRename) {
        var name by remember { mutableStateOf(series?.name ?: "") }
        AlertDialog(
            containerColor = appDialogColor(),
            onDismissRequest = { showRename = false },
            title = { Text("Rename series") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    singleLine = true, label = { Text("Series name") }
                )
            },
            confirmButton = {
                HapticTextButton(onClick = { viewModel.rename(name); showRename = false }) { Text("Save") }
            },
            dismissButton = { HapticTextButton(onClick = { showRename = false }) { Text("Cancel") } }
        )
    }
}

/** Books-panel fill. Near-opaque frosted in Immersive — it floats over the cover backdrop, and a
 *  fully solid sheet looks pasted-on there — and the stock tonal surface in Material You. */
@Composable
private fun panelColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE)
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    else
        MaterialTheme.colorScheme.surfaceContainerHigh

/** Row fill inside the books panel — frosted over the (already translucent) Immersive panel. */
@Composable
private fun rowColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE)
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f)
    else
        MaterialTheme.colorScheme.surfaceContainer

/** The swipe-up hint sits on the info page, over the cover scrim in Immersive and over the opaque
 *  tonal background in Material You — so it follows each theme's muted scrim tone. */
@Composable
private fun swipeHintColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE)
        com.betteraudio.ui.immersive.ImmersiveStyle.scrimText(muted = true)
    else
        MaterialTheme.colorScheme.onSurfaceVariant

/** Panel spring, matching each theme's page motion. */
@Composable
private fun panelSpring(): androidx.compose.animation.core.AnimationSpec<Float> =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE)
        androidx.compose.animation.core.spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
    else
        com.betteraudio.ui.material.motion.LocalVoyageMotion.current.spatialDefault

@Composable
private fun SeriesBookRow(
    book: Book,
    index: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = rowColor(),
        modifier = modifier.fillMaxWidth().pressScale()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        book.seriesOrder?.let { "%.0f".format(it) } ?: index.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            AsyncImage(
                model = book.coverArtPath?.let { File(it) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.medium)
            )
            Column(Modifier.weight(1f)) {
                Text(book.displayTitle, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val label = when (book.status) {
                    BookStatus.FINISHED -> "Finished"
                    BookStatus.IN_PROGRESS -> "In progress"
                    BookStatus.NOT_STARTED -> "Not started"
                }
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column {
                HapticIconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ArrowUpward, "Move up", Modifier.size(18.dp))
                }
                HapticIconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.ArrowDownward, "Move down", Modifier.size(18.dp))
                }
            }
            HapticIconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, "Remove from series", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddBooksSheet(
    candidates: List<Book>,
    onAdd: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val added = remember { mutableStateListOf<Long>() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = appSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Add books to series", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp))
            if (candidates.isEmpty()) {
                Text("No other books to add.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(candidates, key = { it.id }) { book ->
                    val isAdded = book.id in added
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = book.coverArtPath?.let { File(it) },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(book.displayTitle, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (book.displayAuthor.isNotBlank()) {
                                Text(book.displayAuthor, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (isAdded) {
                            Text("Added", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        } else {
                            HapticFilledTonalButton(onClick = { onAdd(book.id); added.add(book.id) }) { Text("Add") }
                        }
                    }
                }
            }
        }
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
 * animate from/to on either open or close — Book Info's identical morph (now literally the same
 * code, see [InfoPageScaffold]) looks right only because it's hosted the same way.
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
