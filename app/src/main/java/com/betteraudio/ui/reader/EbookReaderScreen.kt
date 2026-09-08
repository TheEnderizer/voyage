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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.data.db.entities.ReaderMarkKind
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
                    state, viewModel, onBack, onListenFromHere, scope,
                    onOpenContents = { pane = ReaderPane.CONTENTS },
                    onOpenSettings = { pane = ReaderPane.SETTINGS }
                )
            }
        }
    }
}

/**
 * The chrome for a reader whose top bar is switched off: the same three destinations as the full
 * `TopAppBar` (out of the book, Contents, Reading settings) as small floating buttons instead of a
 * band across the page.
 *
 * It exists so "Show the top bar" can stay a real preference without being a trapdoor. The buttons
 * carry their own tonal grounds because they sit directly on the page — over body text at any
 * palette, a bare icon is a coin toss for legibility.
 *
 * Reserves no layout space on purpose. It appears only while the chrome is toggled on, so briefly
 * overlaying the first line costs less than re-paginating every book of every reader who has the
 * bar switched off just to hold a gap open for something that is usually not there.
 */
@Composable
private fun BoxScope.ReaderNavCluster(
    hasAid: Boolean,
    aidsRunning: Boolean,
    onToggleAid: () -> Unit,
    onBack: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClusterButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(20.dp))
        }
        ClusterButton(onClick = onOpenContents) {
            Icon(Icons.AutoMirrored.Filled.List, "Contents", Modifier.size(20.dp))
        }
        if (hasAid) {
            ClusterButton(onClick = onToggleAid) {
                Icon(
                    if (aidsRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (aidsRunning) "Stop auto-advance" else "Start auto-advance",
                    Modifier.size(20.dp)
                )
            }
        }
        ClusterButton(onClick = onOpenSettings) {
            Text("Aa", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ClusterButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.size(40.dp)
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The settings that act on the window rather than on the page: keep-awake (#134), immersive
 * fullscreen (#136), orientation lock (#135) and forced brightness (#46/#48).
 *
 * Applied in `DisposableEffect`s keyed on the values themselves and, crucially, **undone on
 * dispose** — every one of them outlives this screen if left set, which would mean leaving the
 * reader locked to portrait or the whole app pinned at 10% brightness. Brightness gets an effect
 * of its own because, unlike the other three, it changes continuously under a finger.
 */
@Composable
private fun ReaderWindowEffects(prefs: ReaderPrefs, onLeave: () -> Unit) {
    val view = LocalView.current

    // Brightness is deliberately NOT part of the effect below, and that is the other half of why
    // the edge-swipe dimmer did nothing. Keyed together, every frame of a brightness drag tore the
    // whole block down and rebuilt it: system bars re-hidden, orientation re-applied, the window
    // flags cycled — sixty times a second, against a value that was itself barely moving. On its
    // own it is one cheap attribute write per frame, which is what a live dimmer needs to be.
    DisposableEffect(prefs.brightness) {
        val window = (view.context as? Activity)?.window
        // -1 (BRIGHTNESS_OVERRIDE_NONE) hands control back to the system setting.
        window?.let { w ->
            w.attributes = w.attributes.apply {
                screenBrightness = if (prefs.brightness < 0f) -1f else prefs.brightness.coerceIn(0.01f, 1f)
            }
        }
        onDispose {
            window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
        }
    }

    DisposableEffect(prefs.keepScreenOn, prefs.fullscreen, prefs.orientation) {
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

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            previousOrientation?.let { activity?.requestedOrientation = it }
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

    // How much room the chrome bars take, MEASURED FROM THE BARS — not computed from a guess.
    //
    // This used to be `WindowInsets.statusBars + 64dp + 20dp`. Both halves were wrong together:
    // the reader runs fullscreen, so `statusBars` reports 0, while `TopAppBar` sizes itself with
    // `TopAppBarDefaults.windowInsets`, which includes the **display cutout**. On a phone with a
    // punch-hole that is ~29dp the page never accounted for, so the first line of every full page
    // was sliced in half behind the bar and the last line behind the footer — and because the
    // paginator was told the page was taller than it really is, those lines were not pushed to the
    // next page, they were simply never seen. The footer had the same problem with the navigation
    // bar, and its own height varies anyway with the clock/battery/progress rows.
    //
    // A bar knows its own height and nothing else reliably does, so each one reports it. The
    // remembered value is only ever replaced by a real measurement, which is what makes toggling
    // the chrome free: hiding a bar removes it from composition, no zero arrives, the reservation
    // stands, and the book does not repaginate every time you tap the middle of the page.
    val chromeDensity = LocalDensity.current
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBarPx by remember { mutableIntStateOf(0) }
    // Until the first measurement lands, stand in with roughly what the bars come out to, so the
    // opening pagination is close and the correction is not a visible reflow.
    val chromeTop = when {
        !prefs.showHeader -> 28.dp   // the floating cluster overlays the page and reserves nothing
        topBarPx > 0 -> with(chromeDensity) { topBarPx.toDp() } + 20.dp
        else -> 84.dp
    }
    val chromeBottom = when {
        !prefs.showFooter -> 20.dp
        bottomBarPx > 0 -> with(chromeDensity) { bottomBarPx.toDp() } + 12.dp
        else -> 108.dp
    }

    fun turnRelative(delta: Int) {
        val target = state.currentPageIndex + delta
        when {
            target < 0 -> viewModel.prevChapter()
            target > state.pages.lastIndex -> viewModel.nextChapter()
            else -> viewModel.onPageChanged(target)
        }
    }

    // The chapter's highlights, in the shape the renderer wants: block start → tint. Recomputed
    // only when the marks or the chapter change, not on every page turn.
    // Grouped by chapter, not filtered to the current one: continuous mode has the neighbouring
    // chapters on screen at the same time, and their highlights have to be painted too.
    val highlightsBySpine = remember(state.marks) {
        state.marks
            .filter { it.kind == ReaderMarkKind.HIGHLIGHT }
            .groupBy { it.spineIndex }
            .mapValues { (_, marks) -> marks.associate { it.renderStart to it.colorArgb } }
    }
    var highlightColor by rememberSaveable { mutableIntStateOf(HIGHLIGHT_TINTS.first()) }
    val bookmarked = state.marks.any {
        it.kind == ReaderMarkKind.BOOKMARK &&
            it.spineIndex == state.currentSpineIndex &&
            it.renderStart == state.pages.getOrNull(state.currentPageIndex)?.blocks?.firstOrNull()?.renderStart
    }

    // The listen↔read landing flash (#see ParagraphGlow). Held in the ViewModel only until it has
    // been played: the animation itself lives with the paragraph's composable, so leaving the
    // marker in place would re-fire it every time that paragraph scrolled back into view.
    val glow = state.flash?.let {
        ParagraphGlow(it.spineIndex, it.renderStart, MaterialTheme.colorScheme.primary)
    }
    LaunchedEffect(state.flash?.generation) {
        val generation = state.flash?.generation ?: return@LaunchedEffect
        delay(1_300) // the pulse is 200ms up + 750ms down; the rest is slack for a scroll into view
        viewModel.clearFlash(generation)
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
        onBrightnessSet = { level -> viewModel.updatePrefs { it.copy(brightness = level) } },
        onViewportMeasured = { w, h, _ -> viewModel.preparePages(measurer, w, h) },
        paginationKey = remember(state.currentSpineIndex, typography) { state.currentSpineIndex to typography },
        aidsRunning = aidsRunning,
        scrollWindow = state.scrollWindow,
        scrollAnchor = state.scrollAnchor,
        onScrolledTo = { spineIndex, renderStart -> viewModel.onScrolledTo(spineIndex, renderStart) },
        currentSpineIndex = state.currentSpineIndex,
        highlightsBySpine = highlightsBySpine,
        onBlockTap = if (state.highlighting) {
            { spineIndex, block -> viewModel.toggleHighlight(spineIndex, block, highlightColor) }
        } else null,
        glow = glow,
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
    //
    // Visible on `chromeVisible` ALONE — deliberately not `&& prefs.showHeader` any more. This bar
    // carries the only route to Contents, to Reading settings, and the back arrow out of the book;
    // "Show the top bar" is a setting *inside* Reading settings, so gating the bar on it meant
    // turning it off removed the only way to turn it back on. The reader was reachable and then
    // permanently unnavigable, with every reading setting stranded behind a door the user had just
    // bricked from the inside. Navigation is not decoration and must not be hideable.
    //
    // The preference is still honoured, in the only way it safely can be: it chooses between the
    // full app bar and a compact floating cluster carrying the same three destinations. Off still
    // means "no bar across my page" — it just no longer means "no way out".
    AnimatedVisibility(
        visible = state.chromeVisible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        if (!prefs.showHeader) {
            ReaderNavCluster(
                hasAid = hasAid,
                aidsRunning = aidsRunning,
                onToggleAid = { aidsRunning = !aidsRunning },
                onBack = onBack,
                onOpenContents = onOpenContents,
                onOpenSettings = onOpenSettings
            )
            return@AnimatedVisibility
        }
        TopAppBar(
            modifier = Modifier.onSizeChanged { topBarPx = it.height },
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
                HapticIconButton(
                    onClick = { viewModel.toggleBookmark() },
                    enabled = state.pages.isNotEmpty()
                ) {
                    Icon(
                        if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        if (bookmarked) "Remove bookmark" else "Bookmark this page",
                        tint = if (bookmarked) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current
                    )
                }
                HapticIconButton(
                    onClick = { viewModel.setHighlighting(!state.highlighting) },
                    enabled = state.pages.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.BorderColor,
                        if (state.highlighting) "Stop highlighting" else "Highlight paragraphs",
                        tint = if (state.highlighting) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current
                    )
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
        // Hidden while highlighting: the colour strip takes this space, and the chapter arrows it
        // carries are the one thing you do not want within reach of a mis-aimed paragraph tap.
        visible = state.chromeVisible && prefs.showFooter && !state.highlighting,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            modifier = Modifier.onSizeChanged { bottomBarPx = it.height }
        ) {
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

    // ── Highlight mode ────────────────────────────────────────────────────
    // A mode with a visible bar, rather than a hidden long-press. Long-press is already spoken for
    // twice over on this screen — by text selection inside the page, and by the unconditional
    // chrome toggle that keeps the reader from locking itself out — and a third meaning for the
    // same press would have made all three unreliable. The bar also answers "which colour am I
    // about to use", which a gesture cannot.
    AnimatedVisibility(
        visible = state.highlighting,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).zIndex(3f)
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Tap a paragraph",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                HIGHLIGHT_TINTS.forEach { tint ->
                    val chosen = tint == highlightColor
                    Box(
                        Modifier
                            .size(if (chosen) 30.dp else 26.dp)
                            .clip(CircleShape)
                            .background(Color(tint))
                            .then(
                                if (chosen) Modifier.border(
                                    2.dp, MaterialTheme.colorScheme.primary, CircleShape
                                ) else Modifier
                            )
                            .clickable { highlightColor = tint }
                    )
                }
                Spacer(Modifier.weight(1f))
                HapticTextButton(onClick = { viewModel.setHighlighting(false) }) { Text("Done") }
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

/**
 * The highlight palette. Four tints, each with a low alpha so the text under it stays the text
 * rather than becoming a coloured block — which also means one set works on a white page and a
 * black one, instead of needing a light palette and a dark one that drift apart.
 */
private val HIGHLIGHT_TINTS = listOf(
    0x66FFE082.toInt(),  // amber
    0x6680CBC4.toInt(),  // teal
    0x669FA8DA.toInt(),  // indigo
    0x66F48FB1.toInt(),  // rose
)
