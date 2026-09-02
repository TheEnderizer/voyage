package com.betteraudio.ui.reader.contents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betteraudio.data.ebook.render.Page
import com.betteraudio.data.ebook.render.RenderBlock
import com.betteraudio.data.ebook.render.BlockKind
import com.betteraudio.ui.haptics.*
import com.betteraudio.ui.reader.EbookReaderViewModel
import com.betteraudio.ui.reader.ReaderUiState
import com.betteraudio.ui.reader.render.ReaderFontFamilyChoice
import com.betteraudio.ui.reader.render.ReaderLineSpacing
import com.betteraudio.ui.reader.render.ReaderMargins
import com.betteraudio.ui.reader.render.ReaderPageView
import com.betteraudio.ui.reader.render.ReaderTheme
import com.betteraudio.ui.reader.render.ReaderTypography

private val PREVIEW_TEXT = "They were indeed a queer-looking party that assembled on the bank — " +
    "the birds with draggled feathers, the animals with their fur clinging close to them, and all " +
    "dripping wet, cross, and uncomfortable."

/**
 * Reading settings — a real screen with a pinned, live preview, replacing `FontSizeDialog` (an
 * `AlertDialog`, deleted). The preview renders through the actual [ReaderPageView]/[blockTextStyle]
 * path the reader itself uses, so it can't drift from what the page really looks like.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingSettingsScreen(state: ReaderUiState, viewModel: EbookReaderViewModel, onBack: () -> Unit) {
    val theme = ReaderTheme.fromName(state.readerTheme)
    val fontFamily = ReaderFontFamilyChoice.fromName(state.readerFontFamily)
    val lineSpacing = ReaderLineSpacing.fromName(state.readerLineSpacing)
    val margins = ReaderMargins.fromName(state.readerMargins)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading settings") },
                navigationIcon = { HapticIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    HapticTextButton(onClick = {
                        viewModel.setReaderTheme("PAPER")
                        viewModel.setReaderFontFamily("SERIF")
                        viewModel.setReaderLineSpacing("NORMAL")
                        viewModel.setReaderMargins("NORMAL")
                        viewModel.setReaderJustify(true)
                        viewModel.setReaderHyphenate(true)
                        viewModel.setFontSize(100)
                    }) { Text("Reset") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            PreviewPane(state, theme, fontFamily, lineSpacing)

            SettingsGroup("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderTheme.entries.forEach { t ->
                        ThemeSwatch(theme = t, selected = t == theme, onClick = { viewModel.setReaderTheme(t.name) })
                    }
                }
            }

            SettingsGroup("Text") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReaderFontFamilyChoice.entries.forEach { f ->
                        FontCard(
                            choice = f, selected = f == fontFamily,
                            onClick = { viewModel.setReaderFontFamily(f.name) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                SettingRow(label = "Size") {
                    Stepper(
                        value = "${state.fontSizePct}%",
                        onDecrease = { viewModel.setFontSize(state.fontSizePct - 10) },
                        onIncrease = { viewModel.setFontSize(state.fontSizePct + 10) },
                        decreaseEnabled = state.fontSizePct > 70,
                        increaseEnabled = state.fontSizePct < 200
                    )
                }
            }

            SettingsGroup("Layout") {
                SettingRow(label = "Line spacing") {
                    Segmented(ReaderLineSpacing.entries, lineSpacing, { it.label }) { viewModel.setReaderLineSpacing(it.name) }
                }
                SettingRow(label = "Margins") {
                    Segmented(ReaderMargins.entries, margins, { it.label }) { viewModel.setReaderMargins(it.name) }
                }
                SettingRow(label = "Justify text") {
                    Switch(checked = state.readerJustify, onCheckedChange = { viewModel.setReaderJustify(it) })
                }
                SettingRow(label = "Hyphenate") {
                    Switch(checked = state.readerHyphenate, onCheckedChange = { viewModel.setReaderHyphenate(it) })
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PreviewPane(state: ReaderUiState, theme: ReaderTheme, fontFamily: ReaderFontFamilyChoice, lineSpacing: ReaderLineSpacing) {
    val typography = remember(state.fontSizePct, fontFamily, lineSpacing, state.readerJustify, state.readerHyphenate, theme) {
        ReaderTypography(
            baseSizeSp = 18f * (state.fontSizePct / 100f),
            fontFamily = fontFamily.family,
            lineHeightMultiplier = lineSpacing.multiplier,
            justify = state.readerJustify,
            hyphenate = state.readerHyphenate,
            color = theme.fg
        )
    }
    val previewBlock = remember {
        RenderBlock(0, "p", 1, null, BlockKind.PARAGRAPH, renderStart = 0, text = PREVIEW_TEXT, spans = emptyList())
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.bg)
            .padding(20.dp)
    ) {
        Text(
            "PREVIEW", style = MaterialTheme.typography.labelSmall,
            color = theme.fg.copy(alpha = 0.55f)
        )
        Spacer(Modifier.height(10.dp))
        ReaderPageView(page = Page(listOf(previewBlock)), typography = typography, modifier = Modifier.fillMaxWidth())
    }
    HorizontalDivider()
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
    HorizontalDivider()
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(theme.bg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Default.Check, null, tint = theme.fg, modifier = Modifier.size(16.dp))
    }
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

@Composable
private fun Stepper(value: String, onDecrease: () -> Unit, onIncrease: () -> Unit, decreaseEnabled: Boolean, increaseEnabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        HapticIconButton(onClick = onDecrease, enabled = decreaseEnabled) { Icon(Icons.Default.Remove, "Smaller") }
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 8.dp))
        HapticIconButton(onClick = onIncrease, enabled = increaseEnabled) { Icon(Icons.Default.Add, "Larger") }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = option == selected
            Box(
                Modifier
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
