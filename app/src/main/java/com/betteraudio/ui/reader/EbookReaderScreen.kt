package com.betteraudio.ui.reader

import android.app.Activity
import android.os.BatteryManager
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.settings.ReaderPrefs
import com.betteraudio.ui.haptics.*
import com.betteraudio.ui.reader.render.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/** Which full-screen pane the reader shows. Contents and Reading settings used to be a
 *  `ModalBottomSheet` and an `AlertDialog` — both deleted. Neither is a separate nav destination:
 *  both need the same [EbookReaderViewModel] instance the reading pane already holds, and Hilt
 *  scopes a route's `hiltViewModel()` to that route's own back-stack entry, so a real second
 *  destination would mean either a fresh (wrong) ViewModel or plumbing the instance through nav
 *  args. A `BackHandler` on this local pane state gives the same "real screen, real back arrow,
 *  system back returns you to reading" behavior the redesign calls for. */
private enum class ReaderPane { READING, CONTENTS, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookReaderScreen(
    onBack: () -> Unit,
    onListenFromHere: (Long) -> Unit,
    onOpenSpike: (Long) -> Unit = {},
    viewModel: EbookReaderViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pane by rememberSaveable { mutableStateOf(ReaderPane.READING) }

    androidx.activity.compose.BackHandler(enabled = pane != ReaderPane.READING) { pane = ReaderPane.READING }

    ReaderWindowEffects(state.prefs) { viewModel.flushCurrent() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> ReaderErrorContent(state.error!!, onBack)
            pane == ReaderPane.CONTENTS -> com.betteraudio.ui.reader.contents.ContentsScreen(
                state = state, viewModel = viewModel,
                onBack = { pane = ReaderPane.READING },
                onSelectSpine = { index -> viewModel.openSpine(index); pane = ReaderPane.READING }
            )
            pane == ReaderPane.SETTINGS -> com.betteraudio.ui.reader.contents.ReadingSettingsScreen(
                state = state, viewModel = viewModel,
                onBack = { pane = ReaderPane.READING }
            )
            else -> with(this) {
                ReaderContent(
                    state, viewModel, onBack, onListenFromHere, onOpenSpike, scope,
                    onOpenContents = { pane = ReaderPane.CONTENTS },
                    onOpenSettings = { pane = ReaderPane.SETTINGS }
                )
            }
        }
    }
}

/**
 * The settings that act on the window rather than on the page: keep-awake (#134), immersive
 * fullscreen (#136), orientation lock (#135) and forced brightness (#46/#48).
 *
 * All four are applied in one `DisposableEffect` keyed on the values themselves and, crucially,
 * **undone on dispose** — every one of them outlives this screen if left set, which would mean
 * leaving the reader locked to portrait or the whole app pinned at 10% brightness.
 */
@Composable
private fun ReaderWindowEffects(prefs: ReaderPrefs, onLeave: () -> Unit) {
    val view = LocalView.current
    DisposableEffect(prefs.keepScreenOn, prefs.fullscreen, prefs.orientation, prefs.brightness) {
        val activity = view.context as? Activity
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }

        if (prefs.keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (prefs.fullscreen) {
            controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }

        activity?.requestedOrientation = ReaderOrientation.fromName(prefs.orientation).activityInfoValue

        // -1 (BRIGHTNESS_OVERRIDE_NONE) hands control back to the system setting.
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (prefs.brightness < 0f) -1f else prefs.brightness.coerceIn(0.01f, 1f)
            }
        }

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            previousOrientation?.let { activity?.requestedOrientation = it }
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
            onLeave()
        }
    }
}

@Composable
private fun ReaderErrorContent(error: ReaderError, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(error.message, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        HapticTextButton(onClick = onBack) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.ReaderContent(
    state: ReaderUiState,
    viewModel: EbookReaderViewModel,
    onBack: () -> Unit,
    onListenFromHere: (Long) -> Unit,
    onOpenSpike: (Long) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val prefs = state.prefs
    val palette = prefs.palette(isSystemInDarkTheme())
    val typography = remember(prefs, palette) { typographyFrom(prefs, palette.fg) }
    val measurer = rememberBlockMeasurer(typography)

    // Whether the configured reading aid is currently running. Deliberately screen state, not a
    // setting: "auto-scroll at 1.2 lines/s" is a preference, "auto-scroll is running right now" is
    // not, and persisting the latter would mean reopening a book straight into a moving page.
    var aidsRunning by remember { mutableStateOf(false) }
    val hasAid = prefs.autoPageTurnSeconds > 0 || (prefs.scrolled && prefs.autoScrollSpeed > 0f)
    LaunchedEffect(hasAid) { if (!hasAid) aidsRunning = false }

    val chromeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
        (if (prefs.showHeader) 64.dp else 8.dp) + 20.dp
    val chromeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
        (if (prefs.showFooter) 96.dp else 8.dp) + 12.dp

    fun turnRelative(delta: Int) {
        val target = state.currentPageIndex + delta
        when {
            target < 0 -> viewModel.prevChapter()
            target > state.pages.lastIndex -> viewModel.nextChapter()
            else -> viewModel.onPageChanged(target)
        }
    }

    // Volume keys turn pages (#70). Consuming the event is what stops the volume UI appearing;
    // when the setting is off the event falls through untouched and volume works normally.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(prefs.volumeKeysTurn) { if (prefs.volumeKeysTurn) focusRequester.requestFocus() }

    ReaderSurface(
        pages = state.pages,
        pageIndex = state.currentPageIndex,
        prefs = prefs,
        palette = palette,
        typography = typography,
        chromeTop = chromeTop,
        chromeBottom = chromeBottom,
        onPageIndexChange = { viewModel.onPageChanged(it) },
        onPrevChapter = { viewModel.prevChapter() },
        onNextChapter = { viewModel.nextChapter() },
        onToggleChrome = { viewModel.toggleChrome() },
        onBrightnessDrag = { delta ->
            val current = if (prefs.brightness < 0f) 0.5f else prefs.brightness
            viewModel.updatePrefs { it.copy(brightness = (current + delta).coerceIn(0.01f, 1f)) }
        },
        onViewportMeasured = { w, h, _ -> viewModel.preparePages(measurer, w, h) },
        paginationKey = remember(state.currentSpineIndex, typography) { state.currentSpineIndex to typography },
        aidsRunning = aidsRunning,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (!prefs.volumeKeysTurn || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.VolumeDown -> { turnRelative(1); true }
                    Key.VolumeUp -> { turnRelative(-1); true }
                    else -> false
                }
            },
    )

    // ── Top chrome (#151) ───────────────────────────────────────────────────
    // The chapter title IS the button that opens Contents (with a trailing chevron saying so),
    // and Aa opens Reading settings — the redesign's replacement for the old ⋮ overflow, which
    // buried both behind an extra tap and a menu label.
    AnimatedVisibility(
        visible = state.chromeVisible && prefs.showHeader,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        TopAppBar(
            title = {
                Column(
                    Modifier.clickable(onClick = onOpenContents),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        state.book?.displayTitle ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            state.currentSpineTitle ?: "Contents",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            navigationIcon = {
                HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                if (hasAid) {
                    HapticIconButton(onClick = { aidsRunning = !aidsRunning }) {
                        Icon(
                            if (aidsRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (aidsRunning) "Stop auto-advance" else "Start auto-advance"
                        )
                    }
                }
                // ⚠️ THROWAWAY — Phase 0 item 3. Not gated on BuildConfig.DEBUG: this app is only
                // ever deployed as a release-signed build, so a DEBUG-only gate would make the
                // spike unreachable on the build actually installed on the test device. Delete
                // this button along with ui/reader/spike/ when Phase 0 ends.
                state.book?.id?.let { bookId ->
                    HapticIconButton(onClick = { onOpenSpike(bookId) }) { Text("🔬") }
                }
                HapticTextButton(onClick = onOpenSettings) {
                    Text("Aa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        )
    }

    // ── Bottom chrome (#152–156) ────────────────────────────────────────────
    AnimatedVisibility(
        visible = state.chromeVisible && prefs.showFooter,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                BookScrubber(
                    spineCount = state.spine.size,
                    currentSpineIndex = state.currentSpineIndex,
                    currentPageIndex = state.currentPageIndex,
                    pageCount = state.pages.size,
                    onScrub = { viewModel.jumpToOverallFraction(it) }
                )
                Spacer(Modifier.height(2.dp))
                StatusLine(state, prefs)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HapticIconButton(
                        onClick = { viewModel.prevChapter() },
                        enabled = state.currentSpineIndex > 0
                    ) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous chapter") }

                    if (state.hasAudio) {
                        HapticFilledTonalButton(onClick = {
                            scope.launch { viewModel.listenFromHere()?.let(onListenFromHere) }
                        }) {
                            Icon(Icons.Default.Headphones, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Listen from here")
                        }
                    } else {
                        Spacer(Modifier.width(1.dp))
                    }

                    HapticIconButton(
                        onClick = { viewModel.nextChapter() },
                        enabled = state.currentSpineIndex < state.spine.size - 1
                    ) { Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next chapter") }
                }
            }
        }
    }

    // ── Sync status (priority: aligning → model downloading → synced → approximate) ──
    // Gated behind EBOOK_SYNC_UI: the whole listen↔read sync surface ships frozen (Phase 0).
    // Its controls now live in the Contents screen's own overflow, not here.
    if (com.betteraudio.util.FeatureFlags.EBOOK_SYNC_UI) {
        val align = state.alignProgress
        val model = state.modelState
        Box(Modifier.align(Alignment.TopCenter).padding(top = 72.dp, start = 12.dp, end = 12.dp)) {
            when {
                align?.running == true -> {
                    val total = align.chaptersTotal.coerceAtLeast(1)
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Improving sync… ${align.chaptersDone}/$total · ${align.anchorsFound} anchors",
                                    style = MaterialTheme.typography.labelMedium)
                                LinearProgressIndicator(
                                    progress = { align.chaptersDone.toFloat() / total },
                                    modifier = Modifier.width(180.dp).padding(top = 2.dp)
                                )
                            }
                            HapticIconButton(onClick = { viewModel.cancelSync() }) { Icon(Icons.Default.Close, "Cancel") }
                        }
                    }
                }
                model is com.betteraudio.data.transcribe.ModelState.Downloading ->
                    StatusChip("Downloading speech model… ${model.pct}%", MaterialTheme.colorScheme.secondaryContainer)
                model is com.betteraudio.data.transcribe.ModelState.Unzipping ->
                    StatusChip("Preparing speech model…", MaterialTheme.colorScheme.secondaryContainer)
                state.hasAudio && state.anchorCount > 0 ->
                    StatusChip("Synced · ${state.anchorCount} anchors", MaterialTheme.colorScheme.tertiaryContainer)
                state.chapterMapApproximate && state.hasAudio ->
                    StatusChip("Approximate alignment — see Contents to align or improve sync", MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

/**
 * The footer's information row (#81–84, #153–155): position on the left in whichever style the
 * user picked, and whatever optional readouts they turned on — remaining pages, remaining time,
 * clock, battery — on the right.
 */
@Composable
private fun StatusLine(state: ReaderUiState, prefs: ReaderPrefs) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pageCount = state.pages.size
    val position = when (ReaderProgressStyle.fromName(prefs.progressStyle)) {
        ReaderProgressStyle.PAGE -> if (pageCount > 0) "Page ${state.currentPageIndex + 1} of $pageCount" else ""
        ReaderProgressStyle.PERCENT -> "${(bookFraction(state) * 100).toInt()}%"
        ReaderProgressStyle.BOTH ->
            if (pageCount > 0) "Page ${state.currentPageIndex + 1} of $pageCount · ${(bookFraction(state) * 100).toInt()}%" else ""
    }

    val right = buildList {
        val pagesLeft = (pageCount - state.currentPageIndex - 1).coerceAtLeast(0)
        if (prefs.showRemainingPages && pageCount > 0) {
            add("$pagesLeft page${if (pagesLeft == 1) "" else "s"} left")
        }
        if (prefs.showRemainingTime && pageCount > 0) {
            // Words are counted from the pages actually left in this chapter — the only text we
            // have measured. Presented as "in chapter" so it can't read as a whole-book estimate
            // it isn't.
            val wordsLeft = state.pages.drop(state.currentPageIndex + 1)
                .sumOf { page -> page.blocks.sumOf { it.text.count(Char::isWhitespace) + 1 } }
            val minutes = (wordsLeft.toFloat() / prefs.wordsPerMinute.coerceAtLeast(60)).toInt()
            add(if (minutes < 1) "<1 min left in chapter" else "$minutes min left in chapter")
        }
        if (prefs.showClock) add(currentClock(prefs.clock24h))
        if (prefs.showBattery) add("${batteryPercent()}%")
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(position, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
        Text(
            right.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Whole-book fraction on the same coarse "every spine item is equal length" model the scrubber
 *  and the stored position already use, so the percentage and the scrubber agree. */
private fun bookFraction(state: ReaderUiState): Float {
    if (state.spine.isEmpty()) return 0f
    val withinSpine = if (state.pages.isNotEmpty()) state.currentPageIndex.toFloat() / state.pages.size else 0f
    return ((state.currentSpineIndex + withinSpine) / state.spine.size).coerceIn(0f, 1f)
}

/** Re-reads the wall clock once a minute — no more often, since the footer only shows minutes. */
@Composable
private fun currentClock(use24h: Boolean): String {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = Calendar.getInstance()
        }
    }
    val minute = now.get(Calendar.MINUTE)
    return if (use24h) {
        "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), minute)
    } else {
        val hour = now.get(Calendar.HOUR).let { if (it == 0) 12 else it }
        "%d:%02d %s".format(hour, minute, if (now.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
    }
}

/** Polled rather than broadcast-registered: the footer is glanced at, not watched, and a
 *  `BroadcastReceiver` for `ACTION_BATTERY_CHANGED` fires far more often than once a minute. */
@Composable
private fun batteryPercent(): Int {
    val context = LocalContext.current
    var level by remember { mutableStateOf(readBatteryPercent(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            level = readBatteryPercent(context)
        }
    }
    return level
}

private fun readBatteryPercent(context: android.content.Context): Int =
    (context.getSystemService(android.content.Context.BATTERY_SERVICE) as? BatteryManager)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

/** A draggable whole-book scrubber with a tick per chapter boundary. Dragging previews the target
 *  chapter; releasing calls [onScrub] with the whole-book fraction. */
@Composable
private fun BookScrubber(
    spineCount: Int,
    currentSpineIndex: Int,
    currentPageIndex: Int,
    pageCount: Int,
    onScrub: (Float) -> Unit,
) {
    val settledFraction = remember(spineCount, currentSpineIndex, currentPageIndex, pageCount) {
        if (spineCount <= 0) 0f
        else {
            val withinSpine = if (pageCount > 0) currentPageIndex.toFloat() / pageCount else 0f
            ((currentSpineIndex + withinSpine) / spineCount).coerceIn(0f, 1f)
        }
    }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val fillColor = MaterialTheme.colorScheme.primary

    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(spineCount) {
                if (spineCount <= 0) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragFraction?.let(onScrub); dragFraction = null },
                    onDragCancel = { dragFraction = null }
                )
            }
    ) {
        val shownFraction = dragFraction ?: settledFraction
        Canvas(Modifier.fillMaxSize()) {
            val midY = size.height / 2f
            drawLine(trackColor, androidx.compose.ui.geometry.Offset(0f, midY), androidx.compose.ui.geometry.Offset(size.width, midY), strokeWidth = 3f)
            drawLine(
                fillColor, androidx.compose.ui.geometry.Offset(0f, midY),
                androidx.compose.ui.geometry.Offset(size.width * shownFraction, midY), strokeWidth = 3f
            )
            if (spineCount > 1) {
                for (i in 1 until spineCount) {
                    val x = size.width * (i.toFloat() / spineCount)
                    drawLine(
                        trackColor, androidx.compose.ui.geometry.Offset(x, midY - 4f),
                        androidx.compose.ui.geometry.Offset(x, midY + 4f), strokeWidth = 2f
                    )
                }
            }
            drawCircle(fillColor, radius = 6f, center = androidx.compose.ui.geometry.Offset(size.width * shownFraction, midY))
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(shape = MaterialTheme.shapes.small, color = color, tonalElevation = 2.dp) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
