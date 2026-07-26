package com.betteraudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.navigation.NavType
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlayerController
import com.betteraudio.ui.author.AuthorDetailScreen
import com.betteraudio.ui.bookinfo.BookInfoOverlay
import com.betteraudio.ui.bookinfo.rememberBookInfoOverlayController
import com.betteraudio.ui.home.HomeScreen
import com.betteraudio.ui.immersive.immersiveEnter
import com.betteraudio.ui.immersive.immersiveExit
import com.betteraudio.ui.immersive.immersivePopEnter
import com.betteraudio.ui.immersive.immersivePopExit
import com.betteraudio.ui.material.materialEnter
import com.betteraudio.ui.material.materialExit
import com.betteraudio.ui.material.materialPopEnter
import com.betteraudio.ui.material.materialPopExit
import com.betteraudio.ui.player.PlayerSheet
import com.betteraudio.ui.player.rememberPlayerSheetController
import com.betteraudio.ui.search.SearchScreen
import com.betteraudio.ui.series.SeriesOverlay
import com.betteraudio.ui.series.rememberSeriesOverlayController
import com.betteraudio.ui.settings.SettingsScreen
import com.betteraudio.ui.theme.VoyageTheme
import com.betteraudio.ui.update.UpdateAvailableScreen
import com.betteraudio.ui.update.UpdateGateViewModel
import com.betteraudio.util.AppLog
import com.betteraudio.widget.WidgetIntents
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var repository: AudiobookRepository
    @Inject lateinit var seriesRepository: com.betteraudio.data.repository.SeriesRepository

    private val updateGateViewModel: UpdateGateViewModel by viewModels()

    // Set when a widget tap (warm start) asks to open the active player; observed in setContent.
    private var playerNavRequest by mutableStateOf<Long?>(null)

    // Set when the custom-widget configure activity's "Create new" asks to open the widget editor.
    private var widgetEditorNavRequest by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playerController.connect()
        requestInitialPermissions()
        // Captured before composition so the restore effect reads it before the route-tracking
        // effect (which writes -1 for the initial "home" route) can overwrite it. Read from
        // SettingsStore's pre-warmed snapshot rather than runBlocking the DataStore Flow.
        val initialBookId = settings.currentLastOpenBookId
        // Theme read synchronously so the first frame renders in the right theme (no flash).
        // "" = never chosen → the first-launch theme prompt is shown over the app. These are
        // already-warm snapshots (SettingsStore's init collects them as soon as the singleton is
        // constructed), not a blocking DataStore read.
        val initialThemeRaw = settings.currentAppTheme
        val initialColorSource = settings.currentThemeColorSource
        val initialCustomThemeColor = settings.currentCustomThemeColor
        val initialDarkMode = settings.currentDarkMode
        val initialPureBlack = settings.currentPureBlack
        // A widget tap opens the active player instead of just restoring the last screen.
        val openPlayerFromWidget = intent?.getBooleanExtra(WidgetIntents.EXTRA_OPEN_PLAYER, false) == true
        val openWidgetEditorColdStart = intent?.getBooleanExtra(WidgetIntents.EXTRA_OPEN_WIDGET_GALLERY, false) == true
        // A pinned book shortcut carries the book's folderPath (stable across a rescan/reinstall,
        // unlike a DB row id — see BookShortcuts) rather than a bookId directly. Its lookup is a
        // Room query (no synchronous snapshot exists for it), so it's resolved inside the
        // cold-start LaunchedEffect below instead of blocking onCreate on it.
        val shortcutBookPath = intent?.getStringExtra(com.betteraudio.util.BookShortcuts.EXTRA_BOOK_PATH)
        // The last book that actually played — used to restore the collapsed mini bar even when the
        // player was collapsed at close (LAST_OPEN_BOOK_ID is -1 then, so it alone can't restore it).
        // Same snapshot read once and reused for both the widget cold-start branch and this.
        val lastPlayedBookId = settings.currentLastPlayedBookId
        setContent {
            val playbackState by playerController.playbackState.collectAsStateWithLifecycle()
            // When nothing is actively loaded, keep the app themed by the last-opened book's cover
            // so the whole UI stays cohesive with "what's playing" even while idle on the home
            // screen. Tracks themeBookId (set on open/play, never cleared on close) rather than
            // lastPlayedBookId (reset to -1 by PlayerController.stop()) — otherwise closing a book
            // would revert the theme instead of keeping it until a different book opens.
            val lastPlayedCover by produceState<String?>(null) {
                settings.themeBookId.collectLatest { id ->
                    if (id == -1L) value = null
                    else repository.getBookById(id).collect { value = it?.coverArtPath }
                }
            }
            val activeCover = playbackState.coverArtUri
                ?.removePrefix("file://")
                ?.takeIf { playbackState.bookId != -1L && it.isNotBlank() }
            // When the "show series cover" toggle is on and the playing book is in a series, the
            // whole-app theme should recolor from the series cover too (not just the player).
            val seriesThemeCover by produceState<String?>(null) {
                combine(
                    settings.playerShowSeriesCover,
                    playerController.playbackState.map { it.bookId }.distinctUntilChanged()
                ) { show, id -> show to id }
                    .flatMapLatest { (show, id) ->
                        if (!show || id == -1L) flowOf(null)
                        else repository.getBookById(id).flatMapLatest { b ->
                            val sid = b?.seriesId
                            if (sid == null) flowOf(null)
                            else seriesRepository.getSeries(sid).map { it?.coverArtPath }
                        }
                    }
                    .collectLatest { value = it }
            }
            val coverPath = seriesThemeCover ?: activeCover ?: lastPlayedCover

            // Mirrors of the three cover flows above, but resolving each book/series' baked
            // blurred+reflected composite (CoverEffectBaker) instead of the sharp cover — used by
            // AppBlurredBackdrop so the app-wide Immersive background matches the player/book-info/
            // series screens' pre-baked variant instead of live-blurring the sharp cover.
            val lastPlayedCoverFx by produceState<String?>(null) {
                settings.themeBookId.collectLatest { id ->
                    if (id == -1L) value = null
                    else repository.getBookById(id).collect { value = it?.coverFxPath }
                }
            }
            val activeCoverFx by produceState<String?>(null) {
                playerController.playbackState.map { it.bookId }.distinctUntilChanged()
                    .flatMapLatest { id ->
                        if (id == -1L) flowOf(null) else repository.getBookById(id).map { it?.coverFxPath }
                    }
                    .collectLatest { value = it }
            }
            val seriesThemeCoverFx by produceState<String?>(null) {
                combine(
                    settings.playerShowSeriesCover,
                    playerController.playbackState.map { it.bookId }.distinctUntilChanged()
                ) { show, id -> show to id }
                    .flatMapLatest { (show, id) ->
                        if (!show || id == -1L) flowOf(null)
                        else repository.getBookById(id).flatMapLatest { b ->
                            val sid = b?.seriesId
                            if (sid == null) flowOf(null)
                            else seriesRepository.getSeries(sid).map { it?.coverFxPath }
                        }
                    }
                    .collectLatest { value = it }
            }
            val bakedCoverPath = seriesThemeCoverFx ?: activeCoverFx ?: lastPlayedCoverFx
            val appThemeRaw by settings.appTheme.collectAsStateWithLifecycle(initialThemeRaw)
            val colorSourceRaw by settings.themeColorSource.collectAsStateWithLifecycle(initialColorSource)
            val customThemeColor by settings.customThemeColor.collectAsStateWithLifecycle(initialCustomThemeColor)
            val darkModeRaw by settings.darkMode.collectAsStateWithLifecycle(initialDarkMode)
            val pureBlack by settings.pureBlack.collectAsStateWithLifecycle(initialPureBlack)
            val dynamicPills by settings.dynamicPills.collectAsStateWithLifecycle(false)
            val widgetDefaultCoverPath by settings.widgetDefaultCoverPath.collectAsStateWithLifecycle("")
            val appTheme = com.betteraudio.ui.theme.AppTheme.from(appThemeRaw)
            val colorSource = com.betteraudio.ui.theme.ThemeColorSource.from(colorSourceRaw)
            val darkMode = com.betteraudio.ui.theme.DarkMode.from(darkModeRaw)
            val darkTheme = when (darkMode) {
                com.betteraudio.ui.theme.DarkMode.ON -> true
                com.betteraudio.ui.theme.DarkMode.OFF -> false
                com.betteraudio.ui.theme.DarkMode.AUTO -> isSystemInDarkTheme()
            }
            VoyageTheme(
                darkTheme = darkTheme,
                appTheme = appTheme,
                colorSource = colorSource,
                customThemeColor = customThemeColor,
                pureBlack = pureBlack,
                coverArtPath = coverPath
            ) {
                val navController = rememberNavController()
                val sheetController = rememberPlayerSheetController()
                val seriesOverlayController = rememberSeriesOverlayController()
                val bookInfoOverlayController = rememberBookInfoOverlayController()
                val uiScope = androidx.compose.runtime.rememberCoroutineScope()
                val isMaterialYou = appTheme == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU
                // Shared across the home grid and the player sheet so a grid card's cover bounds
                // are available as a morph source for grid → Book Info (see CoverBoundsRegistry).
                val coverBoundsRegistry = androidx.compose.runtime.remember { com.betteraudio.ui.player.CoverBoundsRegistry() }

                // Persist the resolved Material You primary so themeless widget providers (no
                // Compose context) can render an "app color" background for custom widgets.
                val widgetAppColorPrimary = androidx.compose.material3.MaterialTheme.colorScheme.primary
                androidx.compose.runtime.LaunchedEffect(widgetAppColorPrimary) {
                    settings.setWidgetAppColor(widgetAppColorPrimary.toArgb())
                }

                // Opening a book DIRECTLY (Books view, search, author page) always shows the
                // book's own cover; the series cover appears only when playing via the series
                // path (SeriesPlayer flips the flag back on).
                fun openBookDirect(bookId: Long) {
                    uiScope.launch { settings.setPlayerShowSeriesCover(false) }
                    sheetController.open(bookId = bookId)
                }

                // Trace navigation so the in-app log shows the screen flow leading to a bug, and
                // track the current route so the mini bar can be hidden on Settings.
                var currentRoute by androidx.compose.runtime.remember { mutableStateOf<String?>("home") }
                DisposableEffect(navController) {
                    val listener = NavController.OnDestinationChangedListener { _, dest, _ ->
                        AppLog.i("Nav", "→ ${dest.route}")
                        currentRoute = dest.route
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose { navController.removeOnDestinationChangedListener(listener) }
                }

                // Restore the player the user last had open (or, from a widget tap, the active one).
                // startPlaying = false on a cold-start restore so the player shows the last book
                // without automatically starting playback — the user must tap Play themselves.
                // If nothing was left EXPANDED but a book was last played, restore the collapsed mini
                // bar so it shows that book (fixes the mini bar being empty after a cold launch).
                LaunchedEffect(Unit) {
                    val shortcutBookId = shortcutBookPath?.let { path -> repository.getBookByFolder(path)?.id }
                    if (shortcutBookPath != null && shortcutBookId == null) {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            "This book couldn't be found — it may have moved or been removed.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    val coldStartBookId = when {
                        shortcutBookId != null -> shortcutBookId
                        openPlayerFromWidget -> lastPlayedBookId.takeIf { it != -1L } ?: initialBookId
                        else -> initialBookId
                    }
                    when {
                        coldStartBookId != -1L -> {
                            // A widget tap should land on Home with the player shown (not
                            // stacked over whatever route the cold-launched app happens to
                            // start on), so Back from the player returns to Home.
                            if (openPlayerFromWidget && currentRoute != "home") {
                                navController.popBackStack("home", inclusive = false)
                            }
                            sheetController.open(bookId = coldStartBookId, startPlaying = false)
                        }
                        lastPlayedBookId != -1L ->
                            sheetController.restore(lastPlayedBookId)
                    }
                }

                // Warm-start widget taps (singleTask onNewIntent) expand the player sheet. Pop
                // any non-Home route first (e.g. Settings) so the player opens over Home instead
                // of stacking on top of it — Back then collapses the player straight to Home.
                LaunchedEffect(playerNavRequest) {
                    playerNavRequest?.let { id ->
                        if (currentRoute != "home") {
                            navController.popBackStack("home", inclusive = false)
                        }
                        sheetController.open(bookId = id)
                        playerNavRequest = null
                    }
                }

                LaunchedEffect(Unit) {
                    if (openWidgetEditorColdStart) navController.navigate("widget_gallery")
                }
                LaunchedEffect(widgetEditorNavRequest) {
                    if (widgetEditorNavRequest) {
                        navController.navigate("widget_gallery")
                        widgetEditorNavRequest = false
                    }
                }

                // Remember which book the player is showing (or -1 when collapsed) so we can
                // restore it after the app is closed and reopened.
                LaunchedEffect(sheetController.isExpanded, sheetController.target) {
                    val id = if (sheetController.isExpanded) sheetController.target?.bookId ?: -1L else -1L
                    settings.setLastOpenBookId(id)
                }

                // Back collapses the expanded player before doing anything else. Material You
                // tracks a predictive-back gesture in flight so the sheet shrinks with the finger
                // (system-style peek); Immersive (and pre-gesture devices, which degrade
                // PredictiveBackHandler to commit-only) keep a plain commit-on-back collapse.
                if (isMaterialYou) {
                    androidx.activity.compose.PredictiveBackHandler(enabled = sheetController.isExpanded) { progress ->
                        try {
                            progress.collect { event -> sheetController.seek(event.progress) }
                            sheetController.commitSeek()
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            sheetController.cancelSeek()
                        }
                    }
                } else {
                    BackHandler(enabled = sheetController.isExpanded) { sheetController.collapse() }
                }

                Box(Modifier.fillMaxSize()) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.betteraudio.ui.player.LocalCoverBoundsRegistry provides coverBoundsRegistry,
                    // So the mini player pill / floating nav pill can render a "liquid glass" look
                    // matching this exact backdrop (see ImmersiveGlass.kt) — Material You ignores it.
                    com.betteraudio.ui.immersive.components.LocalImmersiveBackdrop provides
                        com.betteraudio.ui.immersive.components.ImmersiveBackdropPaths(
                            coverPath, bakedCoverPath, widgetDefaultCoverPath.ifBlank { null }
                        ),
                    com.betteraudio.ui.immersive.components.LocalDynamicPillsEnabled provides dynamicPills
                ) {
                // Immersive: the playing/last-played cover under a very heavy blur fills the
                // app. Material You: a plain opaque background (Home's scaffold is transparent
                // and relies on this layer).
                if (appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE) {
                    com.betteraudio.ui.components.AppBlurredBackdrop(coverPath = coverPath, bakedPath = bakedCoverPath)
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    enterTransition = {
                        if (isMaterialYou) materialEnter()
                        else immersiveEnter()
                    },
                    exitTransition = {
                        if (isMaterialYou) materialExit()
                        else immersiveExit()
                    },
                    popEnterTransition = {
                        if (isMaterialYou) materialPopEnter()
                        else immersivePopEnter()
                    },
                    popExitTransition = {
                        if (isMaterialYou) materialPopExit()
                        else immersivePopExit()
                    }
                ) {

                    composable("home") {
                        HomeScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenBook = { bookId -> openBookDirect(bookId) },
                            onOpenBookInfo = { bookId -> bookInfoOverlayController.open(bookId) },
                            onOpenSearch = { navController.navigate("search") },
                            onOpenSeries = { seriesId -> seriesOverlayController.open(seriesId) },
                            onOpenAuthor = { name -> navController.navigate("author/${Uri.encode(name)}") },
                            onOpenReader = { bookId -> navController.navigate("reader/$bookId") }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            onBack = { navController.popBackStack() },
                            onOpenWidgetGallery = { navController.navigate("widget_gallery") }
                        )
                    }

                    composable("widget_gallery") {
                        com.betteraudio.ui.widget.WidgetGalleryScreen(
                            onBack = { navController.popBackStack() },
                            onEditDesign = { designId -> navController.navigate("widget_editor?designId=$designId") }
                        )
                    }

                    composable(
                        route = "widget_editor?designId={designId}",
                        arguments = listOf(navArgument("designId") { type = NavType.LongType; defaultValue = -1L })
                    ) {
                        com.betteraudio.ui.widget.WidgetEditorScreen(onBack = { navController.popBackStack() })
                    }

                    composable("search") {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onBookClick = { bookId -> openBookDirect(bookId) }
                        )
                    }

                    composable(
                        route = "author/{authorName}",
                        arguments = listOf(navArgument("authorName") { type = NavType.StringType })
                    ) { backStack ->
                        val name = Uri.decode(backStack.arguments?.getString("authorName") ?: "")
                        AuthorDetailScreen(
                            authorName = name,
                            onBack = { navController.popBackStack() },
                            onBookClick = { bookId -> openBookDirect(bookId) }
                        )
                    }

                    composable(
                        route = "reader/{bookId}",
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                    ) {
                        com.betteraudio.ui.reader.EbookReaderScreen(
                            onBack = { navController.popBackStack() },
                            // The reader VM already started playback (readFromHere's cascade); just
                            // expand the sheet over the reader — it stays on the back stack beneath it.
                            onListenFromHere = { bookId -> sheetController.open(bookId = bookId, startPlaying = false) }
                        )
                    }
                }

                // Series info overlay — drawn ABOVE the NavHost's Home content (as a sibling, not
                // a route) so Home stays mounted underneath for the cover-morph open/close
                // animation to work, exactly like Book Info inside PlayerSheet below.
                SeriesOverlay(
                    controller = seriesOverlayController,
                    onOpenPlayer = { bookId -> sheetController.open(bookId = bookId) }
                )

                // Book info overlay — same treatment as the series overlay above (a persistent
                // sibling above Home, not a route) so its cover-morph open/close works the same
                // way. Resume closes the overlay (with its own shrink-back-to-card morph) and
                // opens the full player on that book.
                BookInfoOverlay(
                    controller = bookInfoOverlayController,
                    onResume = { bookId -> sheetController.open(bookId = bookId, startPlaying = true) }
                )

                // Floating nav pill (ArchiveTune style) — home route only; the player sheet
                // draws over it and it slides away in lockstep with the sheet's expansion.
                val homeSectionRaw by settings.homeSection.collectAsStateWithLifecycle("AUDIO")
                val homeViewModeRaw by settings.homeViewMode.collectAsStateWithLifecycle("BOOKS")
                val pillSection = runCatching {
                    com.betteraudio.ui.home.HomeSection.valueOf(homeSectionRaw)
                }.getOrDefault(com.betteraudio.ui.home.HomeSection.AUDIO)
                val pillViewMode = runCatching {
                    com.betteraudio.ui.home.HomeViewMode.valueOf(homeViewModeRaw)
                }.getOrDefault(com.betteraudio.ui.home.HomeViewMode.BOOKS)
                val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                androidx.compose.animation.AnimatedVisibility(
                    visible = currentRoute == "home" && seriesOverlayController.seriesId == -1L &&
                        bookInfoOverlayController.bookId == -1L,
                    enter = androidx.compose.animation.fadeIn() +
                        androidx.compose.animation.slideInVertically { it },
                    exit = androidx.compose.animation.fadeOut() +
                        androidx.compose.animation.slideOutVertically { it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navInset + com.betteraudio.ui.components.NAV_PILL_BOTTOM_PADDING)
                ) {
                    com.betteraudio.ui.components.FloatingNavPill(
                        section = pillSection,
                        viewMode = pillViewMode,
                        onSelectSection = { s -> uiScope.launch { settings.setHomeSection(s.name) } },
                        onCycleViewMode = {
                            val next = when (pillViewMode) {
                                com.betteraudio.ui.home.HomeViewMode.BOOKS -> com.betteraudio.ui.home.HomeViewMode.SERIES
                                com.betteraudio.ui.home.HomeViewMode.SERIES -> com.betteraudio.ui.home.HomeViewMode.AUTHORS
                                com.betteraudio.ui.home.HomeViewMode.AUTHORS -> com.betteraudio.ui.home.HomeViewMode.BOOKS
                            }
                            uiScope.launch { settings.setHomeViewMode(next.name) }
                        },
                        onSearch = { navController.navigate("search") },
                        onSettings = { navController.navigate("settings") },
                        expandProgress = sheetController.expandProgress
                    )
                }

                PlayerSheet(
                    controller = sheetController,
                    playerController = playerController,
                    // Mini bar shows only on Home — hidden on every other route (search, author,
                    // settings, reader, widget editor/gallery) and while a full-bleed overlay
                    // (series or book info) covers Home.
                    hideMiniBar = currentRoute != "home" ||
                        seriesOverlayController.seriesId != -1L ||
                        bookInfoOverlayController.bookId != -1L,
                    liftForNavPill = currentRoute == "home",
                    onOpenReader = { bookId -> navController.navigate("reader/$bookId") }
                )
                } // CompositionLocalProvider(LocalCoverBoundsRegistry)

                // First launch (or first run after this update): let the user pick the app
                // theme. "" = never chosen; confirming (or dismissing) writes a value so the
                // prompt never reappears.
                if (appThemeRaw.isEmpty()) {
                    com.betteraudio.ui.components.ThemePickerDialog(
                        initial = com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU,
                        onConfirm = { chosen ->
                            uiScope.launch { settings.setAppTheme(chosen.name) }
                        },
                        onDismiss = {
                            uiScope.launch {
                                settings.setAppTheme(com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU.name)
                            }
                        }
                    )
                }

                // Launch-time update gate: draws over everything when a new release is found
                // (and the user hasn't skipped that exact version).
                val updateState by updateGateViewModel.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { updateGateViewModel.checkOnLaunch() }
                updateState.info?.let { info ->
                    // Back = decide later (reappears next launch), never a silent skip.
                    BackHandler(enabled = !updateState.downloading) { updateGateViewModel.dismissForNow() }
                    UpdateAvailableScreen(
                        versionName = info.versionName,
                        releaseNotes = info.releaseNotes,
                        downloading = updateState.downloading,
                        progress = updateState.progress,
                        error = updateState.error,
                        onInstall = { updateGateViewModel.install() },
                        onSkip = { updateGateViewModel.skip() }
                    )
                }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(WidgetIntents.EXTRA_OPEN_PLAYER, false)) {
            val activeId = playerController.playbackState.value.bookId.takeIf { it != -1L }
            if (activeId != null) {
                playerNavRequest = activeId
            } else {
                settings.currentLastPlayedBookId.takeIf { it != -1L }?.let { playerNavRequest = it }
            }
        }
        if (intent.getBooleanExtra(WidgetIntents.EXTRA_OPEN_WIDGET_GALLERY, false)) {
            widgetEditorNavRequest = true
        }
        intent.getStringExtra(com.betteraudio.util.BookShortcuts.EXTRA_BOOK_PATH)?.let { path ->
            lifecycleScope.launch {
                val id = repository.getBookByFolder(path)?.id
                if (id != null) {
                    playerNavRequest = id
                } else {
                    android.widget.Toast.makeText(
                        this@MainActivity, "This book couldn't be found — it may have moved or been removed.", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The position write that used to happen here (playerController.saveCurrentProgressNow())
        // is redundant now that the service itself flushes on pause/stop/file-transition/
        // onTaskRemoved (see PlaybackService's G2-3 fix) — it was the only thing forcing this onto
        // the exit animation frame, which is what made pressing home stutter. appStoppedAt is
        // unrelated (AudioCascade's auto-rewind-after-away-time) and doesn't need to block either.
        lifecycleScope.launch { settings.setAppStoppedAt(System.currentTimeMillis()) }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) playerController.disconnect()
    }

    private fun requestInitialPermissions() {
        val needed = buildList {
            val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
            if (!isGranted(audioPermission)) add(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
