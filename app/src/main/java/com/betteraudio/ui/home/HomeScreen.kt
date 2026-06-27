package com.betteraudio.ui.home

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
import androidx.compose.ui.layout.ContentScale
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
import com.betteraudio.playback.PlaybackState
import com.betteraudio.ui.components.CircleIconButton
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.playerContainerTransform
import com.betteraudio.ui.theme.pressScale
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenBook: (Long) -> Unit = {},
    onOpenBookInfo: (Long) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenSeries: (String) -> Unit = {},
    onJoinBooks: (bookIds: String) -> Unit = {},
    onEditGroup: (groupId: Long) -> Unit = {},
    onOpenGroupInfo: (groupId: Long) -> Unit = {},
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    var showScanSheet by remember { mutableStateOf(false) }
    var showStorageRationale by remember { mutableStateOf(false) }
    var showSortFilter by remember { mutableStateOf(false) }

    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val currentBook by viewModel.currentlyPlayingBook.collectAsStateWithLifecycle()
    val resumeBook by viewModel.resumeBook.collectAsStateWithLifecycle()
    val gridItems by viewModel.gridItems.collectAsStateWithLifecycle()
    val visibleItems by viewModel.visibleGridItems.collectAsStateWithLifecycle()
    val libraryTab by viewModel.libraryTab.collectAsStateWithLifecycle()
    val tabCounts by viewModel.tabCounts.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val savedFolder by viewModel.savedFolder.collectAsStateWithLifecycle()
    val sortFilter by viewModel.sortFilter.collectAsStateWithLifecycle()
    val selectedBookIds by viewModel.selectedBookIds.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val bookOptionsTarget by viewModel.bookOptionsTarget.collectAsStateWithLifecycle()
    val coverSearchTargetId by viewModel.coverSearchTargetId.collectAsStateWithLifecycle()

    val isSelectionMode = selectedBookIds.isNotEmpty() || selectedGroupId != null

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

    val nowPlaying = playbackState.bookId != -1L && playbackState.bookTitle.isNotBlank()
    val showResumeCard = !nowPlaying && resumeBook != null

    // Auto-open folder picker on first launch. savedFolder is null until DataStore emits,
    // so we only act once the real value arrives — avoids a false trigger on the "" default.
    LaunchedEffect(savedFolder) {
        val folder = savedFolder
        if (folder != null && folder.isBlank() && !showScanSheet) {
            onScanClick()
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {

            // ── Scrolling library ──────────────────────────────────────────
            if (gridItems.isEmpty() && !nowPlaying && !showResumeCard) {
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
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 132.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header — stays put; the selection bar floats over it as an overlay
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HomeHeader(
                            scanning = scan.status == ScanStatus.Running,
                            onSearch = onOpenSearch,
                            onSettings = onOpenSettings
                        )
                    }

                    // Featured now-playing card (active) or resume card (last played, after restart)
                    if (nowPlaying) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            FeaturedNowPlaying(
                                state = playbackState,
                                book = currentBook,
                                sharedScope = sharedScope,
                                animScope = animScope,
                                onPlayPause = { viewModel.playerController.togglePlayPause() },
                                onSkipForward = { viewModel.playerController.skipForward() },
                                onExpand = {
                                    val id = playbackState.bookId
                                    if (id != -1L) onOpenBook(id)
                                }
                            )
                        }
                    } else if (showResumeCard) {
                        val bwp = resumeBook!!
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ResumeCard(
                                bwp = bwp,
                                sharedScope = sharedScope,
                                animScope = animScope,
                                onPlay = { viewModel.playResumeBook(bwp) },
                                onExpand = { onOpenBook(bwp.book.id) }
                            )
                        }
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
                                    LibraryTab.ALL -> "Your library is empty"
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
                                    BookGridCard(
                                        modifier = Modifier.animateItem(),
                                        bwp = gridItem.bwp,
                                        isSelected = gridItem.bwp.book.id in selectedBookIds,
                                        isSelectionMode = isSelectionMode,
                                        isNowPlaying = playbackState.bookId == gridItem.bwp.book.id && playbackState.groupId == -1L,
                                        onClick = {
                                            if (isSelectionMode)
                                                viewModel.toggleBookSelection(gridItem.bwp.book.id)
                                            else onOpenBookInfo(gridItem.bwp.book.id)
                                        },
                                        onPlayClick = { onOpenBook(gridItem.bwp.book.id) },
                                        onLongClick = { viewModel.enterBookSelection(gridItem.bwp.book.id) }
                                    )
                                }
                            }

                            is HomeGridItem.Group -> {
                                item(key = "group_${gridItem.group.id}") {
                                    GroupGridCard(
                                        modifier = Modifier.animateItem(),
                                        groupItem = gridItem,
                                        isSelected = selectedGroupId == gridItem.group.id,
                                        isSelectionMode = isSelectionMode,
                                        isNowPlaying = playbackState.groupId == gridItem.group.id,
                                        onClick = {
                                            if (isSelectionMode) viewModel.selectGroup(gridItem.group.id)
                                            else onOpenGroupInfo(gridItem.group.id)
                                        },
                                        onLongClick = { viewModel.selectGroup(gridItem.group.id) }
                                    )
                                }
                            }
                        }
                    }
                }
                } // end PullToRefreshBox
            }

            // ── Floating bottom chrome ─────────────────────────────────────
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedVisibility(
                    visible = nowPlaying && !isSelectionMode,
                    enter = slideInVertically { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut()
                ) {
                    NowPlayingPill(
                        state = playbackState,
                        coverPath = currentBook?.coverArtPath ?: playbackState.coverArtUri?.removePrefix("file://"),
                        onPlayPause = { viewModel.playerController.togglePlayPause() },
                        onSkipForward = { viewModel.playerController.skipForward() },
                        onExpand = {
                            val id = playbackState.bookId
                            if (id != -1L) onOpenBook(id)
                        }
                    )
                }
                FloatingNavBar(
                    onSearch = onOpenSearch,
                    onSort = { showSortFilter = true },
                    onSettings = onOpenSettings
                )
            }

            // ── Selection bar — floats over the top, doesn't push content down ──
            AnimatedVisibility(
                visible = isSelectionMode,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp)
            ) {
                SelectionHeader(
                    selectedCount = selectedBookIds.size + (if (selectedGroupId != null) 1 else 0),
                    showSplit = selectedGroupId != null && selectedBookIds.isEmpty(),
                    showJoin = selectedBookIds.size >= 2,
                    showOptions = selectedBookIds.size == 1 && selectedGroupId == null,
                    onClear = viewModel::clearSelection,
                    onJoin = {
                        val ids = selectedBookIds.joinToString(",")
                        viewModel.clearSelection()
                        onJoinBooks(ids)
                    },
                    onSplit = { viewModel.splitSelectedGroup { } },
                    onEditGroup = {
                        val gid = selectedGroupId ?: return@SelectionHeader
                        viewModel.clearSelection()
                        onEditGroup(gid)
                    },
                    onOptions = { selectedBookIds.firstOrNull()?.let { viewModel.openBookOptions(it) } }
                )
            }
        }
    }

    // Dialogs
    if (showStorageRationale) {
        AlertDialog(
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
                }
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
}

// ─── Home header ──────────────────────────────────────────────────────────────

@Composable
private fun HomeHeader(
    scanning: Boolean,
    onSearch: () -> Unit,
    onSettings: () -> Unit
) {
    if (scanning) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            CircularProgressIndicator(
                Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
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
                label = { Text(if (count > 0) "${tab.label} · $count" else tab.label) }
            )
        }
    }
}

@Composable
private fun SelectionHeader(
    selectedCount: Int,
    showSplit: Boolean,
    showJoin: Boolean,
    showOptions: Boolean,
    onClear: () -> Unit,
    onJoin: () -> Unit,
    onSplit: () -> Unit,
    onEditGroup: () -> Unit,
    onOptions: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
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
            if (showSplit) {
                IconButton(onClick = onEditGroup) { Icon(Icons.Default.Edit, "Edit group") }
                TextButton(
                    onClick = onSplit,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Split") }
            }
            if (showJoin) {
                Button(onClick = onJoin, shape = Pill) { Text("Join") }
            }
            // Single-book selection → overflow menu with that book's options
            if (showOptions) {
                IconButton(onClick = onOptions) { Icon(Icons.Default.MoreVert, "Book options") }
            }
        }
    }
}

// ─── Featured now-playing card ────────────────────────────────────────────────

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun FeaturedNowPlaying(
    state: PlaybackState,
    book: Book?,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onExpand: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null
) {
    val progress = if (state.bookTotalDurationMs > 0)
        (state.bookPositionMs.toFloat() / state.bookTotalDurationMs).coerceIn(0f, 1f) else 0f
    val timeLeft = state.bookTotalDurationMs - state.bookPositionMs
    val coverPath = book?.coverArtPath ?: state.coverArtUri?.removePrefix("file://")
    val title = if (state.groupId != -1L) state.groupName else state.bookTitle

    Surface(
        onClick = onExpand,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .playerContainerTransform(sharedScope, animScope, state.bookId)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Box(
                    Modifier
                        .size(108.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    AsyncImage(
                        model = coverPath?.let { File(it) } ?: state.coverArtUri,
                        contentDescription = "Now playing cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.GraphicEq,
                            null,
                            Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (state.isPlaying) "NOW PLAYING" else "PAUSED",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.author.isNotBlank()) {
                        Text(
                            state.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            val synopsis = book?.synopsis
            if (!synopsis.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(Pill),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${formatDurationHero(timeLeft)} left",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlayPause,
                    shape = Pill,
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isPlaying) "Pause" else "Play")
                }
                FilledTonalIconButton(
                    onClick = onSkipForward,
                    shape = Pill,
                    modifier = Modifier.size(50.dp)
                ) { Icon(Icons.Default.FastForward, "Skip forward") }
                FilledTonalIconButton(
                    onClick = onExpand,
                    shape = Pill,
                    modifier = Modifier.size(50.dp)
                ) { Icon(Icons.Default.OpenInFull, "Open player", Modifier.size(20.dp)) }
            }
        }
    }
}

// ─── Floating now-playing pill ────────────────────────────────────────────────

@Composable
private fun NowPlayingPill(
    state: PlaybackState,
    coverPath: String?,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onExpand: () -> Unit
) {
    val title = if (state.groupId != -1L) state.groupName else state.bookTitle
    Surface(
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .clickable(onClick = onExpand)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = coverPath?.let { File(it) } ?: state.coverArtUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.author.isNotBlank()) {
                    Text(
                        state.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.isPlaying) "Pause" else "Play"
                )
            }
            IconButton(onClick = onSkipForward) {
                Icon(Icons.Default.FastForward, "Skip forward")
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

// ─── Resume card (last played book, shown after app restart when not playing) ──

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ResumeCard(
    bwp: BookWithProgress,
    onPlay: () -> Unit,
    onExpand: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null
) {
    val progress = bwp.progressFraction.coerceIn(0f, 1f)
    val timeLeft = ((1f - progress) * bwp.book.totalDurationMs).toLong()

    Surface(
        onClick = onExpand,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .playerContainerTransform(sharedScope, animScope, bwp.book.id)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Box(
                    Modifier
                        .size(108.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    AsyncImage(
                        model = bwp.book.coverArtPath?.let { File(it) },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).padding(top = 4.dp)) {
                    Text(
                        bwp.book.displayTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (bwp.book.displayAuthor.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            bwp.book.displayAuthor,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(Pill),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (bwp.book.totalDurationMs > 0) {
                    Text(
                        "${formatDurationHero(timeLeft)} left",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onPlay,
                    shape = Pill,
                    modifier = Modifier.weight(1f).height(50.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Resume")
                }
                FilledTonalIconButton(
                    onClick = onExpand,
                    shape = Pill,
                    modifier = Modifier.size(50.dp)
                ) { Icon(Icons.Default.OpenInFull, "Open player", Modifier.size(20.dp)) }
            }
        }
    }
}

// ─── Floating nav bar ─────────────────────────────────────────────────────────

@Composable
private fun FloatingNavBar(
    onSearch: () -> Unit,
    onSort: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 12.dp
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Active "Home" pill
            Surface(shape = Pill, color = MaterialTheme.colorScheme.primary) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Home,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Home",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            NavIcon(Icons.Default.Search, "Search", onSearch)
            NavIcon(Icons.AutoMirrored.Filled.Sort, "Sort & filter", onSort)
            NavIcon(Icons.Default.Settings, "Settings", onSettings)
        }
    }
}

@Composable
private fun NavIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .pressScale()
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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
    modifier: Modifier = Modifier
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

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .pressScale(enabled = !isSelectionMode)
            .clip(MaterialTheme.shapes.large)
            .border(
                width = if (isSelected || isNowPlaying) 2.5.dp else 0.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.large
            )
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        AsyncImage(
            model = book.coverArtPath?.let { File(it) },
            contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Bottom gradient info
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                Text(
                    book.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.displayAuthor.isNotBlank()) {
                    Text(
                        book.displayAuthor,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                }
                val prog = bwp.progressFraction
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

// ─── Joined group card ────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupGridCard(
    groupItem: HomeGridItem.Group,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isNowPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        when {
            isSelected -> MaterialTheme.colorScheme.primary
            isNowPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        tween(150), label = "groupBorder"
    )

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .pressScale(enabled = !isSelectionMode)
            .clip(MaterialTheme.shapes.large)
            .border(
                width = if (isSelected || isNowPlaying) 2.5.dp else 0.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.large
            )
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        StackedCovers(books = groupItem.books, modifier = Modifier.fillMaxSize(), corner = 0.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                    )
                )
                .padding(12.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.Link, null,
                        Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${groupItem.books.size} joined",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    groupItem.group.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelectionMode) {
            SelectionCheck(isSelected, Modifier.padding(8.dp).align(Alignment.TopEnd))
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
        HomeHeader(scanning = false, onSearch = onOpenSearch, onSettings = onOpenSettings)
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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

private fun formatDurationHero(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

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
