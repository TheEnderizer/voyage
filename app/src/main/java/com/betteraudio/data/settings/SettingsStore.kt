package com.betteraudio.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "better_audio_settings")

/** Midpoint of the backdrop-dim slider, and the value every existing install lands on: it
 *  reproduces the veil the Immersive backdrop shipped with before the slider existed, so the
 *  control starts centred on "what this already looked like" rather than at an extreme. */
const val BACKDROP_DIM_DEFAULT = 0.5f

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsFileStore: com.betteraudio.data.diskstore.SettingsFileStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val LIBRARY_FOLDER   = stringPreferencesKey("library_folder")
        val SKIP_FORWARD_MS  = longPreferencesKey("skip_forward_ms")
        val SKIP_BACK_MS     = longPreferencesKey("skip_back_ms")
        val DEFAULT_SPEED    = floatPreferencesKey("default_speed")
        val GEMINI_API_KEY          = stringPreferencesKey("gemini_api_key")
        val DEFAULT_AUDIO_PRESET_ID = longPreferencesKey("default_audio_preset_id")
        val SORT_OPTION             = stringPreferencesKey("sort_option")
        val SORT_DIRECTION          = stringPreferencesKey("sort_direction")
        val LAST_OPEN_BOOK_ID       = longPreferencesKey("last_open_book_id")
        val LAST_PLAYED_BOOK_ID     = longPreferencesKey("last_played_book_id")
        val THEME_BOOK_ID           = longPreferencesKey("theme_book_id")
        val WIDGET_CUSTOM_COLORS    = stringPreferencesKey("widget_custom_colors")
        val AUTO_REWIND_SECONDS          = intPreferencesKey("auto_rewind_seconds")
        val AUTO_REWIND_THRESHOLD_MINUTES = intPreferencesKey("auto_rewind_threshold_minutes")
        val APP_STOPPED_AT               = longPreferencesKey("app_stopped_at")
        val SKIP_SILENCE_MIN_MS          = longPreferencesKey("skip_silence_min_ms")
        val SKIP_SILENCE_THRESHOLD       = intPreferencesKey("skip_silence_threshold")
        // How much of a skipped silence to leave in place, so word onsets aren't clipped.
        val SKIP_SILENCE_PADDING_MS      = longPreferencesKey("skip_silence_padding_ms")
        // "" = not chosen yet (drives the first-run structure prompt); otherwise an
        // ImportStructure name. Blank is treated as AUTO at scan time.
        val IMPORT_STRUCTURE             = stringPreferencesKey("import_structure")
        // Version the user tapped "Skip" on in the launch update prompt; suppresses the prompt
        // only for that exact version, so a newer release still prompts.
        val SKIPPED_UPDATE_VERSION       = stringPreferencesKey("skipped_update_version")
        // Home library grouping view: BOOKS (default) | SERIES | AUTHORS.
        val HOME_VIEW_MODE               = stringPreferencesKey("home_view_mode")
        // In the player, for a book that belongs to a series: show the series cover (true) instead
        // of the book's own cover (false, default).
        val PLAYER_SHOW_SERIES_COVER     = booleanPreferencesKey("player_show_series_cover")
        // Which landscape layout the Material You player uses: RAILS (default) | STAGE. Portrait
        // is unaffected — see ui/material/player/LandscapePlayerStyle.kt.
        val PLAYER_LANDSCAPE_STYLE       = stringPreferencesKey("player_landscape_style")
        // "" = not chosen yet (drives the first-launch theme prompt); otherwise an AppTheme name
        // (MATERIAL_YOU | IMMERSIVE). Blank renders as MATERIAL_YOU behind the prompt.
        val APP_THEME                    = stringPreferencesKey("app_theme")
        // Colour source for the Material You theme: WALLPAPER (dynamic colour, API 31+) | COVER.
        val THEME_COLOR_SOURCE           = stringPreferencesKey("theme_color_source")
        // Absolute path of the cover image the home-screen widget shows when nothing is playing
        // ("" = none → the built-in placeholder). The widget reads the file directly.
        val WIDGET_DEFAULT_COVER_PATH    = stringPreferencesKey("widget_default_cover_path")
        // Separate root folder scanned ONLY for standalone .epub files (no matching audiobook).
        // Mirrors LIBRARY_FOLDER; "" = not set.
        val EBOOK_FOLDER                 = stringPreferencesKey("ebook_folder")
        // One-shot: VoyageApp's phantom-series cleanup (see cleanupPhantomSeries) has run.
        val PHANTOM_SERIES_CLEANUP_DONE  = booleanPreferencesKey("phantom_series_cleanup_done")
        // One-shot: VoyageApp's repair of books left with a seriesName but no seriesId has run
        // (see SeriesRepository.repairOrphanedMembership).
        val SERIES_MEMBERSHIP_REPAIR_DONE = booleanPreferencesKey("series_membership_repair_done")
        // Top-level home section: AUDIO (default) | EBOOKS.
        val HOME_SECTION                 = stringPreferencesKey("home_section")
        // Reader text size, as a percentage (100 = default CSS font-size).
        val READER_FONT_SIZE             = intPreferencesKey("reader_font_size")
        // Reading-page colour theme, independent of the app's own Material You/Immersive theme:
        // PAPER (default) | SEPIA | GREY | DARK | BLACK. See ReaderTheme.
        val READER_THEME                 = stringPreferencesKey("reader_theme")
        // SERIF (default) | SANS | MONO. See ReaderTypography.fontFamily.
        val READER_FONT_FAMILY           = stringPreferencesKey("reader_font_family")
        // TIGHT | NORMAL (default) | LOOSE. See ReaderTypography.lineHeightMultiplier.
        val READER_LINE_SPACING          = stringPreferencesKey("reader_line_spacing")
        // NARROW | NORMAL (default) | WIDE. See ReaderMargins.
        val READER_MARGINS               = stringPreferencesKey("reader_margins")
        val READER_JUSTIFY               = booleanPreferencesKey("reader_justify")
        val READER_HYPHENATE             = booleanPreferencesKey("reader_hyphenate")
        // Material You seed: "default" (system/cover per THEME_COLOR_SOURCE) | "#AARRGGBB" |
        // a built-in preset id | "seedPalette:<base64>" (custom 4-role palette).
        val CUSTOM_THEME_COLOR           = stringPreferencesKey("custom_theme_color")
        // ON | OFF | AUTO (follow system). Applies to both app looks.
        val DARK_MODE                    = stringPreferencesKey("dark_mode")
        // AMOLED-black surfaces when dark + Material You.
        val PURE_BLACK                   = booleanPreferencesKey("pure_black")
        // Immersive only: the mini player / nav pill "glass" samples whichever book cover is
        // currently scrolled underneath it in the grid (updating live as the user scrolls) instead
        // of the fixed now-playing/last-played backdrop.
        val DYNAMIC_PILLS                = booleanPreferencesKey("dynamic_pills")
        // Immersive only: how hard the app-wide blurred-cover backdrop is darkened toward
        // the bottom of the screen. 0 = the blurred cover shows through undimmed,
        // BACKDROP_DIM_DEFAULT = the tuned look, 1 = the lower backdrop goes solid black.
        val BACKDROP_DIM                 = floatPreferencesKey("backdrop_dim")
        // Manual accent picks, one per cover, as a JSON object of path -> "#AARRGGBB".
        // Overrides the automatic swatch choice for that cover. See CoverAccentCodec.
        val COVER_ACCENTS                = stringPreferencesKey("cover_accents")
        // Immersive only: how the mini player draws the cover. CAP (the pill's own left cap,
        // progress around the pill) | RING (a circle inset in the pill, progress around it).
        val MINI_COVER_STYLE             = stringPreferencesKey("mini_cover_style")
        // Immersive only: which of the four chapter-scrubber designs the player draws.
        // EMBER (default) | RIBS | AURORA | HORIZON. See ScrubberStyle.
        val SCRUBBER_STYLE               = stringPreferencesKey("scrubber_style")
        // How much the app is allowed to vibrate: OFF | LIGHT | FULL. See HapticStrength.
        val HAPTIC_STRENGTH              = stringPreferencesKey("haptic_strength")
        // Resolved Material You ColorScheme.primary (ARGB Int), kept in sync from VoyageTheme so
        // themeless widget providers (no Compose context) can render an "app color" background.
        val WIDGET_APP_COLOR             = intPreferencesKey("widget_app_color")
        // When true, widgets (built-in and custom) hide their controls/text/cover elements while
        // nothing is playing, keeping just the background — instead of showing a cold play button
        // that revives the last book.
        val WIDGET_HIDE_WHEN_IDLE        = booleanPreferencesKey("widget_hide_when_idle")
        // ── Backup & restore (data/backup/) ──────────────────────────────────
        val AUTO_BACKUP_ENABLED          = booleanPreferencesKey("auto_backup_enabled")
        // Persisted SAF tree URI (as a string) of the folder auto-backups are written to.
        val AUTO_BACKUP_FOLDER_URI       = stringPreferencesKey("auto_backup_folder_uri")
        val AUTO_BACKUP_LAST_RUN_MS      = longPreferencesKey("auto_backup_last_run_ms")
        // "" = never run; "ok" = last run succeeded; anything else = the last error message.
        val AUTO_BACKUP_LAST_STATUS      = stringPreferencesKey("auto_backup_last_status")
        // Remembers the user's last choice for the "include API key" export checkbox.
        val BACKUP_INCLUDE_API_KEY       = booleanPreferencesKey("backup_include_api_key")
        // ── Sleep timer (playback/PlaybackService — service-owned, see setSleepTimer) ────────
        val SLEEP_FADE_SECONDS           = intPreferencesKey("sleep_fade_seconds")
        val SLEEP_SHAKE_ENABLED          = booleanPreferencesKey("sleep_shake_enabled")
        val SLEEP_SHAKE_RESET_MINUTES    = intPreferencesKey("sleep_shake_reset_minutes")
        val SLEEP_SCHEDULE_ENABLED       = booleanPreferencesKey("sleep_schedule_enabled")
        // Minutes since midnight (local time), 0-1439. START may be > END to mean "wraps past
        // midnight" (e.g. 22:00 -> 06:00).
        val SLEEP_SCHEDULE_START_MINUTES = intPreferencesKey("sleep_schedule_start_minutes")
        val SLEEP_SCHEDULE_END_MINUTES   = intPreferencesKey("sleep_schedule_end_minutes")
        val SLEEP_SCHEDULE_DEFAULT_MINUTES = intPreferencesKey("sleep_schedule_default_minutes")
        // The slider/custom-entry value the player's sleep icon starts a timer with on tap.
        val SLEEP_TIMER_MINUTES          = intPreferencesKey("sleep_timer_minutes")
        // ── Audio balance / mono (playback/ChannelMixProcessor) — global, not per-book ────────
        val AUDIO_BALANCE = floatPreferencesKey("audio_balance")   // -1f (left) .. 1f (right)
        val MONO_AUDIO     = booleanPreferencesKey("mono_audio")
        // ── Headset multi-press mapping (PlaybackService.onMediaButtonEvent) ───────────────────
        // Off by default — keeps today's zero-latency single-press behavior; enabling accepts a
        // short press-counting delay. Action values: "play_pause" | "skip_forward" | "skip_back" |
        // "next_chapter" | "prev_chapter" | "bookmark" | "none".
        val HEADSET_MULTI_PRESS_ENABLED = booleanPreferencesKey("headset_multi_press_enabled")
        val HEADSET_DOUBLE_PRESS_ACTION = stringPreferencesKey("headset_double_press_action")
        val HEADSET_TRIPLE_PRESS_ACTION = stringPreferencesKey("headset_triple_press_action")
        // ── Bluetooth/headphone auto-resume (PlaybackService.AudioDeviceCallback) ──────────────
        val BT_AUTO_RESUME_ENABLED        = booleanPreferencesKey("bt_auto_resume_enabled")
        val BT_AUTO_RESUME_WINDOW_MINUTES = intPreferencesKey("bt_auto_resume_window_minutes")
        // Off by default: persisting every log line costs a stat+open/write/close otherwise
        // (see AppLog) — Logcat mirroring is unconditional, so nothing is lost live, only the
        // persisted copy is skipped unless the user opts in from Settings → Diagnostics.
        // Kept as a real boolean forever (never a tri-state) so a settings.json written by an
        // older build can still apply it without a type mismatch — see LOG_LEVEL below and
        // SettingsSpecs' exclusion list. Its meaning is simply "LOG_LEVEL != OFF"; setLogLevel
        // keeps both keys in one atomic write so they can never disagree.
        val ENABLE_FILE_LOGGING = booleanPreferencesKey("enable_file_logging")
        // "OFF" | "ON" | "VERBOSE". Deliberately NOT mirrored to .voyage/settings.json (see
        // SettingsSpecs) — a diagnostic toggle shouldn't silently re-enable itself on a reinstall
        // the user expected to be clean. Also cached synchronously in LogPrefsCache so AppLog.init()
        // can read it before DataStore's Flow machinery is up (see VoyageApp.onCreate).
        val LOG_LEVEL = stringPreferencesKey("log_level")
        // On-disk budget for the log directory, in MB (0.5–20 range enforced by the setter).
        val LOG_BUDGET_MB = floatPreferencesKey("log_budget_mb")
        // ── Disk-first storage redesign (data/diskstore/) ───────────────────────────────────────
        // Versioned (not boolean) so a later schema addition can force a re-export by bumping
        // DiskExportMigration.TARGET_VERSION. 0 = never run.
        val DISK_EXPORT_VERSION = intPreferencesKey("disk_export_version")
        // "" | "NEEDS_FOLDER" | "FRESH" | "RESTORED" — only ever the two terminal values
        // (FRESH/RESTORED) are actually persisted; see LibraryBootstrapper.
        val SETUP_STATE = stringPreferencesKey("setup_state")
        // Mtime (ms) of .voyage/library.json the last time reconcileLibraryFromDisk applied it —
        // lets a rescan skip re-parsing library.json when nothing has changed since.
        val LIBRARY_JSON_APPLIED_AT = longPreferencesKey("library_json_applied_at")
    }

    companion object {
        private const val MIRROR_DEBOUNCE_MS = 500L
        const val DEFAULT_SKIP_FORWARD_MS = 30_000L
        const val DEFAULT_SKIP_BACK_MS    = 15_000L
        const val DEFAULT_SPEED           = 1.0f
        const val DEFAULT_AUTO_REWIND_SECONDS           = 10
        const val DEFAULT_AUTO_REWIND_THRESHOLD_MINUTES = 30
        // Skip-silence engine config. Threshold is the PCM level below which audio counts as
        // silence (higher = more aggressive). Padding is how much of each skipped silence is left
        // in place so word onsets aren't clipped. All three apply live (see
        // playback/LiveSilenceSkippingProcessor).
        const val DEFAULT_SKIP_SILENCE_MIN_MS     = 1_000L
        const val DEFAULT_SKIP_SILENCE_THRESHOLD  = 1024
        const val DEFAULT_SKIP_SILENCE_PADDING_MS = 300L
        const val DEFAULT_WIDGET_APP_COLOR = 0xFFFFA552.toInt()
        const val DEFAULT_SLEEP_FADE_SECONDS = 10
        const val DEFAULT_SLEEP_SHAKE_ENABLED = true
        const val DEFAULT_SLEEP_SHAKE_RESET_MINUTES = 10
        const val DEFAULT_SLEEP_SCHEDULE_START_MINUTES = 22 * 60   // 22:00
        const val DEFAULT_SLEEP_SCHEDULE_END_MINUTES = 6 * 60      // 06:00
        const val DEFAULT_SLEEP_SCHEDULE_DEFAULT_MINUTES = 30
        const val DEFAULT_SLEEP_TIMER_MINUTES = 30
        const val DEFAULT_HEADSET_DOUBLE_PRESS_ACTION = "skip_forward"
        const val DEFAULT_HEADSET_TRIPLE_PRESS_ACTION = "skip_back"
        const val DEFAULT_BT_AUTO_RESUME_WINDOW_MINUTES = 15
        const val DEFAULT_LOG_LEVEL = "OFF"
        const val DEFAULT_LOG_BUDGET_MB = 2f
        const val MIN_LOG_BUDGET_MB = 0.5f
        const val MAX_LOG_BUDGET_MB = 20f
        /** Change on ordinary playback (every book open/close, every app background) rather than
         *  on a deliberate settings edit — see [watchAndLogChanges]. */
        val HIGH_FREQUENCY_SETTING_KEYS = setOf("last_open_book_id", "last_played_book_id", "theme_book_id", "app_stopped_at")
    }

    // Single shared reference to the underlying DataStore flow — every setting below derives from
    // this one collection instead of each independently calling context.dataStore.data, and each
    // adds its own distinctUntilChanged(). DataStore's data Flow re-emits the WHOLE Preferences
    // snapshot on a write to ANY key; without distinctUntilChanged, changing one setting (e.g. skip
    // duration) re-emitted every other setting's Flow too, recomposing every composable that reads
    // ANY setting — not just the one that actually changed.
    private val prefsData: Flow<androidx.datastore.preferences.core.Preferences> = context.dataStore.data

    val libraryFolder: Flow<String>  = prefsData.map { it[Keys.LIBRARY_FOLDER]  ?: "" }.distinctUntilChanged()
    val skipForwardMs: Flow<Long>    = prefsData.map { it[Keys.SKIP_FORWARD_MS] ?: DEFAULT_SKIP_FORWARD_MS }.distinctUntilChanged()
    val skipBackMs: Flow<Long>       = prefsData.map { it[Keys.SKIP_BACK_MS]    ?: DEFAULT_SKIP_BACK_MS }.distinctUntilChanged()
    val defaultSpeed: Flow<Float>    = prefsData.map { it[Keys.DEFAULT_SPEED]   ?: DEFAULT_SPEED }.distinctUntilChanged()
    val geminiApiKey: Flow<String>          = prefsData.map { it[Keys.GEMINI_API_KEY]          ?: "" }.distinctUntilChanged()
    val defaultAudioPresetId: Flow<Long>    = prefsData.map { it[Keys.DEFAULT_AUDIO_PRESET_ID] ?: -1L }.distinctUntilChanged()
    // Defaults mirror ui/home/SortFilter's — a library opens most-recently-listened first.
    val sortOption: Flow<String>            = prefsData.map { it[Keys.SORT_OPTION]    ?: "LAST_PLAYED" }.distinctUntilChanged()
    val sortDirection: Flow<String>         = prefsData.map { it[Keys.SORT_DIRECTION] ?: "DESC" }.distinctUntilChanged()
    val lastOpenBookId: Flow<Long>          = prefsData.map { it[Keys.LAST_OPEN_BOOK_ID] ?: -1L }.distinctUntilChanged()
    val lastPlayedBookId: Flow<Long>        = prefsData.map { it[Keys.LAST_PLAYED_BOOK_ID] ?: -1L }.distinctUntilChanged()
    /** The book whose cover the app-wide Material You theme should track — set alongside
     *  [setLastPlayedBookId] whenever a book actually opens/plays, but (unlike that id) never reset
     *  to -1 on close, so closing a book keeps its theme instead of reverting to the last one
     *  before it. Only a genuinely different book opening changes it. */
    val themeBookId: Flow<Long>              = prefsData.map { it[Keys.THEME_BOOK_ID] ?: -1L }.distinctUntilChanged()
    /** Custom colors the user has picked in the widget editor, newest first — so a color set once
     *  (on any element) is immediately offered as a swatch everywhere else via [ColorPickerRow]. */
    val widgetCustomColors: Flow<List<Long>> = prefsData.map { prefs ->
        prefs[Keys.WIDGET_CUSTOM_COLORS]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
    }.distinctUntilChanged()
    val autoRewindSeconds: Flow<Int>          = prefsData.map { it[Keys.AUTO_REWIND_SECONDS] ?: DEFAULT_AUTO_REWIND_SECONDS }.distinctUntilChanged()
    val autoRewindThresholdMinutes: Flow<Int> = prefsData.map { it[Keys.AUTO_REWIND_THRESHOLD_MINUTES] ?: DEFAULT_AUTO_REWIND_THRESHOLD_MINUTES }.distinctUntilChanged()
    val appStoppedAt: Flow<Long>              = prefsData.map { it[Keys.APP_STOPPED_AT] ?: 0L }.distinctUntilChanged()
    val skipSilenceMinMs: Flow<Long>          = prefsData.map { it[Keys.SKIP_SILENCE_MIN_MS] ?: DEFAULT_SKIP_SILENCE_MIN_MS }.distinctUntilChanged()
    val skipSilenceThreshold: Flow<Int>       = prefsData.map { it[Keys.SKIP_SILENCE_THRESHOLD] ?: DEFAULT_SKIP_SILENCE_THRESHOLD }.distinctUntilChanged()
    val skipSilencePaddingMs: Flow<Long>      = prefsData.map { it[Keys.SKIP_SILENCE_PADDING_MS] ?: DEFAULT_SKIP_SILENCE_PADDING_MS }.distinctUntilChanged()
    val importStructure: Flow<String>         = prefsData.map { it[Keys.IMPORT_STRUCTURE] ?: "" }.distinctUntilChanged()
    val skippedUpdateVersion: Flow<String>    = prefsData.map { it[Keys.SKIPPED_UPDATE_VERSION] ?: "" }.distinctUntilChanged()
    val playerShowSeriesCover: Flow<Boolean>  = prefsData.map { it[Keys.PLAYER_SHOW_SERIES_COVER] ?: false }.distinctUntilChanged()
    val playerLandscapeStyle: Flow<String>    = prefsData.map { it[Keys.PLAYER_LANDSCAPE_STYLE] ?: "RAILS" }.distinctUntilChanged()
    val homeViewMode: Flow<String>            = prefsData.map { it[Keys.HOME_VIEW_MODE] ?: "BOOKS" }.distinctUntilChanged()
    val appTheme: Flow<String>                = prefsData.map { it[Keys.APP_THEME] ?: "" }.distinctUntilChanged()
    val themeColorSource: Flow<String>        = prefsData.map { it[Keys.THEME_COLOR_SOURCE] ?: "WALLPAPER" }.distinctUntilChanged()
    val widgetDefaultCoverPath: Flow<String>  = prefsData.map { it[Keys.WIDGET_DEFAULT_COVER_PATH] ?: "" }.distinctUntilChanged()
    val ebookFolder: Flow<String>              = prefsData.map { it[Keys.EBOOK_FOLDER] ?: "" }.distinctUntilChanged()
    val phantomSeriesCleanupDone: Flow<Boolean> = prefsData.map { it[Keys.PHANTOM_SERIES_CLEANUP_DONE] ?: false }.distinctUntilChanged()
    val seriesMembershipRepairDone: Flow<Boolean> = prefsData.map { it[Keys.SERIES_MEMBERSHIP_REPAIR_DONE] ?: false }.distinctUntilChanged()
    val readerFontSize: Flow<Int>              = prefsData.map { it[Keys.READER_FONT_SIZE] ?: 100 }.distinctUntilChanged()
    val readerTheme: Flow<String>               = prefsData.map { it[Keys.READER_THEME] ?: "PAPER" }.distinctUntilChanged()
    val readerFontFamily: Flow<String>          = prefsData.map { it[Keys.READER_FONT_FAMILY] ?: "SERIF" }.distinctUntilChanged()
    val readerLineSpacing: Flow<String>         = prefsData.map { it[Keys.READER_LINE_SPACING] ?: "NORMAL" }.distinctUntilChanged()
    val readerMargins: Flow<String>             = prefsData.map { it[Keys.READER_MARGINS] ?: "NORMAL" }.distinctUntilChanged()
    val readerJustify: Flow<Boolean>            = prefsData.map { it[Keys.READER_JUSTIFY] ?: true }.distinctUntilChanged()
    val readerHyphenate: Flow<Boolean>          = prefsData.map { it[Keys.READER_HYPHENATE] ?: true }.distinctUntilChanged()
    val homeSection: Flow<String>              = prefsData.map { it[Keys.HOME_SECTION] ?: "AUDIO" }.distinctUntilChanged()
    val customThemeColor: Flow<String>         = prefsData.map { it[Keys.CUSTOM_THEME_COLOR] ?: "default" }.distinctUntilChanged()
    val darkMode: Flow<String>                 = prefsData.map { it[Keys.DARK_MODE] ?: "AUTO" }.distinctUntilChanged()
    val pureBlack: Flow<Boolean>               = prefsData.map { it[Keys.PURE_BLACK] ?: false }.distinctUntilChanged()
    // Defaults ON: the pills' real backdrop blur is the Immersive theme's signature surface, and
    // it should be what a user sees without hunting through Settings. The toggle exists to turn it
    // OFF (older/slower devices, or a preference for the static cover smudge).
    val dynamicPills: Flow<Boolean>            = prefsData.map { it[Keys.DYNAMIC_PILLS] ?: true }.distinctUntilChanged()
    val backdropDim: Flow<Float>               = prefsData.map { it[Keys.BACKDROP_DIM] ?: BACKDROP_DIM_DEFAULT }.distinctUntilChanged()
    /** Raw JSON — decode with [CoverAccentCodec.decode]. "" = nothing pinned. */
    val coverAccents: Flow<String>             = prefsData.map { it[Keys.COVER_ACCENTS] ?: "" }.distinctUntilChanged()
    val miniCoverStyle: Flow<String>           = prefsData.map { it[Keys.MINI_COVER_STYLE] ?: "CAP" }.distinctUntilChanged()
    val scrubberStyle: Flow<String>            = prefsData.map { it[Keys.SCRUBBER_STYLE] ?: "EMBER" }.distinctUntilChanged()
    val hapticStrength: Flow<String>           = prefsData.map { it[Keys.HAPTIC_STRENGTH] ?: "FULL" }.distinctUntilChanged()
    val widgetAppColor: Flow<Int>              = prefsData.map { it[Keys.WIDGET_APP_COLOR] ?: DEFAULT_WIDGET_APP_COLOR }.distinctUntilChanged()
    val widgetHideWhenIdle: Flow<Boolean>      = prefsData.map { it[Keys.WIDGET_HIDE_WHEN_IDLE] ?: false }.distinctUntilChanged()
    val autoBackupEnabled: Flow<Boolean>       = prefsData.map { it[Keys.AUTO_BACKUP_ENABLED] ?: false }.distinctUntilChanged()
    val autoBackupFolderUri: Flow<String>      = prefsData.map { it[Keys.AUTO_BACKUP_FOLDER_URI] ?: "" }.distinctUntilChanged()
    val autoBackupLastRunMs: Flow<Long>        = prefsData.map { it[Keys.AUTO_BACKUP_LAST_RUN_MS] ?: 0L }.distinctUntilChanged()
    val autoBackupLastStatus: Flow<String>     = prefsData.map { it[Keys.AUTO_BACKUP_LAST_STATUS] ?: "" }.distinctUntilChanged()
    val backupIncludeApiKey: Flow<Boolean>     = prefsData.map { it[Keys.BACKUP_INCLUDE_API_KEY] ?: false }.distinctUntilChanged()
    val sleepFadeSeconds: Flow<Int>            = prefsData.map { it[Keys.SLEEP_FADE_SECONDS] ?: DEFAULT_SLEEP_FADE_SECONDS }.distinctUntilChanged()
    val sleepShakeEnabled: Flow<Boolean>       = prefsData.map { it[Keys.SLEEP_SHAKE_ENABLED] ?: DEFAULT_SLEEP_SHAKE_ENABLED }.distinctUntilChanged()
    val sleepShakeResetMinutes: Flow<Int>      = prefsData.map { it[Keys.SLEEP_SHAKE_RESET_MINUTES] ?: DEFAULT_SLEEP_SHAKE_RESET_MINUTES }.distinctUntilChanged()
    val sleepScheduleEnabled: Flow<Boolean>    = prefsData.map { it[Keys.SLEEP_SCHEDULE_ENABLED] ?: false }.distinctUntilChanged()
    val sleepScheduleStartMinutes: Flow<Int>   = prefsData.map { it[Keys.SLEEP_SCHEDULE_START_MINUTES] ?: DEFAULT_SLEEP_SCHEDULE_START_MINUTES }.distinctUntilChanged()
    val sleepScheduleEndMinutes: Flow<Int>     = prefsData.map { it[Keys.SLEEP_SCHEDULE_END_MINUTES] ?: DEFAULT_SLEEP_SCHEDULE_END_MINUTES }.distinctUntilChanged()
    val sleepScheduleDefaultMinutes: Flow<Int> = prefsData.map { it[Keys.SLEEP_SCHEDULE_DEFAULT_MINUTES] ?: DEFAULT_SLEEP_SCHEDULE_DEFAULT_MINUTES }.distinctUntilChanged()
    val sleepTimerMinutes: Flow<Int>           = prefsData.map { it[Keys.SLEEP_TIMER_MINUTES] ?: DEFAULT_SLEEP_TIMER_MINUTES }.distinctUntilChanged()
    val audioBalance: Flow<Float>              = prefsData.map { it[Keys.AUDIO_BALANCE] ?: 0f }.distinctUntilChanged()
    val monoAudio: Flow<Boolean>               = prefsData.map { it[Keys.MONO_AUDIO] ?: false }.distinctUntilChanged()
    val headsetMultiPressEnabled: Flow<Boolean> = prefsData.map { it[Keys.HEADSET_MULTI_PRESS_ENABLED] ?: false }.distinctUntilChanged()
    val headsetDoublePressAction: Flow<String> = prefsData.map { it[Keys.HEADSET_DOUBLE_PRESS_ACTION] ?: DEFAULT_HEADSET_DOUBLE_PRESS_ACTION }.distinctUntilChanged()
    val headsetTriplePressAction: Flow<String> = prefsData.map { it[Keys.HEADSET_TRIPLE_PRESS_ACTION] ?: DEFAULT_HEADSET_TRIPLE_PRESS_ACTION }.distinctUntilChanged()
    val btAutoResumeEnabled: Flow<Boolean>     = prefsData.map { it[Keys.BT_AUTO_RESUME_ENABLED] ?: false }.distinctUntilChanged()
    val btAutoResumeWindowMinutes: Flow<Int>   = prefsData.map { it[Keys.BT_AUTO_RESUME_WINDOW_MINUTES] ?: DEFAULT_BT_AUTO_RESUME_WINDOW_MINUTES }.distinctUntilChanged()
    /** "OFF" | "ON" | "VERBOSE" — see [setLogLevel]. Collected once in VoyageApp.onCreate to push
     *  the level into AppLog and backfill [com.betteraudio.util.log.LogPrefsCache]. */
    val logLevel: Flow<String>                 = prefsData.map { it[Keys.LOG_LEVEL] ?: DEFAULT_LOG_LEVEL }.distinctUntilChanged()
    val logBudgetMb: Flow<Float>                = prefsData.map { it[Keys.LOG_BUDGET_MB] ?: DEFAULT_LOG_BUDGET_MB }.distinctUntilChanged()
    val diskExportVersion: Flow<Int>           = prefsData.map { it[Keys.DISK_EXPORT_VERSION] ?: 0 }.distinctUntilChanged()
    val setupState: Flow<String>               = prefsData.map { it[Keys.SETUP_STATE] ?: "" }.distinctUntilChanged()
    val libraryJsonAppliedAt: Flow<Long>       = prefsData.map { it[Keys.LIBRARY_JSON_APPLIED_AT] ?: 0L }.distinctUntilChanged()

    @Volatile var currentSkipForwardMs               = DEFAULT_SKIP_FORWARD_MS;               private set
    @Volatile var currentSkipBackMs                  = DEFAULT_SKIP_BACK_MS;                  private set
    @Volatile var currentDefaultSpeed                = DEFAULT_SPEED;                          private set
    @Volatile var currentLibraryFolder               = "";                                     private set
    @Volatile var currentGeminiApiKey                = "";                                     private set
    @Volatile var currentDefaultAudioPresetId        = -1L;                                    private set
    @Volatile var currentAutoRewindSeconds           = DEFAULT_AUTO_REWIND_SECONDS;            private set
    @Volatile var currentAutoRewindThresholdMinutes  = DEFAULT_AUTO_REWIND_THRESHOLD_MINUTES;  private set
    @Volatile var currentAppStoppedAt               = 0L;                                     private set
    @Volatile var currentSkipSilenceMinMs           = DEFAULT_SKIP_SILENCE_MIN_MS;            private set
    @Volatile var currentSkipSilenceThreshold       = DEFAULT_SKIP_SILENCE_THRESHOLD;         private set
    @Volatile var currentSkipSilencePaddingMs       = DEFAULT_SKIP_SILENCE_PADDING_MS;        private set
    @Volatile var currentWidgetAppColor             = DEFAULT_WIDGET_APP_COLOR;               private set
    @Volatile var currentWidgetHideWhenIdle         = false;                                   private set
    @Volatile var currentSleepFadeSeconds           = DEFAULT_SLEEP_FADE_SECONDS;             private set
    @Volatile var currentSleepShakeEnabled          = DEFAULT_SLEEP_SHAKE_ENABLED;             private set
    @Volatile var currentSleepShakeResetMinutes     = DEFAULT_SLEEP_SHAKE_RESET_MINUTES;       private set
    @Volatile var currentSleepScheduleEnabled       = false;                                   private set
    @Volatile var currentSleepScheduleStartMinutes  = DEFAULT_SLEEP_SCHEDULE_START_MINUTES;    private set
    @Volatile var currentSleepScheduleEndMinutes    = DEFAULT_SLEEP_SCHEDULE_END_MINUTES;      private set
    @Volatile var currentSleepScheduleDefaultMinutes = DEFAULT_SLEEP_SCHEDULE_DEFAULT_MINUTES; private set
    @Volatile var currentAudioBalance               = 0f;                                      private set
    @Volatile var currentMonoAudio                  = false;                                    private set
    @Volatile var currentHeadsetMultiPressEnabled   = false;                                    private set
    @Volatile var currentHeadsetDoublePressAction   = DEFAULT_HEADSET_DOUBLE_PRESS_ACTION;      private set
    @Volatile var currentHeadsetTriplePressAction   = DEFAULT_HEADSET_TRIPLE_PRESS_ACTION;      private set
    @Volatile var currentBtAutoResumeEnabled        = false;                                    private set
    @Volatile var currentBtAutoResumeWindowMinutes  = DEFAULT_BT_AUTO_RESUME_WINDOW_MINUTES;    private set
    // The player's last-chosen sleep-timer duration — read synchronously by PlaybackService's
    // ACTION_SLEEP_TIMER_TOGGLE handler (onStartCommand can't suspend-read the Flow) so a widget
    // tap arms the SAME duration the player would, instead of a separate hardcoded fallback.
    @Volatile var currentSleepTimerMinutes          = DEFAULT_SLEEP_TIMER_MINUTES;              private set
    // Read synchronously by MainActivity.onCreate so the first composed frame renders in the
    // right theme and restores the right book without a runBlocking DataStore read.
    @Volatile var currentAppTheme                   = "";                                       private set
    @Volatile var currentThemeColorSource           = "WALLPAPER";                              private set
    @Volatile var currentCustomThemeColor           = "default";                                private set
    @Volatile var currentCoverAccents               = "";                                       private set
    @Volatile var currentMiniCoverStyle             = "CAP";                                    private set
    @Volatile var currentScrubberStyle              = "EMBER";                                  private set
    @Volatile var currentHapticStrength             = "FULL";                                   private set
    @Volatile var currentDarkMode                   = "AUTO";                                    private set
    @Volatile var currentPureBlack                  = false;                                    private set
    @Volatile var currentLastOpenBookId             = -1L;                                       private set
    @Volatile var currentLastPlayedBookId           = -1L;                                       private set
    // Read synchronously by MainActivity.onCreate, before setContent, to decide whether the
    // theme/import-structure onboarding dialogs may show. Defaults to "" (UNKNOWN in
    // LibraryBootstrapper.SetupState), NOT "FRESH" — a dialog must never flash before this
    // store's own async collector has actually landed a real value.
    @Volatile var currentSetupState                 = "";                                        private set

    init {
        scope.launch { skipForwardMs.collect             { currentSkipForwardMs              = it } }
        scope.launch { skipBackMs.collect                { currentSkipBackMs                 = it } }
        scope.launch { defaultSpeed.collect              { currentDefaultSpeed               = it } }
        scope.launch { libraryFolder.collect             { currentLibraryFolder              = it } }
        scope.launch { geminiApiKey.collect              { currentGeminiApiKey               = it } }
        scope.launch { defaultAudioPresetId.collect      { currentDefaultAudioPresetId       = it } }
        scope.launch { autoRewindSeconds.collect         { currentAutoRewindSeconds          = it } }
        scope.launch { autoRewindThresholdMinutes.collect{ currentAutoRewindThresholdMinutes = it } }
        scope.launch { appStoppedAt.collect              { currentAppStoppedAt              = it } }
        scope.launch { skipSilenceMinMs.collect          { currentSkipSilenceMinMs          = it } }
        scope.launch { skipSilenceThreshold.collect      { currentSkipSilenceThreshold      = it } }
        scope.launch { skipSilencePaddingMs.collect      { currentSkipSilencePaddingMs      = it } }
        scope.launch { widgetAppColor.collect            { currentWidgetAppColor            = it } }
        scope.launch { widgetHideWhenIdle.collect        { currentWidgetHideWhenIdle        = it } }
        scope.launch { sleepFadeSeconds.collect              { currentSleepFadeSeconds              = it } }
        scope.launch { sleepShakeEnabled.collect             { currentSleepShakeEnabled             = it } }
        scope.launch { sleepShakeResetMinutes.collect        { currentSleepShakeResetMinutes        = it } }
        scope.launch { sleepScheduleEnabled.collect          { currentSleepScheduleEnabled          = it } }
        scope.launch { sleepScheduleStartMinutes.collect     { currentSleepScheduleStartMinutes     = it } }
        scope.launch { sleepScheduleEndMinutes.collect       { currentSleepScheduleEndMinutes       = it } }
        scope.launch { sleepScheduleDefaultMinutes.collect   { currentSleepScheduleDefaultMinutes   = it } }
        scope.launch { audioBalance.collect                  { currentAudioBalance                  = it } }
        scope.launch { monoAudio.collect                     { currentMonoAudio                     = it } }
        scope.launch { headsetMultiPressEnabled.collect      { currentHeadsetMultiPressEnabled       = it } }
        scope.launch { headsetDoublePressAction.collect      { currentHeadsetDoublePressAction       = it } }
        scope.launch { headsetTriplePressAction.collect      { currentHeadsetTriplePressAction       = it } }
        scope.launch { btAutoResumeEnabled.collect           { currentBtAutoResumeEnabled            = it } }
        scope.launch { btAutoResumeWindowMinutes.collect     { currentBtAutoResumeWindowMinutes      = it } }
        scope.launch { sleepTimerMinutes.collect             { currentSleepTimerMinutes              = it } }
        scope.launch { appTheme.collect                      { currentAppTheme                       = it } }
        scope.launch { themeColorSource.collect               { currentThemeColorSource              = it } }
        scope.launch { customThemeColor.collect                { currentCustomThemeColor              = it } }
        scope.launch { coverAccents.collect                    { currentCoverAccents                  = it } }
        scope.launch { miniCoverStyle.collect                  { currentMiniCoverStyle                = it } }
        scope.launch { scrubberStyle.collect                   { currentScrubberStyle                 = it } }
        scope.launch { hapticStrength.collect                  { currentHapticStrength                = it } }
        scope.launch { darkMode.collect                         { currentDarkMode                       = it } }
        scope.launch { pureBlack.collect                        { currentPureBlack                      = it } }
        scope.launch { lastOpenBookId.collect                   { currentLastOpenBookId                 = it } }
        scope.launch { lastPlayedBookId.collect                 { currentLastPlayedBookId               = it } }
        scope.launch { setupState.collect                       { currentSetupState                     = it } }

        // Disk-mirror: one collector on the raw (undedup'd) prefsData flow instead of a line in
        // every one of the ~46 setters above — functionally identical ("every write eventually
        // mirrors"), can't be forgotten when a future setting is added, and debounce naturally
        // coalesces a burst of edits (e.g. several sliders dragged in a settings screen) into one
        // file write. Deliberately not distinctUntilChanged()'d — self-healing: if the mirror ever
        // drifted from DataStore for some other reason, the very next unrelated setting change
        // still re-syncs everything, not just the one key that changed.
        scope.launch { watchAndMirrorToDisk() }
        // Same "one collector, not a line in every setter" reasoning, for logging instead of
        // mirroring — see watchAndLogChanges.
        scope.launch { watchAndLogChanges() }
    }

    /** Logs every setting change as "key: old -> new" by diffing consecutive raw [prefsData]
     *  snapshots — chosen over a line in each setter because most setters have no old value
     *  available anyway (they just call `dataStore.edit {}` directly), and the handful that do
     *  keep it in an async-updated `@Volatile current*` that can be stale relative to the write
     *  being logged (see the class doc above `prefsData`). `gemini_api_key` is never logged, not
     *  even redacted, since a raw key could appear on either side of the arrow. A few keys that
     *  legitimately change on ordinary playback (book open/close, app background) are demoted to
     *  DEBUG so routine use doesn't fill the ON-level log with them. */
    private suspend fun watchAndLogChanges() {
        var previous: androidx.datastore.preferences.core.Preferences? = null
        prefsData.collect { current ->
            val prev = previous
            previous = current
            if (prev == null) return@collect // first emission is the initial load, not a change
            val prevMap = prev.asMap()
            val currentMap = current.asMap()
            for (key in prevMap.keys + currentMap.keys) {
                if (key.name == Keys.GEMINI_API_KEY.name) continue
                val oldValue = prevMap[key]
                val newValue = currentMap[key]
                if (oldValue == newValue) continue
                if (key.name in HIGH_FREQUENCY_SETTING_KEYS) {
                    com.betteraudio.util.AppLog.d(com.betteraudio.util.log.LogCat.SETTINGS) { "${key.name}: $oldValue -> $newValue" }
                } else {
                    com.betteraudio.util.AppLog.i(com.betteraudio.util.log.LogCat.SETTINGS, "${key.name}: $oldValue -> $newValue")
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun watchAndMirrorToDisk() {
        prefsData.debounce(MIRROR_DEBOUNCE_MS).collect { mirrorToDisk() }
    }

    private suspend fun mirrorToDisk() {
        val libraryFolder = currentLibraryFolder
        if (libraryFolder.isBlank()) return
        val specs = com.betteraudio.data.diskstore.SettingsSpecs.coreSpecs() +
            com.betteraudio.data.diskstore.SettingsSpecs.pathSpecs() +
            if (backupIncludeApiKey.first()) listOf(com.betteraudio.data.diskstore.SettingsSpecs.apiKeySpec()) else emptyList()
        val values = specs.mapNotNull { spec ->
            spec.get(this@SettingsStore)?.let { com.betteraudio.data.diskstore.SettingsDocument.SettingValue(spec.name, spec.type, it) }
        }
        settingsFileStore.write(libraryFolder, values)
    }

    suspend fun setLibraryFolder(path: String) {
        context.dataStore.edit { it[Keys.LIBRARY_FOLDER]  = path }
    }
    suspend fun setSkipForwardMs(ms: Long) {
        context.dataStore.edit { it[Keys.SKIP_FORWARD_MS] = ms }
    }
    suspend fun setSkipBackMs(ms: Long) {
        context.dataStore.edit { it[Keys.SKIP_BACK_MS]    = ms }
    }
    suspend fun setDefaultSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.DEFAULT_SPEED]   = speed }
    }
    suspend fun setGeminiApiKey(key: String) {
        context.dataStore.edit { it[Keys.GEMINI_API_KEY]          = key }
    }
    suspend fun setDefaultAudioPresetId(id: Long) {
        context.dataStore.edit { it[Keys.DEFAULT_AUDIO_PRESET_ID] = id }
    }
    suspend fun setWidgetDefaultCoverPath(path: String) {
        context.dataStore.edit { it[Keys.WIDGET_DEFAULT_COVER_PATH] = path }
    }
    suspend fun setEbookFolder(path: String) {
        context.dataStore.edit { it[Keys.EBOOK_FOLDER] = path }
    }
    suspend fun setPhantomSeriesCleanupDone(done: Boolean) {
        context.dataStore.edit { it[Keys.PHANTOM_SERIES_CLEANUP_DONE] = done }
    }
    suspend fun setSeriesMembershipRepairDone(done: Boolean) {
        context.dataStore.edit { it[Keys.SERIES_MEMBERSHIP_REPAIR_DONE] = done }
    }
    suspend fun setReaderFontSize(pct: Int) {
        context.dataStore.edit { it[Keys.READER_FONT_SIZE] = pct }
    }
    suspend fun setReaderTheme(name: String) {
        context.dataStore.edit { it[Keys.READER_THEME] = name }
    }
    suspend fun setReaderFontFamily(name: String) {
        context.dataStore.edit { it[Keys.READER_FONT_FAMILY] = name }
    }
    suspend fun setReaderLineSpacing(name: String) {
        context.dataStore.edit { it[Keys.READER_LINE_SPACING] = name }
    }
    suspend fun setReaderMargins(name: String) {
        context.dataStore.edit { it[Keys.READER_MARGINS] = name }
    }
    suspend fun setReaderJustify(on: Boolean) {
        context.dataStore.edit { it[Keys.READER_JUSTIFY] = on }
    }
    suspend fun setReaderHyphenate(on: Boolean) {
        context.dataStore.edit { it[Keys.READER_HYPHENATE] = on }
    }
    suspend fun setHomeSection(name: String) {
        context.dataStore.edit { it[Keys.HOME_SECTION] = name }
    }
    suspend fun setSort(option: String, direction: String) {
        context.dataStore.edit {
            it[Keys.SORT_OPTION]    = option
            it[Keys.SORT_DIRECTION] = direction
        }
    }
    suspend fun setLastOpenBookId(id: Long) {
        context.dataStore.edit { it[Keys.LAST_OPEN_BOOK_ID] = id }
    }
    suspend fun setLastPlayedBookId(id: Long) {
        context.dataStore.edit { it[Keys.LAST_PLAYED_BOOK_ID] = id }
    }
    suspend fun setThemeBookId(id: Long) {
        context.dataStore.edit { it[Keys.THEME_BOOK_ID] = id }
    }
    suspend fun addWidgetCustomColor(color: Long) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.WIDGET_CUSTOM_COLORS]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            val updated = (listOf(color) + current.filterNot { it == color }).take(12)
            prefs[Keys.WIDGET_CUSTOM_COLORS] = updated.joinToString(",")
        }
    }
    /** Replaces the whole custom-color list, distinct from [addWidgetCustomColor] which only ever
     *  prepends one color. Used by the disk-settings mirror's restore path to apply a full CSV
     *  list atomically rather than replaying N single-color inserts. */
    suspend fun setWidgetCustomColorsCsv(csv: String) {
        context.dataStore.edit { it[Keys.WIDGET_CUSTOM_COLORS] = csv }
    }
    suspend fun setAutoRewindSeconds(s: Int) {
        context.dataStore.edit { it[Keys.AUTO_REWIND_SECONDS] = s }
    }
    suspend fun setAutoRewindThresholdMinutes(m: Int) {
        context.dataStore.edit { it[Keys.AUTO_REWIND_THRESHOLD_MINUTES] = m }
    }
    suspend fun setAppStoppedAt(ts: Long) {
        context.dataStore.edit { it[Keys.APP_STOPPED_AT] = ts }
    }
    suspend fun setSkipSilenceMinMs(ms: Long) {
        context.dataStore.edit { it[Keys.SKIP_SILENCE_MIN_MS] = ms }
    }
    suspend fun setSkipSilenceThreshold(level: Int) {
        context.dataStore.edit { it[Keys.SKIP_SILENCE_THRESHOLD] = level }
    }
    suspend fun setSkipSilencePaddingMs(ms: Long) {
        context.dataStore.edit { it[Keys.SKIP_SILENCE_PADDING_MS] = ms.coerceIn(0L, 2_000L) }
    }
    suspend fun setImportStructure(name: String) {
        context.dataStore.edit { it[Keys.IMPORT_STRUCTURE] = name }
    }
    suspend fun setSkippedUpdateVersion(version: String) {
        context.dataStore.edit { it[Keys.SKIPPED_UPDATE_VERSION] = version }
    }
    suspend fun setHomeViewMode(mode: String) {
        context.dataStore.edit { it[Keys.HOME_VIEW_MODE] = mode }
    }
    suspend fun setPlayerShowSeriesCover(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PLAYER_SHOW_SERIES_COVER] = enabled }
    }
    suspend fun setPlayerLandscapeStyle(name: String) {
        context.dataStore.edit { it[Keys.PLAYER_LANDSCAPE_STYLE] = name }
    }
    suspend fun setAppTheme(name: String) {
        context.dataStore.edit { it[Keys.APP_THEME] = name }
    }
    suspend fun setThemeColorSource(name: String) {
        context.dataStore.edit { it[Keys.THEME_COLOR_SOURCE] = name }
    }
    suspend fun setCustomThemeColor(value: String) {
        context.dataStore.edit { it[Keys.CUSTOM_THEME_COLOR] = value }
    }
    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { it[Keys.DARK_MODE] = mode }
    }
    suspend fun setPureBlack(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PURE_BLACK] = enabled }
    }
    suspend fun setDynamicPills(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_PILLS] = enabled }
    }
    suspend fun setBackdropDim(amount: Float) {
        context.dataStore.edit { it[Keys.BACKDROP_DIM] = amount.coerceIn(0f, 1f) }
    }
    /** Pins [argb] as the accent for [coverPath], or clears the pin when [argb] is null. The
     *  read-modify-write happens inside `edit` so two quick taps can't lose one another. */
    suspend fun setCoverAccent(coverPath: String, argb: Int?) {
        context.dataStore.edit {
            val next = CoverAccentCodec.with(it[Keys.COVER_ACCENTS] ?: "", coverPath, argb)
            if (next.isBlank()) it.remove(Keys.COVER_ACCENTS) else it[Keys.COVER_ACCENTS] = next
        }
    }
    suspend fun setMiniCoverStyle(name: String) {
        context.dataStore.edit { it[Keys.MINI_COVER_STYLE] = name }
    }
    suspend fun setScrubberStyle(name: String) {
        context.dataStore.edit { it[Keys.SCRUBBER_STYLE] = name }
    }
    suspend fun setHapticStrength(name: String) {
        context.dataStore.edit { it[Keys.HAPTIC_STRENGTH] = name }
    }
    /** Whole-map write, for the settings restore path only. */
    suspend fun setCoverAccentsRaw(value: String) {
        context.dataStore.edit {
            if (value.isBlank()) it.remove(Keys.COVER_ACCENTS) else it[Keys.COVER_ACCENTS] = value
        }
    }
    suspend fun setWidgetAppColor(argb: Int) {
        context.dataStore.edit { it[Keys.WIDGET_APP_COLOR] = argb }
    }
    suspend fun setWidgetHideWhenIdle(enabled: Boolean) {
        // Set the volatile snapshot eagerly (not just via the async collector above) so a widget
        // re-render fired immediately after this call — see WidgetUpdater.requestRender() callers
        // in SettingsViewModel — reads the new value instead of racing the DataStore write's own collect().
        currentWidgetHideWhenIdle = enabled
        context.dataStore.edit { it[Keys.WIDGET_HIDE_WHEN_IDLE] = enabled }
    }
    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = enabled }
    }
    suspend fun setAutoBackupFolderUri(uri: String) {
        context.dataStore.edit { it[Keys.AUTO_BACKUP_FOLDER_URI] = uri }
    }
    suspend fun setAutoBackupLastRun(ts: Long, status: String) {
        context.dataStore.edit {
            it[Keys.AUTO_BACKUP_LAST_RUN_MS] = ts
            it[Keys.AUTO_BACKUP_LAST_STATUS] = status
        }
    }
    suspend fun setBackupIncludeApiKey(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BACKUP_INCLUDE_API_KEY] = enabled }
    }
    suspend fun setSleepFadeSeconds(seconds: Int) {
        context.dataStore.edit { it[Keys.SLEEP_FADE_SECONDS] = seconds.coerceIn(0, 60) }
    }
    suspend fun setSleepShakeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SLEEP_SHAKE_ENABLED] = enabled }
    }
    suspend fun setSleepShakeResetMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP_SHAKE_RESET_MINUTES] = minutes.coerceIn(1, 120) }
    }
    suspend fun setSleepScheduleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_ENABLED] = enabled }
    }
    suspend fun setSleepScheduleStartMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_START_MINUTES] = minutes.coerceIn(0, 1439) }
    }
    suspend fun setSleepScheduleEndMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_END_MINUTES] = minutes.coerceIn(0, 1439) }
    }
    suspend fun setSleepScheduleDefaultMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_DEFAULT_MINUTES] = minutes.coerceIn(1, 180) }
    }
    suspend fun setSleepTimerMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.SLEEP_TIMER_MINUTES] = minutes.coerceIn(1, 180) }
    }
    suspend fun setAudioBalance(value: Float) {
        context.dataStore.edit { it[Keys.AUDIO_BALANCE] = value.coerceIn(-1f, 1f) }
    }
    suspend fun setMonoAudio(enabled: Boolean) {
        context.dataStore.edit { it[Keys.MONO_AUDIO] = enabled }
    }
    suspend fun setHeadsetMultiPressEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HEADSET_MULTI_PRESS_ENABLED] = enabled }
    }
    suspend fun setHeadsetDoublePressAction(action: String) {
        context.dataStore.edit { it[Keys.HEADSET_DOUBLE_PRESS_ACTION] = action }
    }
    suspend fun setHeadsetTriplePressAction(action: String) {
        context.dataStore.edit { it[Keys.HEADSET_TRIPLE_PRESS_ACTION] = action }
    }
    suspend fun setBtAutoResumeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BT_AUTO_RESUME_ENABLED] = enabled }
    }
    suspend fun setBtAutoResumeWindowMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.BT_AUTO_RESUME_WINDOW_MINUTES] = minutes.coerceIn(1, 120) }
    }
    /** [level] must be "OFF" | "ON" | "VERBOSE". Writes LOG_LEVEL and the legacy ENABLE_FILE_LOGGING
     *  boolean (meaning "level != OFF") in one atomic edit — two separate writes would leave a
     *  window where the two keys disagree, which the debounced disk-mirror/delta-logging collector
     *  on [prefsData] could observe and record. Also eagerly refreshes
     *  [com.betteraudio.util.log.LogPrefsCache] so a level change takes effect for AppLog
     *  immediately rather than waiting on the async collector in VoyageApp — that collector still
     *  writes the same cache on every emission, which is what makes it self-healing for an install
     *  that already had a level set before this cache existed. */
    suspend fun setLogLevel(level: String) {
        require(level == "OFF" || level == "ON" || level == "VERBOSE") { "invalid log level: $level" }
        context.dataStore.edit {
            it[Keys.LOG_LEVEL] = level
            it[Keys.ENABLE_FILE_LOGGING] = (level != "OFF")
        }
        com.betteraudio.util.log.LogPrefsCache.write(context, level)
    }
    suspend fun setLogBudgetMb(mb: Float) {
        val clamped = mb.coerceIn(MIN_LOG_BUDGET_MB, MAX_LOG_BUDGET_MB)
        context.dataStore.edit { it[Keys.LOG_BUDGET_MB] = clamped }
        com.betteraudio.util.log.LogPrefsCache.writeBudgetMb(context, clamped)
    }
    suspend fun setDiskExportVersion(version: Int) {
        context.dataStore.edit { it[Keys.DISK_EXPORT_VERSION] = version }
    }
    /** Only ever called with a terminal value ("FRESH" | "RESTORED") — see LibraryBootstrapper;
     *  transient states are held in memory there, never persisted. */
    suspend fun setSetupState(state: String) {
        // Eager, same reasoning as setWidgetHideWhenIdle: MainActivity reads currentSetupState
        // synchronously right after the bootstrap flow resolves it, and can't wait on the async
        // collector above to catch up.
        currentSetupState = state
        context.dataStore.edit { it[Keys.SETUP_STATE] = state }
    }
    suspend fun setLibraryJsonAppliedAt(ts: Long) {
        context.dataStore.edit { it[Keys.LIBRARY_JSON_APPLIED_AT] = ts }
    }
}
