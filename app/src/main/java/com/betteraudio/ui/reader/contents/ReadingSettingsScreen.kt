package com.betteraudio.ui.reader.contents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betteraudio.data.ebook.render.BlockKind
import com.betteraudio.data.ebook.render.Page
import com.betteraudio.data.ebook.render.RenderBlock
import com.betteraudio.data.settings.ReaderPrefs
import com.betteraudio.ui.haptics.*
import com.betteraudio.ui.reader.EbookReaderViewModel
import com.betteraudio.ui.reader.ReaderUiState
import com.betteraudio.ui.reader.render.*

private const val PREVIEW_HEADING = "Down the Rabbit-Hole"
private const val PREVIEW_TEXT = "They were indeed a queer-looking party that assembled on the bank — " +
    "the birds with draggled feathers, the animals with their fur clinging close to them, and all " +
    "dripping wet, cross, and uncomfortable."

/** The tabs, in the order they appear. Grouped so a change you are hunting for is one tap away
 *  rather than a scroll through four unrelated sections — the whole point of the tabbed layout
 *  now that this screen carries every reading setting rather than seven. */
private enum class SettingsTab(val label: String) {
    TEXT("Text"), LAYOUT("Layout"), COLOUR("Colour"),
    PAGE("Page"), DISPLAY("Display"), AIDS("Aids")
}

/**
 * Reading settings — a real screen (no bottom sheets, no dialogs) with a pinned live preview over
 * a tab row. The preview renders through the actual [ReaderPageView]/[blockTextStyle] path the
 * reader itself uses, so it can't drift from what the page really looks like.
 *
 * Every control writes through the single [EbookReaderViewModel.updatePrefs] entry point, which
 * routes to the global settings or this book's own override depending on the scope selector at the
 * top (inventory #160/#161).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(state: ReaderUiState, viewModel: EbookReaderViewModel, onBack: () -> Unit) {
    val prefs = state.prefs
    var tab by rememberSaveable { mutableStateOf(SettingsTab.TEXT) }
    val set: ((ReaderPrefs) -> ReaderPrefs) -> Unit = { viewModel.updatePrefs(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading settings") },
                navigationIcon = { HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { HapticTextButton(onClick = { viewModel.resetPrefs() }) { Text("Reset") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PreviewPane(prefs)
            ScopeRow(state, viewModel)
            ScrollableTabRow(
                selectedTabIndex = tab.ordinal,
                edgePadding = 12.dp,
            ) {
                SettingsTab.entries.forEach { t ->
                    Tab(selected = t == tab, onClick = { tab = t }, text = { Text(t.label) })
                }
            }
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (tab) {
                    SettingsTab.TEXT -> TextTab(prefs, set)
                    SettingsTab.LAYOUT -> LayoutTab(prefs, set)
                    SettingsTab.COLOUR -> ColourTab(prefs, set)
                    SettingsTab.PAGE -> PageTab(prefs, set)
                    SettingsTab.DISPLAY -> DisplayTab(prefs, set)
                    SettingsTab.AIDS -> AidsTab(prefs, set)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ── Scope (#160/#161) ───────────────────────────────────────────────────────

@Composable
private fun ScopeRow(state: ReaderUiState, viewModel: EbookReaderViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Applies to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Segmented(
            options = listOf(false, true),
            selected = state.prefsArePerBook,
            label = { if (it) "This book" else "All books" },
            onSelect = { viewModel.setPrefsScopePerBook(it) }
        )
        Spacer(Modifier.weight(1f))
        if (state.prefsArePerBook) {
            HapticTextButton(onClick = { viewModel.applyPrefsToAllBooks() }) { Text("Apply to all") }
        }
    }
    HorizontalDivider()
}

// ── Tabs ────────────────────────────────────────────────────────────────────

@Composable
private fun TextTab(p: ReaderPrefs, set: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    val family = ReaderFontFamilyChoice.fromName(p.fontFamily)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ReaderFontFamilyChoice.entries.forEach { f ->
            FontCard(f, f == family, { set { it.copy(fontFamily = f.name) } }, Modifier.weight(1f))
        }
    }
    SettingRow("Size") {
        Stepper("${p.fontSizePct}%", { set { it.copy(fontSizePct = it.fontSizePct - 5) } },
            { set { it.copy(fontSizePct = it.fontSizePct + 5) } }, p.fontSizePct > 50, p.fontSizePct < 300)
    }
    SettingRow("Smallest allowed") {
        Stepper("${p.minFontSizeSp}sp", { set { it.copy(minFontSizeSp = it.minFontSizeSp - 1) } },
            { set { it.copy(minFontSizeSp = it.minFontSizeSp + 1) } }, p.minFontSizeSp > 8, p.minFontSizeSp < 24)
    }
    SettingRow("Weight") {
        Segmented(listOf(300, 400, 500, 700), p.fontWeight, { weightLabel(it) }) { w -> set { it.copy(fontWeight = w) } }
    }
    SliderRow("Line spacing", p.lineHeight, 1.0f..2.4f, "%.2f") { v -> set { it.copy(lineHeight = v) } }
    SliderRow("Word spacing", p.wordSpacingEm, 0f..0.5f, "%.2f em") { v -> set { it.copy(wordSpacingEm = v) } }
    SliderRow("Letter spacing", p.letterSpacingEm, -0.03f..0.2f, "%.3f em") { v -> set { it.copy(letterSpacingEm = v) } }
    SliderRow("Paragraph spacing", p.paragraphSpacingEm, 0f..2.5f, "%.2f em") { v -> set { it.copy(paragraphSpacingEm = v) } }
    SliderRow("First-line indent", p.textIndentEm, 0f..4f, "%.1f em") { v -> set { it.copy(textIndentEm = v) } }
    SwitchRow("Justify text", p.justify) { v -> set { it.copy(justify = v) } }
    SwitchRow("Hyphenate", p.hyphenate) { v -> set { it.copy(hyphenate = v) } }
}

@Composable
private fun LayoutTab(p: ReaderPrefs, set: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    GroupLabel("Margins")
    SettingRow("Left") { DpStepper(p.marginLeftDp) { v -> set { it.copy(marginLeftDp = v) } } }
    SettingRow("Right") { DpStepper(p.marginRightDp) { v -> set { it.copy(marginRightDp = v) } } }
    SettingRow("Top") { DpStepper(p.marginTopDp) { v -> set { it.copy(marginTopDp = v) } } }
    SettingRow("Bottom") { DpStepper(p.marginBottomDp) { v -> set { it.copy(marginBottomDp = v) } } }

    GroupLabel("Columns")
    SettingRow("Count") {
        Segmented(listOf(0, 1, 2), p.columnCount, { if (it == 0) "Auto" else "$it" }) { c -> set { it.copy(columnCount = c) } }
    }
    SliderRow("Gap between columns", p.columnGapPct.toFloat(), 0f..20f, "%.0f%%") { v -> set { it.copy(columnGapPct = v.toInt()) } }
    SettingRow("Max column width") {
        Stepper(
            if (p.maxColumnWidthDp == 0) "Unlimited" else "${p.maxColumnWidthDp}dp",
            { set { it.copy(maxColumnWidthDp = (it.maxColumnWidthDp - 20).coerceAtLeast(0)) } },
            { set { it.copy(maxColumnWidthDp = (it.maxColumnWidthDp + 20).coerceAtMost(900)) } },
            p.maxColumnWidthDp > 0, p.maxColumnWidthDp < 900
        )
    }
}

@Composable
private fun ColourTab(p: ReaderPrefs, set: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    SwitchRow("Follow system light/dark", p.followSystemDark) { v -> set { it.copy(followSystemDark = v) } }
    if (p.followSystemDark) {
        SettingRow("Light theme") {
            ThemeSwatchRow(ReaderTheme.entries.filter { !it.isDark }, p.lightTheme) { t -> set { it.copy(lightTheme = t) } }
        }
        SettingRow("Dark theme") {
            ThemeSwatchRow(ReaderTheme.entries.filter { it.isDark }, p.darkTheme) { t -> set { it.copy(darkTheme = t) } }
        }
    } else {
        GroupLabel("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReaderTheme.entries.forEach { t ->
                ThemeSwatch(t.bg, t.fg, t.name == p.theme) { set { it.copy(theme = t.name) } }
            }
            ThemeSwatch(
                parseHexColor(p.customBg, Color.White), parseHexColor(p.customFg, Color.Black),
                p.theme == "CUSTOM"
            ) { set { it.copy(theme = "CUSTOM") } }
        }
        if (p.theme == "CUSTOM") {
            HexField("Background", p.customBg) { v -> set { it.copy(customBg = v) } }
            HexField("Text", p.customFg) { v -> set { it.copy(customFg = v) } }
        }
    }

    GroupLabel("Brightness")
    SwitchRow("Use the system brightness", p.brightness < 0f) { useSystem ->
        set { it.copy(brightness = if (useSystem) -1f else 0.6f) }
    }
    if (p.brightness >= 0f) {
        SliderRow("Screen brightness", p.brightness, 0.01f..1f, "%.0f%%", displayScale = 100f) { v ->
            set { it.copy(brightness = v) }
        }
    }
    SwitchRow("Swipe the left edge to change it", p.brightnessGesture) { v -> set { it.copy(brightnessGesture = v) } }
    SliderRow("Extra dimming", p.dimBelowFloor, 0f..0.85f, "%.0f%%", displayScale = 100f) { v -> set { it.copy(dimBelowFloor = v) } }
    SwitchRow("Invert images in dark themes", p.invertImagesInDark) { v -> set { it.copy(invertImagesInDark = v) } }
}

@Composable
private fun PageTab(p: ReaderPrefs, set: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    SettingRow("Mode") {
        Segmented(listOf(false, true), p.scrolled, { if (it) "Scroll" else "Pages" }) { s -> set { it.copy(scrolled = s) } }
    }
    if (!p.scrolled) {
        SettingRow("Page turn") {
            Segmented(ReaderPageTurn.entries, ReaderPageTurn.fromName(p.pageTurn), { it.label }) { t -> set { it.copy(pageTurn = t.name) } }
        }
        SwitchRow("Animations", p.animated) { v -> set { it.copy(animated = v) } }

        GroupLabel("Gestures")
        SwitchRow("Tap the sides to turn", p.tapToTurn) { v -> set { it.copy(tapToTurn = v) } }
        if (p.tapToTurn) {
            SliderRow("Tap zone width", p.tapZonePct.toFloat(), 5f..50f, "%.0f%%") { v -> set { it.copy(tapZonePct = v.toInt()) } }
            SwitchRow("Swap which side goes back", p.swapTapSides) { v -> set { it.copy(swapTapSides = v) } }
            SwitchRow("Tap anywhere turns forward", p.fullscreenTapTurns) { v -> set { it.copy(fullscreenTapTurns = v) } }
        }
        SwitchRow("Swipe to turn", p.swipeToTurn) { v -> set { it.copy(swipeToTurn = v) } }
        SwitchRow("Volume keys turn pages", p.volumeKeysTurn) { v -> set { it.copy(volumeKeysTurn = v) } }
    } else {
        Text(
            "In scroll mode the page turn, tap-zone and swipe settings don't apply.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DisplayTab(p: ReaderPrefs, set: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    SwitchRow("Keep the screen awake", p.keepScreenOn) { v -> set { it.copy(keepScreenOn = v) } }
    SwitchRow("Hide the system bars", p.fullscreen) { v -> set { it.copy(fullscreen = v) } }
    SettingRow("Orientation") {
        Segmented(ReaderOrientation.entries, ReaderOrientation.fromName(p.orientation), { it.label }) { o -> set { it.copy(orientation = o.name) } }
    }

    GroupLabel("Bars")
    SwitchRow("Show the top bar", p.showHeader) { v -> set { it.copy(showHeader = v) } }
    SwitchRow("Show the bottom bar", p.showFooter) { v -> set { it.copy(showFooter = v) } }

    GroupLabel("What the bottom bar shows")
    SettingRow("Position") {
        Segmented(ReaderProgressStyle.entries, ReaderProgressStyle.fromName(p.progressStyle), { it.label }) { s -> set { it.copy(progressStyle = s.name) } }
    }
    SwitchRow("Pages left", p.showRemainingPages) { v -> set { it.copy(showRemainingPages = v) } }
    SwitchRow("Time left", p.showRemainingTime) { v -> set { it.copy(showRemainingTime = v) } }
    if (p.showRemainingTime) {
        SettingRow("Your reading speed") {
            Stepper("${p.wordsPerMinute} wpm",
                { set { it.copy(wordsPerMinute = it.wordsPerMinute - 10) } },
                { set { it.copy(wordsPerMinute = it.wordsPerMinute + 10) } },
                p.wordsPerMinute > 80, p.wordsPerMinute < 800)
        }
    }
    SwitchRow("Clock", p.showClock) { v -> set { it.copy(showClock = v) } }
    if (p.showClock) {
        SettingRow("Clock format") {
            Segmented(listOf(true, false), p.clock24h, { if (it) "24h" else "12h" }) { h -> set { it.copy(clock24h = h) } }
        }
    }
    SwitchRow("Battery", p.showBattery) { v -> set { it.copy(showBattery = v) } }
}

@Composable
private fun AidsTab(p: ReaderPrefs, set: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    Text(
        "Start and stop these with the ▶ button in the top bar — it appears once one is set up.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    GroupLabel("Auto page turn")
    SliderRow(
        "Turn every", p.autoPageTurnSeconds.toFloat(), 0f..120f,
        formatter = { if (it < 1f) "Off" else "%.0f s".format(it) }
    ) { v -> set { it.copy(autoPageTurnSeconds = v.toInt()) } }

    GroupLabel("Auto scroll")
    Text(
        "Scroll mode only.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SliderRow("Speed", p.autoScrollSpeed, 0.1f..6f, "%.1f lines/s") { v -> set { it.copy(autoScrollSpeed = v) } }

    GroupLabel("Reading ruler")
    SwitchRow("Show a reading band", p.rulerEnabled) { v -> set { it.copy(rulerEnabled = v) } }
    if (p.rulerEnabled) {
        SettingRow("Lines covered") {
            Stepper("${p.rulerLines}",
                { set { it.copy(rulerLines = it.rulerLines - 1) } },
                { set { it.copy(rulerLines = it.rulerLines + 1) } },
                p.rulerLines > 1, p.rulerLines < 12)
        }
        SliderRow("Strength", p.rulerOpacity, 0.02f..0.6f, "%.0f%%", displayScale = 100f) { v -> set { it.copy(rulerOpacity = v) } }
    }
}

// ── Preview ─────────────────────────────────────────────────────────────────

/** A real page fragment rendered by the real renderer, so what is on this pane is exactly what the
 *  reader will draw — including the paragraph gap, the indent and the palette. */
@Composable
private fun PreviewPane(prefs: ReaderPrefs) {
    val palette = prefs.palette(isSystemInDarkTheme())
    val typography = remember(prefs, palette) { typographyFrom(prefs, palette.fg) }
    val blocks = remember {
        listOf(
            RenderBlock(0, "h2", 1, null, BlockKind.HEADING, headingLevel = 2, renderStart = 0, text = PREVIEW_HEADING, spans = emptyList()),
            RenderBlock(1, "p", 1, null, BlockKind.PARAGRAPH, renderStart = PREVIEW_HEADING.length + 1, text = PREVIEW_TEXT, spans = emptyList()),
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.bg)
            .padding(
                start = prefs.marginLeftDp.dp.coerceAtMost(40.dp),
                end = prefs.marginRightDp.dp.coerceAtMost(40.dp),
                top = 14.dp, bottom = 14.dp
            )
    ) {
        Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = palette.fg.copy(alpha = 0.55f))
        Spacer(Modifier.height(8.dp))
        ReaderPageView(Page(blocks), typography, Modifier.fillMaxWidth())
    }
    HorizontalDivider()
}

// ── Reusable controls ───────────────────────────────────────────────────────

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingRow(label) { Switch(checked = checked, onCheckedChange = onChange) }
}

/** A labelled slider with its current value shown — [displayScale] multiplies the value purely for
 *  the readout (a 0..1 fraction shown as a percentage), leaving the stored value untouched. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String = "%.2f",
    displayScale: Float = 1f,
    formatter: ((Float) -> String)? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatter?.invoke(value) ?: format.format(value * displayScale),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun Stepper(value: String, onDecrease: () -> Unit, onIncrease: () -> Unit, decreaseEnabled: Boolean, increaseEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HapticIconButton(onClick = onDecrease, enabled = decreaseEnabled) { Icon(Icons.Default.Remove, "Less") }
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 8.dp))
        HapticIconButton(onClick = onIncrease, enabled = increaseEnabled) { Icon(Icons.Default.Add, "More") }
    }
}

@Composable
private fun DpStepper(value: Int, onChange: (Int) -> Unit) {
    Stepper("${value}dp", { onChange((value - 4).coerceAtLeast(0)) }, { onChange((value + 4).coerceAtMost(96)) },
        value > 0, value < 96)
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option), style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ThemeSwatchRow(themes: List<ReaderTheme>, selectedName: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.forEach { t -> ThemeSwatch(t.bg, t.fg, t.name == selectedName) { onSelect(t.name) } }
    }
}

@Composable
private fun ThemeSwatch(bg: Color, fg: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Default.Check, null, tint = fg, modifier = Modifier.size(16.dp))
    }
}

/** Hex entry for the custom theme (#43). Commits on every keystroke that parses, so the preview
 *  updates live; an unparseable partial entry simply leaves the last good colour in place. */
@Composable
private fun HexField(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            if (raw.removePrefix("#").length == 6) onChange(if (raw.startsWith("#")) raw else "#$raw")
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun FontCard(choice: ReaderFontFamilyChoice, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Aa", fontFamily = choice.family, fontSize = 20.sp)
        Spacer(Modifier.height(2.dp))
        Text(choice.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun weightLabel(w: Int) = when (w) {
    300 -> "Light"; 400 -> "Regular"; 500 -> "Medium"; else -> "Bold"
}
