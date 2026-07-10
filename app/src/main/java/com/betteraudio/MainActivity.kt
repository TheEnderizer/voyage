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
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.navigation.NavType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.Modifier
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlayerController
import com.betteraudio.ui.author.AuthorDetailScreen
import com.betteraudio.ui.home.HomeScreen
import com.betteraudio.ui.theme.colorOsEnter
import com.betteraudio.ui.theme.colorOsExit
import com.betteraudio.ui.theme.colorOsPopEnter
import com.betteraudio.ui.theme.colorOsPopExit
import com.betteraudio.ui.join.JoinOptionsScreen
import com.betteraudio.ui.player.PlayerSheet
import com.betteraudio.ui.player.rememberPlayerSheetController
import com.betteraudio.ui.search.SearchScreen
import com.betteraudio.ui.series.SeriesDetailScreen
import com.betteraudio.ui.settings.SettingsScreen
import com.betteraudio.ui.theme.VoyageTheme
import com.betteraudio.ui.update.UpdateAvailableScreen
import com.betteraudio.ui.update.UpdateGateViewModel
import com.betteraudio.util.AppLog
import com.betteraudio.widget.WidgetRender
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
        // effect (which writes -1 for the initial "home" route) can overwrite it.
        val initialBookId = runBlocking { settings.lastOpenBookId.first() }
        // Theme read synchronously so the first frame renders in the right theme (no flash).
        // "" = never chosen → the first-launch theme prompt is shown over the app.
        val initialThemeRaw = runBlocking { settings.appTheme.first() }
        val initialColorSource = runBlocking { settings.themeColorSource.first() }
        val initialCustomThemeColor = runBlocking { settings.customThemeColor.first() }
        val initialDarkMode = runBlocking { settings.darkMode.first() }
        val initialPureBlack = runBlocking { settings.pureBlack.first() }
        // A widget tap opens the active player instead of just restoring the last screen.
        val openPlayerFromWidget = intent?.getBooleanExtra(WidgetRender.EXTRA_OPEN_PLAYER, false) == true
        val coldStartBookId = if (openPlayerFromWidget)
            (runBlocking { settings.lastPlayedBookId.first() }.takeIf { it != -1L } ?: initialBookId)
        else initialBookId
        // The last book that actually played — used to restore the collapsed mini bar even when the
        // player was collapsed at close (LAST_OPEN_BOOK_ID is -1 then, so it alone can't restore it).
        val lastPlayedBookId = runBlocking { settings.lastPlayedBookId.first() }
        setContent {
            val playbackState by playerController.playbackState.collectAsStateWithLifecycle()
            // When nothing is actively loaded, keep the app themed by the last-played book's cover
            // so the whole UI stays cohesive with "what's playing" even while idle on the home screen.
            val lastPlayedCover by produceState<String?>(null) {
                settings.lastPlayedBookId.collectLatest { id ->
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
            val appThemeRaw by settings.appTheme.collectAsStateWithLifecycle(initialThemeRaw)
            val colorSourceRaw by settings.themeColorSource.collectAsStateWithLifecycle(initialColorSource)
            val customThemeColor by settings.customThemeColor.collectAsStateWithLifecycle(initialCustomThemeColor)
            val darkModeRaw by settings.darkMode.collectAsStateWithLifecycle(initialDarkMode)
            val pureBlack by settings.pureBlack.collectAsStateWithLifecycle(initialPureBlack)
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
                val uiScope = androidx.compose.runtime.rememberCoroutineScope()

                // Opening a book DIRECTLY (Books view, search, author page) always shows the
                // book's own cover; the series cover appears only when playing via the series
                // path (SeriesPlayer flips the flag back on).
                fun openBookDirect(bookId: Long, startInfo: Boolean = false) {
                    uiScope.launch { settings.setPlayerShowSeriesCover(false) }
                    sheetController.open(bookId = bookId, startInfo = startInfo)
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
                    when {
                        coldStartBookId != -1L ->
                            sheetController.open(bookId = coldStartBookId, startPlaying = false)
                        lastPlayedBookId != -1L ->
                            sheetController.restore(lastPlayedBookId)
                    }
                }

                // Warm-start widget taps (singleTask onNewIntent) expand the player sheet.
                LaunchedEffect(playerNavRequest) {
                    playerNavRequest?.let { id ->
                        sheetController.open(bookId = id)
                        playerNavRequest = null
                    }
                }

                // Remember which book the player is showing (or -1 when collapsed) so we can
                // restore it after the app is closed and reopened.
                LaunchedEffect(sheetController.isExpanded, sheetController.target) {
                    val id = if (sheetController.isExpanded) sheetController.target?.bookId ?: -1L else -1L
                    settings.setLastOpenBookId(id)
                }

                // Back collapses the expanded player before doing anything else.
                BackHandler(enabled = sheetController.isExpanded) { sheetController.collapse() }

                Box(Modifier.fillMaxSize()) {
                // Immersive: the playing/last-played cover under a very heavy blur fills the
                // app. Material You: a plain opaque background (Home's scaffold is transparent
                // and relies on this layer).
                if (appTheme == com.betteraudio.ui.theme.AppTheme.IMMERSIVE) {
                    com.betteraudio.ui.components.AppBlurredBackdrop(coverPath = coverPath)
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
                    enterTransition = { colorOsEnter() },
                    exitTransition = { colorOsExit() },
                    popEnterTransition = { colorOsPopEnter() },
                    popExitTransition = { colorOsPopExit() }
                ) {

                    composable("home") {
                        HomeScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenBook = { bookId -> openBookDirect(bookId) },
                            onOpenBookInfo = { bookId -> openBookDirect(bookId, startInfo = true) },
                            onOpenSearch = { navController.navigate("search") },
                            onOpenSeries = { seriesId -> navController.navigate("series/$seriesId") },
                            onOpenAuthor = { name -> navController.navigate("author/${Uri.encode(name)}") },
                            onOpenReader = { bookId -> navController.navigate("reader/$bookId") }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable("search") {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onBookClick = { bookId -> openBookDirect(bookId) }
                        )
                    }

                    composable(
                        route = "series/{seriesId}",
                        arguments = listOf(navArgument("seriesId") { type = NavType.LongType })
                    ) { backStack ->
                        SeriesDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPlayer = { bookId -> sheetController.open(bookId = bookId) }
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

                    // Join / Edit group options
                    composable(
                        route = "join_options?bookIds={bookIds}&groupId={groupId}",
                        arguments = listOf(
                            navArgument("bookIds") {
                                type = NavType.StringType
                                nullable = true
                                defaultValue = null
                            },
                            navArgument("groupId") {
                                type = NavType.LongType
                                defaultValue = -1L
                            }
                        )
                    ) {
                        JoinOptionsScreen(
                            onBack = { navController.popBackStack() },
                            onSaved = { navController.popBackStack() }
                        )
                    }
                }

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
                    visible = currentRoute == "home",
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
                    hideMiniBar = currentRoute == "settings" || currentRoute?.startsWith("reader/") == true,
                    liftForNavPill = currentRoute == "home",
                    onOpenReader = { bookId -> navController.navigate("reader/$bookId") }
                )

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
        if (intent.getBooleanExtra(WidgetRender.EXTRA_OPEN_PLAYER, false)) {
            val id = playerController.playbackState.value.bookId.takeIf { it != -1L }
                ?: runBlocking { settings.lastPlayedBookId.first() }
            if (id != -1L) playerNavRequest = id
        }
    }

    override fun onStop() {
        super.onStop()
        runBlocking {
            playerController.saveCurrentProgressNow()
            settings.setAppStoppedAt(System.currentTimeMillis())
        }
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
