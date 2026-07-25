package com.betteraudio.ui.immersive.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.model.BookWithProgress
import com.betteraudio.ui.components.ImportStructureDialog
import com.betteraudio.ui.home.*
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenBook: (Long) -> Unit = {},
    onOpenBookInfo: (Long) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSeries: (Long) -> Unit = {},
    onOpenAuthor: (String) -> Unit = {},
    onOpenReader: (Long) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var showScanSheet by remember { mutableStateOf(false) }
    var showStorageRationale by remember { mutableStateOf(false) }
    var showSortFilter by remember { mutableStateOf(false) }
    var showStructureDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val gridItems by viewModel.gridItems.collectAsStateWithLifecycle()
    val visibleItems by viewModel.visibleGridItems.collectAsStateWithLifecycle()
    val libraryTab by viewModel.libraryTab.collectAsStateWithLifecycle()
    val tabCounts by viewModel.tabCounts.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val savedFolder by viewModel.savedFolder.collectAsStateWithLifecycle()
    val structureChosen by viewModel.structureChosen.collectAsStateWithLifecycle()
    val sortFilter by viewModel.sortFilter.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val bookOptionsTarget by viewModel.bookOptionsTarget.collectAsStateWithLifecycle()
    val coverSearchTargetId by viewModel.coverSearchTargetId.collectAsStateWithLifecycle()
    val coverSearchCollection by viewModel.coverSearchCollection.collectAsStateWithLifecycle()
    val homeViewMode by viewModel.homeViewMode.collectAsStateWithLifecycle()
    val homeSection by viewModel.homeSection.collectAsStateWithLifecycle()
    val hasAnyBooks by viewModel.hasAnyBooks.collectAsStateWithLifecycle()
    val ebookError by viewModel.ebookError.collectAsStateWithLifecycle()

    val isSelectionMode = selection.isNotEmpty()

    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (needsAllFilesAccess()) showStorageRationale = true else showScanSheet = true
        }
    }
    val storageSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (!needsAllFilesAccess()) showScanSheet = true }

    fun onScanClick() {
        val hasAudioPerm = ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        when {
            !hasAudioPerm -> audioPermLauncher.launch(audioPermission)
            needsAllFilesAccess() -> showStorageRationale = true
            else -> showScanSheet = true
        }
    }

    // First launch: pick the library structure, then auto-open the folder picker. Both
    // savedFolder and structureChosen are null until DataStore emits, so we only act once the
    // real values arrive — avoids a false trigger on the "" / not-yet-loaded defaults.
    LaunchedEffect(savedFolder, structureChosen, showStructureDialog) {
        val folder = savedFolder
        val chosen = structureChosen
        if (folder != null && folder.isBlank() && chosen != null) {
            when {
                !chosen -> showStructureDialog = true
                !showScanSheet && !showStructureDialog -> onScanClick()
            }
        }
    }

    if (showStructureDialog) {
        ImportStructureDialog(
            initial = com.betteraudio.data.scanner.ImportStructure.AUTO,
            confirmLabel = "Continue",
            onConfirm = { chosen ->
                viewModel.chooseImportStructure(chosen)
                showStructureDialog = false
            },
            // Dismissing still commits a choice (Automatic) so the prompt doesn't reappear.
            onDismiss = {
                viewModel.chooseImportStructure(com.betteraudio.data.scanner.ImportStructure.AUTO)
                showStructureDialog = false
            }
        )
    }

    // Transparent container: the app-wide blurred-cover backdrop shows through. contentColor
    // must be explicit — contentColorFor(Transparent) falls back to the default (black) and
    // would render headings unreadable on the dark backdrop.
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            // ── Scrolling library ──────────────────────────────────────────
            // Only the truly-empty library shows the full onboarding screen; a library that has
            // books but none in the CURRENT section falls through to a per-section empty message.
            if (!hasAnyBooks) {
                EmptyLibrary(
                    onScan = ::onScanClick,
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch
                )
            } else {
                val isGridRefreshing = scan.status == ScanStatus.Running
                PullToRefreshBox(
                    isRefreshing = isGridRefreshing,
                    onRefresh = { savedFolder?.takeIf { it.isNotBlank() }?.let { viewModel.startScan(it) } },
                    modifier = Modifier.fillMaxSize()
                ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(
                        // Bottom clears the floating nav pill + mini player stacked above the
                        // nav inset (see FloatingNavPill / PlayerSheet).
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 184.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header — stays put; the selection bar floats over it as an overlay.
                    // Section (Audio/Ebooks) + view-mode switching moved to the floating nav pill.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HomeHeader(
                            itemCount = tabCounts[LibraryTab.ALL] ?: gridItems.size,
                            viewMode = homeViewMode,
                            section = homeSection,
                            scanning = scan.status == ScanStatus.Running,
                            onSort = { showSortFilter = true }
                        )
                    }

                    // Library status tabs
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryTabRow(
                            selected = libraryTab,
                            counts = tabCounts,
                            onSelect = { viewModel.setLibraryTab(it) }
                        )
                    }

                    if (visibleItems.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                when (libraryTab) {
                                    LibraryTab.LISTENING -> "Nothing in progress yet"
                                    LibraryTab.NOT_STARTED -> "No unstarted books"
                                    LibraryTab.FINISHED -> "No finished books yet"
                                    LibraryTab.ALL -> if (homeSection == HomeSection.EBOOKS)
                                        "No ebooks yet — connect an EPUB from a book's options, or set an ebook folder in Settings"
                                    else "No audiobooks yet"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }

                    visibleItems.forEach { gridItem ->
                        when (gridItem) {
                            is HomeGridItem.SingleBook -> {
                                item(key = "book_${gridItem.bwp.book.id}") {
                                    val key = SelKey.BookK(gridItem.bwp.book.id)
                                    val ebookOnly = gridItem.bwp.isEbookOnly
                                    BookGridCard(
                                        modifier = Modifier.animateItem(),
                                        bwp = gridItem.bwp,
                                        isSelected = key in selection,
                                        isSelectionMode = isSelectionMode,
                                        isNowPlaying = playbackState.bookId == gridItem.bwp.book.id,
                                        useReadingProgress = homeSection == HomeSection.EBOOKS,
                                        onClick = {
                                            when {
                                                isSelectionMode -> viewModel.toggleSelection(key)
                                                // In the Ebooks section (or an audio-less ebook row)
                                                // a tap opens the reader.
                                                homeSection == HomeSection.EBOOKS || ebookOnly ->
                                                    onOpenReader(gridItem.bwp.book.id)
                                                else -> onOpenBookInfo(gridItem.bwp.book.id)
                                            }
                                        },
                                        // Play in place (mini bar), do NOT open the full player —
                                        // unless this row has no audio, in which case play = read.
                                        onPlayClick = {
                                            if (ebookOnly) onOpenReader(gridItem.bwp.book.id)
                                            else viewModel.playResumeBook(gridItem.bwp)
                                        },
                                        onLongClick = { viewModel.toggleSelection(key) }
                                    )
                                }
                            }

                            is HomeGridItem.SeriesItem -> {
                                item(key = "series_${gridItem.series.id}") {
                                    val key = SelKey.SeriesK(gridItem.series.id)
                                    CollectionGridCard(
                                        modifier = Modifier.animateItem(),
                                        title = gridItem.series.name,
                                        subtitle = "${gridItem.books.size} book${if (gridItem.books.size != 1) "s" else ""}",
                                        coverPath = gridItem.coverPath,
                                        isNowPlaying = gridItem.books.any { it.book.id == playbackState.bookId },
                                        isSelected = key in selection,
                                        isSelectionMode = isSelectionMode,
                                        onClick = {
                                            if (isSelectionMode) viewModel.toggleSelection(key)
                                            else onOpenSeries(gridItem.series.id)
                                        },
                                        onPlayClick = { viewModel.playSeries(gridItem.series.id) },
                                        onLongClick = { viewModel.toggleSelection(key) },
                                        seriesId = gridItem.series.id
                                    )
                                }
                            }

                            is HomeGridItem.AuthorItem -> {
                                item(key = "author_${gridItem.name}") {
                                    val key = SelKey.AuthorK(gridItem.name)
                                    CollectionGridCard(
                                        modifier = Modifier.animateItem(),
                                        title = gridItem.name,
                                        subtitle = "${gridItem.books.size} book${if (gridItem.books.size != 1) "s" else ""}",
                                        coverPath = gridItem.coverPath,
                                        isNowPlaying = gridItem.books.any { it.book.id == playbackState.bookId },
                                        isSelected = key in selection,
                                        isSelectionMode = isSelectionMode,
                                        onClick = {
                                            if (isSelectionMode) viewModel.toggleSelection(key)
                                            else onOpenAuthor(gridItem.name)
                                        },
                                        onPlayClick = null,
                                        onLongClick = { viewModel.toggleSelection(key) }
                                    )
                                }
                            }
                        }
                    }
                }
                } // end PullToRefreshBox
            }


            // ── Selection bar — floats over the top, doesn't push content down ──
            val single = selection.singleOrNull()
            val selectedSeries = selection.filterIsInstance<SelKey.SeriesK>()
            val selectedBooks = selection.filterIsInstance<SelKey.BookK>()
            val selectedAuthors = selection.filterIsInstance<SelKey.AuthorK>()
            val showAddToSeries = selectedSeries.size == 1 && selectedBooks.isNotEmpty() && selectedAuthors.isEmpty()
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp)
            ) {
                SelectionHeader(
                    selectedCount = selection.size,
                    single = single,
                    showAddToSeries = showAddToSeries,
                    onClear = viewModel::clearSelection,
                    onDelete = { showDeleteConfirm = true },
                    onAddToSeries = {
                        val sid = selectedSeries.first().id
                        viewModel.addSelectedBooksToSeries(sid)
                        onOpenSeries(sid)
                    },
                    onCoverSearch = {
                        when (val s = single) {
                            is SelKey.BookK -> viewModel.openCoverSearch(s.id)
                            is SelKey.SeriesK -> gridItems.filterIsInstance<HomeGridItem.SeriesItem>()
                                .find { it.series.id == s.id }
                                ?.let { viewModel.openSeriesCoverSearch(it.series.id, it.series.name) }
                            is SelKey.AuthorK -> viewModel.openAuthorCoverSearch(s.name)
                            null -> {}
                        }
                    },
                    onBookOptions = { (single as? SelKey.BookK)?.let { viewModel.openBookOptions(it.id) } }
                )
            }
        }
    }

    // Dialogs
    ebookError?.let { message ->
        AlertDialog(
            containerColor = ImmersiveStyle.dialogColor(),
            onDismissRequest = { viewModel.dismissEbookError() },
            title = { Text("Couldn't connect ebook") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { viewModel.dismissEbookError() }) { Text("OK") } }
        )
    }

    if (showDeleteConfirm) {
        var deleteFiles by remember { mutableStateOf(false) }
        val count = selection.size
        AlertDialog(
            containerColor = ImmersiveStyle.dialogColor(),
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteSweep, null) },
            title = { Text("Delete $count item${if (count != 1) "s" else ""}?") },
            text = {
                Column {
                    Text("Selected series and authors will delete all of their books. This can't be undone.")
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { deleteFiles = !deleteFiles },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Spacer(Modifier.width(4.dp))
                        Text("Also delete files from storage")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelection(deleteFiles)
                    showDeleteConfirm = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } }
        )
    }

    if (showStorageRationale) {
        AlertDialog(
            containerColor = ImmersiveStyle.dialogColor(),
            onDismissRequest = { showStorageRationale = false },
            title = { Text("Storage Access Needed") },
            text = { Text("Better Audio needs access to all files. Tap Open Settings, then enable 'Allow access to all files'.") },
            confirmButton = {
                TextButton(onClick = {
                    showStorageRationale = false
                    storageSettingsLauncher.launch(allFilesAccessIntent(context))
                }) { Text("Open Settings") }
            },
            dismissButton = {
                TextButton(onClick = { showStorageRationale = false }) { Text("Cancel") }
            }
        )
    }

    if (showScanSheet) {
        ScanBottomSheet(
            startPath = savedFolder ?: "",
            onDismiss = { showScanSheet = false; viewModel.resetScanState() },
            onScan = { path -> viewModel.startScan(path) },
            onOpenStorageSettings = { storageSettingsLauncher.launch(allFilesAccessIntent(context)) },
            scan = scan
        )
    }

    if (showSortFilter) {
        SortFilterSheet(
            current = sortFilter,
            onApply = { viewModel.setSortFilter(it) },
            onDismiss = { showSortFilter = false }
        )
    }

    // Book options sheet (long hold — 1500 ms)
    if (bookOptionsTarget != null) {
        val optionsBwp = gridItems
            .filterIsInstance<HomeGridItem.SingleBook>()
            .firstOrNull { it.bwp.book.id == bookOptionsTarget }
            ?.bwp
        if (optionsBwp != null) {
            BookOptionsSheet(
                bwp = optionsBwp,
                onDismiss = { viewModel.closeBookOptions() },
                onUpdateMetadata = { title, author ->
                    viewModel.updateBookMetadata(optionsBwp.book.id, title, author)
                },
                onUpdateSeries = { name, order ->
                    viewModel.updateBookSeries(optionsBwp.book.id, name, order)
                },
                onUpdateStatus = { status ->
                    viewModel.updateBookStatus(optionsBwp.book.id, status)
                },
                onSearchOnlineCover = { viewModel.openCoverSearch(optionsBwp.book.id) },
                onRefreshCoverEffect = { viewModel.refreshCoverEffect(optionsBwp.book.id) },
                onIgnore = { viewModel.ignoreBook(optionsBwp.book.id) },
                onDeletePermanently = { deleteFiles ->
                    viewModel.deleteBook(optionsBwp.book.id, deleteFiles)
                },
                onConnectEpub = { path -> viewModel.connectEpub(optionsBwp.book.id, path) },
                onDisconnectEpub = { viewModel.disconnectEpub(optionsBwp.book.id) },
                onOpenReader = { onOpenReader(optionsBwp.book.id) },
                onPinShortcut = { com.betteraudio.util.BookShortcuts.requestPin(context, optionsBwp.book) }
            )
        }
    }

    // Online cover search sheet
    if (coverSearchTargetId != null) {
        val targetBwp = gridItems
            .filterIsInstance<HomeGridItem.SingleBook>()
            .firstOrNull { it.bwp.book.id == coverSearchTargetId }
            ?.bwp
        val book = targetBwp?.book
        CoverSearchSheet(
            initialQuery = book?.let {
                listOf(it.displayTitle, it.displayAuthor).filter(String::isNotBlank).joinToString(" ")
            } ?: "",
            onSearch = { query -> viewModel.searchCovers(query) },
            onPick = { url ->
                coverSearchTargetId?.let { viewModel.setBookCoverFromUrl(it, url) }
            },
            onDismiss = { viewModel.closeCoverSearch() }
        )
    }

    // Per-view cover search for a series or author tile
    coverSearchCollection?.let { target ->
        CoverSearchSheet(
            initialQuery = target.seed,
            onSearch = { query -> viewModel.searchCovers(query) },
            onPick = { url -> viewModel.setCollectionCoverFromUrl(url) },
            onDismiss = { viewModel.closeCollectionCoverSearch() }
        )
    }
}

// ─── Home header ──────────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(
    itemCount: Int,
    viewMode: HomeViewMode,
    section: HomeSection,
    scanning: Boolean,
    onSort: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (section == HomeSection.EBOOKS) "Ebooks" else "Library",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            val noun = if (section == HomeSection.EBOOKS) "ebook" else when (viewMode) {
                HomeViewMode.BOOKS -> "book"
                HomeViewMode.SERIES -> "title"
                HomeViewMode.AUTHORS -> "author"
            }
            Text(
                if (scanning) "Scanning library…"
                else "$itemCount $noun${if (itemCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (scanning) {
            CircularProgressIndicator(
                Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(10.dp))
        }
        // Search + Settings moved to the floating nav pill; only Sort stays contextual here.
        HeaderIconButton(Icons.AutoMirrored.Filled.Sort, "Sort & filter", onSort)
    }
}

/** Circular translucent icon button — reads well over the blurred-cover backdrop. */
@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

// ─── Selection header ─────────────────────────────────────────────────────────

@Composable
private fun LibraryTabRow(
    selected: LibraryTab,
    counts: Map<LibraryTab, Int>,
    onSelect: (LibraryTab) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LibraryTab.entries.forEach { tab ->
            val count = counts[tab] ?: 0
            FilterChip(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                label = { Text(if (count > 0) "${tab.label} · $count" else tab.label) },
                // Frosted unselected chips (stock chips are opaque tonal M3 and stick out over
                // the blurred cover); the selected chip keeps its solid fill for contrast, but
                // the hairline outline goes — borders read as "Material" over the blur.
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ImmersiveStyle.cardColor()
                ),
                border = null
            )
        }
    }
}

@Composable
private fun SelectionHeader(
    selectedCount: Int,
    single: SelKey?,
    showAddToSeries: Boolean,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onAddToSeries: () -> Unit,
    onCoverSearch: () -> Unit,
    onBookOptions: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        // Mostly-opaque frosted (not fully solid): it floats over the book grid, so it needs to
        // stay readable, but a hint of translucency keeps it in the Immersive family.
        // contentColor explicit — contentColorFor() can't resolve a translucent fill.
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClear) { Icon(Icons.Default.Close, "Cancel selection") }
            Text(
                "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            // One series + some books → add those books to the series.
            if (showAddToSeries) {
                IconButton(onClick = onAddToSeries) {
                    Icon(Icons.Default.PlaylistAdd, "Add to series")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
            }
            // Single selection → overflow (cover search + book options).
            if (single != null) {
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }, containerColor = ImmersiveStyle.menuColor()) {
                        DropdownMenuItem(
                            text = { Text("Search cover online") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            onClick = { showMenu = false; onCoverSearch() }
                        )
                        if (single is SelKey.BookK) {
                            DropdownMenuItem(
                                text = { Text("Book options") },
                                leadingIcon = { Icon(Icons.Default.Tune, null) },
                                onClick = { showMenu = false; onBookOptions() }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Book grid card ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookGridCard(
    bwp: BookWithProgress,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isNowPlaying: Boolean,
    onClick: () -> Unit,
    onPlayClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    useReadingProgress: Boolean = false
) {
    val book = bwp.book
    val borderColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.primary
            isNowPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        tween(150), label = "border"
    )

    // Published so a cover-morph transition (grid → Book Info) can start from this exact card's
    // on-screen bounds even when nothing is playing (no mini bar to morph from otherwise).
    val coverBoundsRegistry = com.betteraudio.ui.player.LocalCoverBoundsRegistry.current
    val cardRadius = MaterialTheme.shapes.large

    Box(
        modifier
            // Hides the ENTIRE card (image, border, badge, gradient/title/progress) the instant
            // the player's morphing cover takes over — placed first so it affects everything
            // later in the chain and all children, not just the image (leaving the border/badge
            // floating over a hidden image was a visible "stutter").
            .graphicsLayer { alpha = if (coverBoundsRegistry.isMorphHidden(book.id)) 0f else 1f }
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .pressScale(enabled = !isSelectionMode)
            .clip(cardRadius)
            .border(
                width = if (isSelected || isNowPlaying) 2.5.dp else 0.dp,
                color = borderColor,
                shape = cardRadius
            )
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.38f)) // frosted placeholder behind cover art
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .onGloballyPositioned {
                coverBoundsRegistry.publish(book.id, it.boundsInRoot(), 28.dp, book.coverArtPath)
            }
    ) {
        AsyncImage(
            model = book.coverArtPath?.let { File(it) },
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Bottom gradient info — the scrim is pulled slightly toward the cover accent (instead
        // of pure black) so the grid ties into the Immersive theme's cover-derived palette.
        val scrimBase = androidx.compose.ui.graphics.lerp(Color.Black, MaterialTheme.colorScheme.primary, 0.10f)
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, scrimBase.copy(alpha = 0.82f))
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    book.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = ImmersiveStyle.scrimText(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.displayAuthor.isNotBlank()) {
                    Text(
                        book.displayAuthor,
                        style = MaterialTheme.typography.labelSmall,
                        color = ImmersiveStyle.scrimText(muted = true),
                        maxLines = 1
                    )
                }
                val prog = if (useReadingProgress) bwp.readingFraction else bwp.progressFraction
                if (prog > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { prog },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(Pill),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.White.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // Now-playing badge
        if (isNowPlaying && !isSelectionMode) {
            Box(
                Modifier
                    .padding(8.dp)
                    .clip(Pill)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .align(Alignment.TopStart)
            ) {
                Icon(
                    Icons.Default.GraphicEq, "Now playing",
                    Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // Selection checkbox
        if (isSelectionMode) {
            SelectionCheck(isSelected, Modifier.padding(8.dp).align(Alignment.TopEnd))
        }

        // Play button — bottom-right corner, only visible when not in selection mode
        if (!isSelectionMode) {
            Box(
                Modifier
                    .padding(8.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onPlayClick)
                    .align(Alignment.BottomEnd),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun SelectionCheck(isSelected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(26.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.45f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// ─── Series / Author collection card ───────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionGridCard(
    title: String,
    subtitle: String,
    coverPath: String?,
    isNowPlaying: Boolean,
    onClick: () -> Unit,
    onPlayClick: (() -> Unit)?,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    isSelectionMode: Boolean = false,
    // Non-null only for series cards (author cards leave this null) — publishes this card's bounds
    // for the Series info screen's cover-morph open/close (see CoverBoundsRegistry), mirroring how
    // the book grid card feeds Book Info's morph.
    seriesId: Long? = null
) {
    val borderColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.primary
            isNowPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        tween(150), label = "collectionBorder"
    )
    val coverBoundsRegistry = com.betteraudio.ui.player.LocalCoverBoundsRegistry.current
    val cardRadius = MaterialTheme.shapes.large

    Box(
        modifier
            .graphicsLayer {
                alpha = if (seriesId != null && coverBoundsRegistry.isSeriesMorphHidden(seriesId)) 0f else 1f
            }
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .pressScale(enabled = !isSelectionMode)
            .clip(cardRadius)
            .border(
                width = if (isSelected || isNowPlaying) 2.5.dp else 0.dp,
                color = borderColor,
                shape = cardRadius
            )
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.38f)) // frosted placeholder behind cover art
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .onGloballyPositioned {
                if (seriesId != null) coverBoundsRegistry.publishSeries(seriesId, it.boundsInRoot(), 28.dp)
            }
    ) {
        AsyncImage(
            model = coverPath?.let { File(it) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // Accent-tinted scrim, matching BookGridCard — see the comment there.
        val scrimBase = androidx.compose.ui.graphics.lerp(Color.Black, MaterialTheme.colorScheme.primary, 0.10f)
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, scrimBase.copy(alpha = 0.88f))
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = ImmersiveStyle.scrimText(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelectionMode) {
            SelectionCheck(isSelected, Modifier.padding(8.dp).align(Alignment.TopEnd))
        } else if (onPlayClick != null) {
            FilledIconButton(
                onClick = onPlayClick,
                modifier = Modifier.padding(8.dp).align(Alignment.TopEnd).size(36.dp)
            ) { Icon(Icons.Default.PlayArrow, "Play series", Modifier.size(20.dp)) }
        }
    }
}

// ─── Stacked covers visual ────────────────────────────────────────────────────

@Composable
fun StackedCovers(books: List<Book>, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    Box(modifier.clip(RoundedCornerShape(corner))) {
        val display = books.take(3)
        val n = display.size
        display.forEachIndexed { i, book ->
            val depth = n - 1 - i
            val rotation = depth * 6f
            val offsetX = (depth * 4).dp
            val a = 1f - depth * 0.25f
            AsyncImage(
                model = book.coverArtPath?.let { File(it) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = offsetX)
                    .rotate(rotation)
                    .alpha(a)
            )
        }
    }
}

// ─── Empty state ──────────────────────────────────────────────────────────────

@Composable
private fun EmptyLibrary(
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        HomeHeader(
            itemCount = 0,
            viewMode = HomeViewMode.BOOKS,
            section = HomeSection.AUDIO,
            scanning = false,
            onSort = {}
        )
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(ImmersiveStyle.cardHighColor()),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.LibraryBooks, null,
                        Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text("Your shelf is empty", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Scan a folder to import your audiobooks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = onScan, shape = Pill, modifier = Modifier.height(50.dp)) {
                    Icon(Icons.Default.CreateNewFolder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan a folder")
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun needsAllFilesAccess(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

private fun allFilesAccessIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }
