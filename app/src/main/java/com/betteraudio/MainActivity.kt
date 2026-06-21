package com.betteraudio

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import kotlinx.coroutines.runBlocking
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.betteraudio.playback.PlayerController
import com.betteraudio.ui.home.HomeScreen
import com.betteraudio.ui.join.JoinOptionsScreen
import com.betteraudio.ui.player.PlayerScreen
import com.betteraudio.ui.search.SearchScreen
import com.betteraudio.ui.series.SeriesDetailScreen
import com.betteraudio.ui.settings.SettingsScreen
import com.betteraudio.ui.theme.VoyageTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@UnstableApi
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playerController: PlayerController

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        playerController.connect()
        requestInitialPermissions()
        setContent {
            val playbackState by playerController.playbackState.collectAsStateWithLifecycle()
            val coverPath = playbackState.coverArtUri
                ?.removePrefix("file://")
                ?.takeIf { playbackState.bookId != -1L && it.isNotBlank() }
            VoyageTheme(coverArtPath = coverPath) {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {

                    composable("home") {
                        HomeScreen(
                            onOpenSettings = { navController.navigate("settings") },
                            onOpenBook = { bookId -> navController.navigate("player/$bookId") },
                            onOpenSearch = { navController.navigate("search") },
                            onOpenSeries = { name -> navController.navigate("series/${Uri.encode(name)}") },
                            onJoinBooks = { bookIds ->
                                navController.navigate("join_options?bookIds=${Uri.encode(bookIds)}")
                            },
                            onEditGroup = { groupId ->
                                navController.navigate("join_options?groupId=$groupId")
                            }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }

                    composable(
                        route = "player/{bookId}",
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                    ) {
                        PlayerScreen(onBack = { navController.popBackStack() })
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

    override fun onStop() {
        super.onStop()
        runBlocking { playerController.saveCurrentProgressNow() }
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
