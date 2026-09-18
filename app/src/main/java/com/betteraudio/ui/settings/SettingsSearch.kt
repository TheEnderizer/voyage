package com.betteraudio.ui.settings

/**
 * The searchable index of Settings.
 *
 * Collapsing every group to one row made each section navigable, but it did nothing for the other
 * half of the problem: knowing a setting exists and not knowing which of nine sections it lives in.
 * "cover" alone is a colour source, an accent, a mini-player shape, a series toggle and a widget
 * default, spread across three sections. Search is what answers that; browsing never will.
 *
 * This is a hand-written list rather than something derived from the section functions, and that is
 * deliberate: the sections are `LazyListScope` extensions whose groups only exist while composing,
 * so there is nothing to enumerate at rest. The cost is that a new group must be added here too —
 * hence [SettingsIndexTest], which fails if a group id in a section has no entry.
 *
 * @param title what the row is called, matched first and shown as the result.
 * @param section where tapping the result goes.
 * @param group the [CollapsingGroup] id to open on arrival, or null for a whole section.
 * @param keywords extra words that should find this row but do not appear in its title — the words
 *        someone would actually type. "brightness" for backdrop darkening; "vibration" for haptics.
 */
internal data class SettingsIndexEntry(
    val title: String,
    val section: SettingsSection,
    val group: String? = null,
    val keywords: List<String> = emptyList(),
    /**
     * Whether this row exists at all right now. Several groups are drawn only for one theme —
     * "Mini player cover" is Immersive-only, "Colour source" is Material You-only, and "Cover
     * accent" needs an automatic cover pick to disagree with. A result that leads to a section
     * where its group is not rendered is worse than no result: it looks like the setting moved.
     * Mirrors the conditions in [themeSection] exactly; change them together.
     */
    val visible: (com.betteraudio.ui.theme.AppTheme, com.betteraudio.ui.theme.ThemeColorSource) -> Boolean =
        { _, _ -> true }
) {
    /** Where the result lives, shown under its title: "Look and feel › Seek bar". */
    val path: String
        get() = sectionTitle(section) + if (group != null) " › $group" else ""
}

internal fun sectionTitle(s: SettingsSection): String = when (s) {
    SettingsSection.Root -> "Settings"
    SettingsSection.Theme -> "Look and feel"
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

internal val SETTINGS_INDEX: List<SettingsIndexEntry> = listOf(
    // ── Look and feel ────────────────────────────────────────────────────────
    SettingsIndexEntry("App theme", SettingsSection.Theme, "App theme",
        listOf("immersive", "material you", "look", "style")),
    SettingsIndexEntry("Typeface", SettingsSection.Theme, "Typeface",
        listOf("font", "poppins", "text")),
    SettingsIndexEntry("Colour source", SettingsSection.Theme, "Colour source",
        listOf("color", "wallpaper", "cover", "accent", "dynamic"),
        visible = { t, _ -> t == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU }),
    SettingsIndexEntry("Cover accent", SettingsSection.Theme, "Cover accent",
        listOf("colour", "color", "pin", "swatch", "every book"),
        visible = { t, c -> t == com.betteraudio.ui.theme.AppTheme.IMMERSIVE || c == com.betteraudio.ui.theme.ThemeColorSource.COVER }),
    SettingsIndexEntry("Dark mode", SettingsSection.Theme, "Dark mode",
        listOf("light", "night", "amoled", "pure black")),
    SettingsIndexEntry("Landscape player", SettingsSection.Theme, "Landscape player",
        listOf("rotate", "sideways", "orientation", "split", "rails"),
        visible = { t, _ -> t == com.betteraudio.ui.theme.AppTheme.MATERIAL_YOU }),
    SettingsIndexEntry("Haptics", SettingsSection.Theme, "Haptics",
        listOf("vibration", "vibrate", "feedback", "buzz")),
    SettingsIndexEntry("Seek bar", SettingsSection.Theme, "Seek bar",
        listOf("scrubber", "progress", "slider", "aurora", "ember", "tape ribs", "horizon")),
    SettingsIndexEntry("Mini player cover", SettingsSection.Theme, "Mini player cover",
        listOf("pill", "ring", "cap", "shape"),
        visible = { t, _ -> t == com.betteraudio.ui.theme.AppTheme.IMMERSIVE }),
    SettingsIndexEntry("Dynamic pills", SettingsSection.Theme, "Dynamic pills",
        listOf("blur", "glass", "nav bar", "mini player"),
        visible = { t, _ -> t == com.betteraudio.ui.theme.AppTheme.IMMERSIVE }),
    SettingsIndexEntry("Backdrop darkening", SettingsSection.Theme, "Backdrop darkening",
        listOf("dim", "brightness", "background", "blur"),
        visible = { t, _ -> t == com.betteraudio.ui.theme.AppTheme.IMMERSIVE }),
    SettingsIndexEntry("App icon", SettingsSection.Theme, "App icon",
        listOf("launcher", "vee", "voyager", "classic", "shortcut")),

    // ── Playback ─────────────────────────────────────────────────────────────
    SettingsIndexEntry("Skip forward", SettingsSection.Playback, "Skip forward",
        listOf("jump", "seconds", "30")),
    SettingsIndexEntry("Skip back", SettingsSection.Playback, "Skip back",
        listOf("rewind", "jump", "seconds", "15")),
    SettingsIndexEntry("Default speed, boost & EQ", SettingsSection.Playback, "Default speed, boost & EQ",
        listOf("equalizer", "volume", "tempo", "preset")),
    SettingsIndexEntry("Auto-rewind", SettingsSection.Playback, "Auto-rewind",
        listOf("resume", "pause", "back up")),
    SettingsIndexEntry("Skip silence", SettingsSection.Playback, "Skip silence",
        listOf("gaps", "quiet", "trim")),
    SettingsIndexEntry("Sleep timer", SettingsSection.Playback, "Sleep timer",
        listOf("bedtime", "fade", "shake", "schedule")),
    SettingsIndexEntry("Headset button mapping", SettingsSection.Playback, "Headset button mapping",
        listOf("bluetooth", "double press", "triple press", "remote")),
    SettingsIndexEntry("Resume on headphones", SettingsSection.Playback, "Resume on headphones",
        listOf("bluetooth", "auto play", "connect")),

    // ── Whole sections ───────────────────────────────────────────────────────
    SettingsIndexEntry("Audio presets", SettingsSection.Presets,
        keywords = listOf("equalizer", "eq", "speed", "boost", "bundle", "default")),
    SettingsIndexEntry("Audiobook folder", SettingsSection.Library,
        keywords = listOf("library", "path", "storage", "scan")),
    SettingsIndexEntry("Rescan library", SettingsSection.Library,
        keywords = listOf("refresh", "reimport", "books")),
    SettingsIndexEntry("Library structure", SettingsSection.Library,
        keywords = listOf("author", "series", "folders", "import")),
    SettingsIndexEntry("Hidden books", SettingsSection.Library,
        keywords = listOf("ignored", "restore", "missing")),
    SettingsIndexEntry("Import companion pack", SettingsSection.Library,
        keywords = listOf("companion", "pack", "voyagepack", "cast", "map", "friend")),
    SettingsIndexEntry("Companion alerts", SettingsSection.Library,
        keywords = listOf("companion", "badge", "digest", "spoiler", "notify", "threshold")),
    SettingsIndexEntry("Widget", SettingsSection.Widget,
        keywords = listOf("home screen", "designs", "editor")),
    SettingsIndexEntry("AI Synopsis", SettingsSection.AI,
        keywords = listOf("gemini", "api key", "summary", "blurb")),
    SettingsIndexEntry("Backup & restore", SettingsSection.Backup,
        keywords = listOf("export", "import", "save", "restore")),
    SettingsIndexEntry("About", SettingsSection.About,
        keywords = listOf("version", "changelog", "update", "licence", "license")),
    SettingsIndexEntry("Diagnostics", SettingsSection.Diagnostics,
        keywords = listOf("logs", "logcat", "crash", "debug")),
)

/**
 * Rank matches so the obvious answer is first: a title that starts with the query beats one that
 * merely contains it, and both beat a keyword-only hit. Without this, typing "cover" puts "Colour
 * source" above "Cover accent" purely because of declaration order.
 */
internal fun searchSettings(
    query: String,
    appTheme: com.betteraudio.ui.theme.AppTheme,
    colorSource: com.betteraudio.ui.theme.ThemeColorSource
): List<SettingsIndexEntry> {
    val q = query.trim().lowercase()
    if (q.length < 2) return emptyList()
    return SETTINGS_INDEX
        .filter { it.visible(appTheme, colorSource) }
        .mapNotNull { e ->
            val t = e.title.lowercase()
            val rank = when {
                t.startsWith(q) -> 0
                t.contains(q) -> 1
                e.keywords.any { it.startsWith(q) } -> 2
                e.keywords.any { it.contains(q) } -> 3
                else -> return@mapNotNull null
            }
            rank to e
        }
        .sortedBy { it.first }
        .map { it.second }
}
