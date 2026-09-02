package com.betteraudio.ui.reader.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The reading-page colour theme (inventory #41/#42) — independent of the app's own Material
 * You/Immersive theme (`ui/theme/AppTheme.kt`). A reader wants a small, well-known set of page
 * colours (paper, sepia, pure black for OLED) regardless of what look the rest of the app is in.
 */
enum class ReaderTheme(val bg: Color, val fg: Color, val label: String) {
    PAPER(Color(0xFFF7F3EC), Color(0xFF221E1A), "Paper"),
    SEPIA(Color(0xFFEFE0C4), Color(0xFF3A2E1E), "Sepia"),
    GREY(Color(0xFFC9C6C1), Color(0xFF201F1D), "Grey"),
    DARK(Color(0xFF12100E), Color(0xFFEDE6DC), "Dark"),
    BLACK(Color(0xFF000000), Color(0xFFD8D2C8), "Black");

    companion object {
        fun fromName(name: String): ReaderTheme = entries.find { it.name == name } ?: PAPER
    }
}

enum class ReaderFontFamilyChoice(val family: FontFamily, val label: String) {
    SERIF(FontFamily.Serif, "Serif"),
    SANS(FontFamily.SansSerif, "Sans"),
    MONO(FontFamily.Monospace, "Mono");

    companion object {
        fun fromName(name: String): ReaderFontFamilyChoice = entries.find { it.name == name } ?: SERIF
    }
}

enum class ReaderLineSpacing(val multiplier: Float, val label: String) {
    TIGHT(1.25f, "Tight"),
    NORMAL(1.45f, "Normal"),
    LOOSE(1.7f, "Loose");

    companion object {
        fun fromName(name: String): ReaderLineSpacing = entries.find { it.name == name } ?: NORMAL
    }
}

enum class ReaderMargins(val horizontal: Dp, val label: String) {
    NARROW(12.dp, "Narrow"),
    NORMAL(20.dp, "Normal"),
    WIDE(32.dp, "Wide");

    companion object {
        fun fromName(name: String): ReaderMargins = entries.find { it.name == name } ?: NORMAL
    }
}
