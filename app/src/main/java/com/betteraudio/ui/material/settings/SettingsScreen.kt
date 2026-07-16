package com.betteraudio.ui.material.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.components.FolderBrowser
import com.betteraudio.ui.material.MaterialStyle
import com.betteraudio.ui.settings.SettingsSection
import com.betteraudio.ui.settings.SettingsViewModel
import com.betteraudio.ui.settings.aboutSection
import com.betteraudio.ui.settings.aiSection
import com.betteraudio.ui.settings.backupSection
import com.betteraudio.ui.settings.diagnosticsSection
import com.betteraudio.ui.settings.hasAllFilesAccess
import com.betteraudio.ui.settings.librarySection
import com.betteraudio.ui.settings.playbackSection
import com.betteraudio.ui.settings.presetsSection
import com.betteraudio.ui.settings.rootSection
import com.betteraudio.ui.settings.themeSection
import com.betteraudio.ui.settings.updatesSection
import com.betteraudio.ui.settings.widgetSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCreateWidget: () -> Unit = {},
    onEditWidget: (Long) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val libraryFolder   by viewModel.libraryFolder.collectAsStateWithLifecycle()
    val skipForwardMs   by viewModel.skipForwardMs.collectAsStateWithLifecycle()
    val skipBackMs      by viewModel.skipBackMs.collectAsStateWithLifecycle()
    val bookCount       by viewModel.bookCount.collectAsStateWithLifecycle()
    val ignoredBooks    by viewModel.ignoredBooks.collectAsStateWithLifecycle()
    val rescanRunning        by viewModel.rescanRunning.collectAsStateWithLifecycle()
    val coverRefreshRunning  by viewModel.coverRefreshRunning.collectAsStateWithLifecycle()
    val resetRunning         by viewModel.resetRunning.collectAsStateWithLifecycle()
    val geminiApiKey    by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val updateState               by viewModel.updateState.collectAsStateWithLifecycle()
    val whatsNew                  by viewModel.whatsNew.collectAsStateWithLifecycle()
    val currentSection            by viewModel.currentSection.collectAsStateWithLifecycle()
    val autoRewindSeconds         by viewModel.autoRewindSeconds.collectAsStateWithLifecycle()
    val autoRewindThresholdMinutes by viewModel.autoRewindThresholdMinutes.collectAsStateWithLifecycle()
    val skipSilenceMinMs          by viewModel.skipSilenceMinMs.collectAsStateWithLifecycle()
    val skipSilenceThreshold      by viewModel.skipSilenceThreshold.collectAsStateWithLifecycle()
    val skipSilencePaddingMs      by viewModel.skipSilencePaddingMs.collectAsStateWithLifecycle()
    val sleepFadeSeconds          by viewModel.sleepFadeSeconds.collectAsStateWithLifecycle()
    val sleepShakeEnabled         by viewModel.sleepShakeEnabled.collectAsStateWithLifecycle()
    val sleepShakeResetMinutes    by viewModel.sleepShakeResetMinutes.collectAsStateWithLifecycle()
    val sleepScheduleEnabled      by viewModel.sleepScheduleEnabled.collectAsStateWithLifecycle()
    val sleepScheduleStartMinutes by viewModel.sleepScheduleStartMinutes.collectAsStateWithLifecycle()
    val sleepScheduleEndMinutes   by viewModel.sleepScheduleEndMinutes.collectAsStateWithLifecycle()
    val sleepScheduleDefaultMinutes by viewModel.sleepScheduleDefaultMinutes.collectAsStateWithLifecycle()
    val headsetMultiPressEnabled by viewModel.headsetMultiPressEnabled.collectAsStateWithLifecycle()
    val headsetDoublePressAction by viewModel.headsetDoublePressAction.collectAsStateWithLifecycle()
    val headsetTriplePressAction by viewModel.headsetTriplePressAction.collectAsStateWithLifecycle()
    val btAutoResumeEnabled by viewModel.btAutoResumeEnabled.collectAsStateWithLifecycle()
    val btAutoResumeWindowMinutes by viewModel.btAutoResumeWindowMinutes.collectAsStateWithLifecycle()
    val importStructure           by viewModel.importStructure.collectAsStateWithLifecycle()
    val appTheme                  by viewModel.appTheme.collectAsStateWithLifecycle()
    val themeColorSource          by viewModel.themeColorSource.collectAsStateWithLifecycle()
    val customThemeColor          by viewModel.customThemeColor.collectAsStateWithLifecycle()
    val darkMode                  by viewModel.darkMode.collectAsStateWithLifecycle()
    val pureBlack                 by viewModel.pureBlack.collectAsStateWithLifecycle()
    val presets                   by viewModel.presets.collectAsStateWithLifecycle()
    val widgetDefaultCover        by viewModel.widgetDefaultCover.collectAsStateWithLifecycle()
    val widgetHideWhenIdle        by viewModel.widgetHideWhenIdle.collectAsStateWithLifecycle()
    val customWidgets             by viewModel.customWidgets.collectAsStateWithLifecycle()

    var showBrowser by remember { mutableStateOf(false) }
    var showEbookBrowser by remember { mutableStateOf(false) }
    var storageGranted by remember { mutableStateOf(hasAllFilesAccess()) }
    val ebookFolder by viewModel.ebookFolder.collectAsStateWithLifecycle()

    val storageSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { storageGranted = hasAllFilesAccess() }

    LaunchedEffect(Unit) { viewModel.loadWhatsNew() }

    if (showBrowser) {
        FolderBrowser(
            startPath = libraryFolder.ifBlank { "/storage/emulated/0" },
            onSelect = {
                viewModel.setLibraryFolder(it)
                viewModel.rescan()
                showBrowser = false
            },
            onCancel = { showBrowser = false }
        )
    }

    if (showEbookBrowser) {
        FolderBrowser(
            startPath = ebookFolder.ifBlank { "/storage/emulated/0" },
            onSelect = {
                viewModel.setEbookFolder(it)
                showEbookBrowser = false
            },
            onCancel = { showEbookBrowser = false }
        )
    }

    BackHandler(enabled = currentSection != SettingsSection.Root) {
        viewModel.navigateTo(SettingsSection.Root)
    }

    val sectionTitle = when (currentSection) {
        SettingsSection.Root -> "Settings"
        SettingsSection.Theme -> "Theme"
        SettingsSection.Library -> "Library"
        SettingsSection.Playback -> "Playback"
        SettingsSection.Presets -> "Audio presets"
        SettingsSection.Widget -> "Widget"
        SettingsSection.AI -> "AI Synopsis"
        SettingsSection.Backup -> "Backup & restore"
        SettingsSection.Updates -> "Updates"
        SettingsSection.About -> "About"
        SettingsSection.Diagnostics -> "Diagnostics"
    }

    Scaffold(
        containerColor = MaterialStyle.surfaceColor(),
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = { Text(sectionTitle, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentSection == SettingsSection.Root) onBack()
                        else viewModel.navigateTo(SettingsSection.Root)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialStyle.surfaceColor()
                )
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = currentSection,
            transitionSpec = {
                fadeIn(tween(160)) togetherWith fadeOut(tween(160))
            },
            label = "settings_section",
            modifier = Modifier.padding(padding)
        ) { section ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (section) {
                    SettingsSection.Root -> rootSection(viewModel)
                    SettingsSection.Theme -> themeSection(
                        appTheme, themeColorSource, customThemeColor, darkMode, pureBlack, viewModel
                    )
                    SettingsSection.Library -> librarySection(
                        context, storageGranted, libraryFolder, bookCount, rescanRunning,
                        coverRefreshRunning, resetRunning, ignoredBooks, importStructure,
                        storageSettingsLauncher, { showBrowser = true },
                        ebookFolder, { showEbookBrowser = true }, viewModel
                    )
                    SettingsSection.Playback -> playbackSection(
                        skipForwardMs, skipBackMs,
                        autoRewindSeconds, autoRewindThresholdMinutes,
                        skipSilenceMinMs, skipSilenceThreshold, skipSilencePaddingMs,
                        sleepFadeSeconds, sleepShakeEnabled, sleepShakeResetMinutes,
                        sleepScheduleEnabled, sleepScheduleStartMinutes, sleepScheduleEndMinutes,
                        sleepScheduleDefaultMinutes,
                        headsetMultiPressEnabled, headsetDoublePressAction, headsetTriplePressAction,
                        btAutoResumeEnabled, btAutoResumeWindowMinutes,
                        viewModel
                    )
                    SettingsSection.Presets -> presetsSection(presets, viewModel)
                    SettingsSection.Widget -> widgetSection(
                        widgetDefaultCover, widgetHideWhenIdle, customWidgets,
                        onCreateWidget, onEditWidget, viewModel
                    )
                    SettingsSection.AI -> aiSection(geminiApiKey, viewModel)
                    SettingsSection.Backup -> backupSection(context, viewModel)
                    SettingsSection.Updates -> updatesSection(updateState, whatsNew, viewModel)
                    SettingsSection.About -> aboutSection(updateState, viewModel)
                    SettingsSection.Diagnostics -> diagnosticsSection(context)
                }
            }
        }
    }
}
