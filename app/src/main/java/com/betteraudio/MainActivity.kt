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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.navigation.NavType
import kotlinx.coroutines.flow.first
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

    private val updateGateViewModel: UpdateGateViewModel by viewModels()

    // Set when a widget tap (warm start) asks to open the active player; observed in setContent.
    private var playerNavRequest by mutableStateOf<Long?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playerController.connect()
        requestInitialPermissions()
        // Captured before composition so the restore effect reads it before the route-tracking
        // effect (which writes -1 for the initial "home" route) can overwrite it.
        val initialBookId = runBlocking { settings.lastOpenBookId.first() }
        // A widget tap opens the active player instead of just restoring the last screen.
        val openPlayerFromWidget = intent?.getBooleanExtra(WidgetRender.EXTRA_OPEN_PLAYER, false) == true
        val coldStartBookId = if (openPlayerFromWidget)
            (runBlocking { settings.lastPlayedBookId.first() }.takeIf { it != -1L } ?: initialBookId)
        else initialBookId
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
            val coverPath = activeCover ?: lastPlayedCover
            VoyageTheme(coverArtPath = coverPath) {
                val navController = rememberNavController()
                val sheetController = rememberPlayerSheetController()

                // Trace navigation so the in-app log shows the screen flow leading to a bug.
                DisposableEffect(navController) {
                    val listener = NavController.OnDestinationChangedListener { _, dest, _ ->
                        AppLog.i("Nav", "→ ${dest.route}")
                    }
                    navController.addOnDestinationChangedListener(listener)
                    onDispose { navController.removeOnDestinationChangedListener(listener) }
                }

                // Restore the player the user last had open (or, from a widget tap, the active one).
                // startPlaying = false on a cold-start restore so the player shows the last book
                // without automatically starting playback — the user must tap Play themselves.
                LaunchedEffect(Unit) {
                    if (coldStartBookId != -1L) sheetController.open(bookId = coldStartBookId, startPlaying = false)
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
                            onOpenBook = { bookId -> sheetController.open(bookId = bookId) },
                            onOpenBookInfo = { bookId -> sheetController.open(bookId = bookId, startInfo = true) },
                            onOpenSearch = { navController.navigate("search") },
                            onOpenSeries = { seriesId -> navController.navigate("series/$seriesId") },
                            onOpenAuthor = { name -> navController.navigate("author/${Uri.encode(name)}") }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable("search") {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onBookClick = { bookId -> sheetController.open(bookId = bookId) }
                        )
                    }

                    composable(
                        route = "series/{seriesId}",
                        arguments = listOf(navArgument("seriesId") { type = NavType.LongType })
                    ) { backStack ->
                        val sid = backStack.arguments?.getLong("seriesId") ?: -1L
                        SeriesDetailScreen(
                            onBack = { navController.popBackStack() },
                            onOpenPlayer = { sheetController.open(groupId = sid) }
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
                            onBookClick = { bookId -> sheetController.open(bookId = bookId) }
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

                PlayerSheet(controller = sheetController, playerController = playerController)

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
