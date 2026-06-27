package com.betteraudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlayerController
import com.betteraudio.ui.home.HomeScreen
import com.betteraudio.ui.theme.colorOsEnter
import com.betteraudio.ui.theme.colorOsExit
import com.betteraudio.ui.theme.colorOsPopEnter
import com.betteraudio.ui.theme.colorOsPopExit
import com.betteraudio.ui.join.JoinOptionsScreen
import com.betteraudio.ui.player.PlayerScreen
import com.betteraudio.ui.search.SearchScreen
import com.betteraudio.ui.series.SeriesDetailScreen
import com.betteraudio.ui.settings.SettingsScreen
import com.betteraudio.ui.theme.VoyageTheme
import com.betteraudio.widget.WidgetRender
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerController: PlayerController
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var repository: AudiobookRepository

    // Set when a widget tap (warm start) asks to open the active player; observed in setContent.
    private var playerNavRequest by mutableStateOf<Long?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    @OptIn(ExperimentalSharedTransitionApi::class)
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

                // Restore the player the user last had open (or, from a widget tap, the active one).
                LaunchedEffect(Unit) {
                    if (coldStartBookId != -1L) navController.navigate("player/$coldStartBookId")
                }

                // Warm-start widget taps (singleTask onNewIntent) route here.
                LaunchedEffect(playerNavRequest) {
                    playerNavRequest?.let { id ->
                        navController.navigate("player/$id")
                        playerNavRequest = null
                    }
                }

                // Remember which book the player is showing (or -1 elsewhere) so we can restore it
                // after the app is closed and reopened.
                val currentEntry by navController.currentBackStackEntryAsState()
                LaunchedEffect(currentEntry) {
                    val route = currentEntry?.destination?.route
                    val id = if (route == "player/{bookId}?startInfo={startInfo}")
                        currentEntry?.arguments?.getLong("bookId") ?: -1L
                    else -1L
                    settings.setLastOpenBookId(id)
                }

                SharedTransitionLayout {
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
                            sharedScope = this@SharedTransitionLayout,
                            animScope = this,
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenBook = { bookId -> navController.navigate("player/$bookId") },
                            onOpenBookInfo = { bookId -> navController.navigate("player/$bookId?startInfo=true") },
                            onOpenSearch = { navController.navigate("search") },
                            onOpenSeries = { name -> navController.navigate("series/${Uri.encode(name)}") },
                            onJoinBooks = { bookIds ->
                                navController.navigate("join_options?bookIds=${Uri.encode(bookIds)}")
                            },
                            onEditGroup = { groupId ->
                                navController.navigate("join_options?groupId=$groupId")
                            },
                            onOpenGroupInfo = { groupId ->
                                navController.navigate("group_info/$groupId")
                            }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }

                    // One screen for both the book-info panel and the transport controls. The
                    // info↔controls switch is an in-place crossfade inside PlayerScreen — never a
                    // navigation — so the two can't stack and back always returns straight home.
                    composable(
                        route = "player/{bookId}?startInfo={startInfo}",
                        arguments = listOf(
                            navArgument("bookId") { type = NavType.LongType },
                            navArgument("startInfo") {
                                type = NavType.BoolType
                                defaultValue = false
                            }
                        )
                    ) {
                        PlayerScreen(
                            sharedScope = this@SharedTransitionLayout,
                            animScope = this,
                            onBack = { navController.popBackStack("home", inclusive = false) },
                            initiallyShowInfo = it.arguments?.getBoolean("startInfo") ?: false
                        )
                    }

                    composable("search") {
                        SearchScreen(
                            onBack = { navController.popBackStack() },
                            onBookClick = { bookId -> navController.navigate("player/$bookId") }
                        )
                    }

                    composable(
                        route = "series/{seriesName}",
                        arguments = listOf(navArgument("seriesName") { type = NavType.StringType })
                    ) { backStack ->
                        val name = Uri.decode(backStack.arguments?.getString("seriesName") ?: "")
                        SeriesDetailScreen(
                            seriesName = name,
                            onBack = { navController.popBackStack() },
                            onBookClick = { bookId -> navController.navigate("player/$bookId") }
                        )
                    }

                    composable(
                        route = "group_info/{groupId}",
                        arguments = listOf(navArgument("groupId") { type = NavType.LongType })
                    ) {
                        PlayerScreen(
                            sharedScope = this@SharedTransitionLayout,
                            animScope = this,
                            onBack = { navController.popBackStack("home", inclusive = false) },
                            initiallyShowInfo = true
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
