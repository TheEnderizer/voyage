package com.betteraudio.ui.reader.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.betteraudio.data.settings.ReaderPrefs

/**
 * The reading-page colour theme (inventory #41/#42) — independent of the app's own Material
 * You/Immersive theme (`ui/theme/AppTheme.kt`). A reader wants a small, well-known set of page
 * colours (paper, sepia, pure black for OLED) regardless of what look the rest of the app is in.
 */
enum class ReaderTheme(val bg: Color, val fg: Color, val label: String, val isDark: Boolean) {
    PAPER(Color(0xFFF7F3EC), Color(0xFF221E1A), "Paper", false),
    SEPIA(Color(0xFFEFE0C4), Color(0xFF3A2E1E), "Sepia", false),
    GREY(Color(0xFFC9C6C1), Color(0xFF201F1D), "Grey", false),
    DARK(Color(0xFF12100E), Color(0xFFEDE6DC), "Dark", true),
    BLACK(Color(0xFF000000), Color(0xFFD8D2C8), "Black", true);

    companion object {
        fun fromName(name: String): ReaderTheme = entries.find { it.name == name } ?: PAPER
    }
}

/** A resolved page palette — either one of the [ReaderTheme] presets or the user's own two
 *  colours (#43). Everything downstream takes this, not a [ReaderTheme], so a custom pair is a
 *  first-class option rather than a special case checked at every call site. */
data class ReaderPalette(val bg: Color, val fg: Color, val isDark: Boolean)

/** Resolves [ReaderPrefs] colour settings against the system dark-mode state (#41). */
fun ReaderPrefs.palette(systemInDarkTheme: Boolean): ReaderPalette {
    if (followSystemDark) {
        val t = ReaderTheme.fromName(if (systemInDarkTheme) darkTheme else lightTheme)
        return ReaderPalette(t.bg, t.fg, t.isDark)
    }
    if (theme == "CUSTOM") {
        val bg = parseHexColor(customBg, Color(0xFFF7F3EC))
        val fg = parseHexColor(customFg, Color(0xFF221E1A))
        return ReaderPalette(bg, fg, bg.luminance() < 0.4f)
    }
    val t = ReaderTheme.fromName(theme)
    return ReaderPalette(t.bg, t.fg, t.isDark)
}

/** Rough perceptual luminance — enough to decide whether a custom background counts as "dark"
 *  (which is what drives image inversion, #53). Not a colour-science-grade conversion. */
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** Accepts `#RRGGBB` and `#AARRGGBB`, with or without the leading `#`. */
fun parseHexColor(hex: String, fallback: Color): Color = runCatching {
    val clean = hex.removePrefix("#")
    when (clean.length) {
        6 -> Color(clean.toLong(16) or 0xFF000000L)
        8 -> Color(clean.toLong(16))
        else -> fallback
    }
}.getOrElse { fallback }

fun Color.toHex(): String = "#%02X%02X%02X".format(
    (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255),
    (blue * 255f).toInt().coerceIn(0, 255),
)

enum class ReaderFontFamilyChoice(val family: FontFamily, val label: String) {
    SERIF(FontFamily.Serif, "Serif"),
    SANS(FontFamily.SansSerif, "Sans"),
    MONO(FontFamily.Monospace, "Mono");

    companion object {
        fun fromName(name: String): ReaderFontFamilyChoice = entries.find { it.name == name } ?: SERIF
    }
}

/** Page-turn animation (inventory #11). `NONE` is the instant cut the `animated` master switch
 *  (#12) also forces, so the two settings collapse to one resolved value at the call site. */
enum class ReaderPageTurn(val label: String) {
    SLIDE("Slide"), PUSH("Push"), FADE("Fade"), NONE("None");

    companion object {
        fun fromName(name: String): ReaderPageTurn = entries.find { it.name == name } ?: SLIDE
    }
}

/** Screen-orientation lock while reading (inventory #135). */
enum class ReaderOrientation(val label: String, val activityInfoValue: Int) {
    SYSTEM("Auto", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT("Portrait", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    LANDSCAPE("Landscape", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    companion object {
        fun fromName(name: String): ReaderOrientation = entries.find { it.name == name } ?: SYSTEM
    }
}

/** How the footer states position (inventory #81). */
enum class ReaderProgressStyle(val label: String) {
    PAGE("Page x of y"), PERCENT("Percent"), BOTH("Both");

    companion object {
        fun fromName(name: String): ReaderProgressStyle = entries.find { it.name == name } ?: PAGE
    }
}

/** Body-text weight (inventory #21). Headings render one step heavier than whatever this is, so
 *  a light body still reads as a hierarchy rather than flattening out. */
fun ReaderPrefs.bodyFontWeight(): FontWeight = FontWeight(fontWeight.coerceIn(100, 900))

fun ReaderPrefs.headingFontWeight(): FontWeight =
    FontWeight((fontWeight + 200).coerceIn(100, 900))

/** The effective body size in sp, after the percentage and the minimum-size floor (#19/#20). */
fun ReaderPrefs.effectiveBodySizeSp(): Float =
    (BASE_BODY_SIZE_SP * fontSizePct / 100f).coerceAtLeast(minFontSizeSp.toFloat())

const val BASE_BODY_SIZE_SP = 18f
