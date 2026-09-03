package com.betteraudio.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every user-adjustable reading setting, in one serialized document.
 *
 * Deliberately **not** ~45 individual DataStore keys the way the rest of [SettingsStore] works.
 * Three reasons, all of which only apply to this particular surface:
 *
 *  1. Volume. The per-key convention costs a key + a `Flow` + a setter + a `SettingSpec` each; at
 *     this count that is ~180 lines of boilerplate whose only job is to reassemble one struct the
 *     reader always reads as a whole anyway.
 *  2. Per-book scoping (inventory #160). Readest lets a book override the global view settings and
 *     "apply to all"/reset back to them. With one document that is one extra preference key per
 *     book (`reader_prefs_book_<id>`) and a null check; with 45 keys it would be 45 nullable
 *     overrides per book.
 *  3. Forward compatibility. `ignoreUnknownKeys` + defaults mean a settings.json written by a
 *     newer build (or an older one, missing fields added since) still loads instead of throwing.
 *
 * Values are stored in their natural authoring units — `Em` suffixes are multiples of the current
 * font size (so spacing tracks font size instead of drifting as it changes), `Dp` suffixes are raw
 * density-independent pixels, `Pct` are percentages.
 */
@Serializable
data class ReaderPrefs(
    // ── Typography (inventory #19–34) ──────────────────────────────────────
    val fontSizePct: Int = 100,
    val minFontSizeSp: Int = 12,
    /** [ReaderFontFamilyChoice] name. */
    val fontFamily: String = "SERIF",
    /** 300..700, mapped onto `FontWeight`. Applies to body text; headings stay one step bolder. */
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.45f,
    val wordSpacingEm: Float = 0f,
    val letterSpacingEm: Float = 0f,
    val paragraphSpacingEm: Float = 0.65f,
    val textIndentEm: Float = 0f,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,

    // ── Page geometry (inventory #35–41) ───────────────────────────────────
    val marginTopDp: Int = 16,
    val marginBottomDp: Int = 16,
    val marginLeftDp: Int = 20,
    val marginRightDp: Int = 20,
    /** 1 or 2; 0 means "auto" — 2 columns once the page is wide enough for them. */
    val columnCount: Int = 0,
    val columnGapPct: Int = 6,
    /** 0 = unlimited. Caps the measure so a tablet/landscape page doesn't run to 140 characters. */
    val maxColumnWidthDp: Int = 0,

    // ── Colour (inventory #41–54) ──────────────────────────────────────────
    /** [ReaderTheme] name, or "CUSTOM" to use [customBg]/[customFg]. */
    val theme: String = "PAPER",
    val customBg: String = "#F7F3EC",
    val customFg: String = "#221E1A",
    /** When on, [theme] is ignored and [lightTheme]/[darkTheme] follow the system setting. */
    val followSystemDark: Boolean = false,
    val lightTheme: String = "PAPER",
    val darkTheme: String = "DARK",
    /** -1 = leave the system brightness alone; 0..1 = force that window brightness. */
    val brightness: Float = -1f,
    val brightnessGesture: Boolean = true,
    /** Extra black scrim on top, 0..0.8 — goes darker than the OS minimum allows. */
    val dimBelowFloor: Float = 0f,
    val invertImagesInDark: Boolean = true,

    // ── Page behaviour (inventory #2, #11–12, #64–70, #135–136) ────────────
    val scrolled: Boolean = false,
    /** [ReaderPageTurn] name. */
    val pageTurn: String = "SLIDE",
    val animated: Boolean = true,
    val tapToTurn: Boolean = true,
    /** Width of each side tap zone as a % of the page; the rest is the chrome-toggling centre. */
    val tapZonePct: Int = 30,
    val swapTapSides: Boolean = false,
    /** Tap anywhere turns forward — the centre no longer toggles chrome (#66). */
    val fullscreenTapTurns: Boolean = false,
    val swipeToTurn: Boolean = true,
    val volumeKeysTurn: Boolean = false,
    val keepScreenOn: Boolean = true,
    val fullscreen: Boolean = true,
    /** [ReaderOrientation] name. */
    val orientation: String = "SYSTEM",

    // ── Header / footer (inventory #151–156, #81–84) ───────────────────────
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val showClock: Boolean = false,
    val clock24h: Boolean = true,
    val showBattery: Boolean = false,
    /** [ReaderProgressStyle] name. */
    val progressStyle: String = "PAGE",
    val showRemainingTime: Boolean = false,
    val showRemainingPages: Boolean = true,
    /** Reading speed used to turn "words left" into "minutes left" (#82). */
    val wordsPerMinute: Int = 240,

    // ── Reading aids (inventory #126–130) ──────────────────────────────────
    val autoScrollSpeed: Float = 1f,
    /** Seconds between automatic page turns; 0 = off (#129). */
    val autoPageTurnSeconds: Int = 0,
    val rulerEnabled: Boolean = false,
    val rulerLines: Int = 3,
    val rulerOpacity: Float = 0.18f,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Never throws: a corrupt or foreign document falls back to the defaults rather than
         *  taking the reader down on open. */
        fun decode(raw: String?): ReaderPrefs =
            if (raw.isNullOrBlank()) ReaderPrefs()
            else runCatching { json.decodeFromString(serializer(), raw) }.getOrElse { ReaderPrefs() }

        fun encode(prefs: ReaderPrefs): String = json.encodeToString(serializer(), prefs)
    }
}
