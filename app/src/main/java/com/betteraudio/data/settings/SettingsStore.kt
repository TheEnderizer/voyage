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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "better_audio_settings")

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
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
        // Top-level home section: AUDIO (default) | EBOOKS.
        val HOME_SECTION                 = stringPreferencesKey("home_section")
        // Reader text size, as a percentage (100 = default CSS font-size).
        val READER_FONT_SIZE             = intPreferencesKey("reader_font_size")
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
    }

    companion object {
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
    val sortOption: Flow<String>            = prefsData.map { it[Keys.SORT_OPTION]    ?: "TITLE" }.distinctUntilChanged()
    val sortDirection: Flow<String>         = prefsData.map { it[Keys.SORT_DIRECTION] ?: "ASC" }.distinctUntilChanged()
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
    val homeViewMode: Flow<String>            = prefsData.map { it[Keys.HOME_VIEW_MODE] ?: "BOOKS" }.distinctUntilChanged()
    val appTheme: Flow<String>                = prefsData.map { it[Keys.APP_THEME] ?: "" }.distinctUntilChanged()
    val themeColorSource: Flow<String>        = prefsData.map { it[Keys.THEME_COLOR_SOURCE] ?: "WALLPAPER" }.distinctUntilChanged()
    val widgetDefaultCoverPath: Flow<String>  = prefsData.map { it[Keys.WIDGET_DEFAULT_COVER_PATH] ?: "" }.distinctUntilChanged()
    val ebookFolder: Flow<String>              = prefsData.map { it[Keys.EBOOK_FOLDER] ?: "" }.distinctUntilChanged()
    val phantomSeriesCleanupDone: Flow<Boolean> = prefsData.map { it[Keys.PHANTOM_SERIES_CLEANUP_DONE] ?: false }.distinctUntilChanged()
    val readerFontSize: Flow<Int>              = prefsData.map { it[Keys.READER_FONT_SIZE] ?: 100 }.distinctUntilChanged()
    val homeSection: Flow<String>              = prefsData.map { it[Keys.HOME_SECTION] ?: "AUDIO" }.distinctUntilChanged()
    val customThemeColor: Flow<String>         = prefsData.map { it[Keys.CUSTOM_THEME_COLOR] ?: "default" }.distinctUntilChanged()
    val darkMode: Flow<String>                 = prefsData.map { it[Keys.DARK_MODE] ?: "AUTO" }.distinctUntilChanged()
    val pureBlack: Flow<Boolean>               = prefsData.map { it[Keys.PURE_BLACK] ?: false }.distinctUntilChanged()
    val dynamicPills: Flow<Boolean>            = prefsData.map { it[Keys.DYNAMIC_PILLS] ?: false }.distinctUntilChanged()
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
    @Volatile var currentDarkMode                   = "AUTO";                                    private set
    @Volatile var currentPureBlack                  = false;                                    private set
    @Volatile var currentLastOpenBookId             = -1L;                                       private set
    @Volatile var currentLastPlayedBookId           = -1L;                                       private set

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
        scope.launch { darkMode.collect                         { currentDarkMode                       = it } }
        scope.launch { pureBlack.collect                        { currentPureBlack                      = it } }
        scope.launch { lastOpenBookId.collect                   { currentLastOpenBookId                 = it } }
        scope.launch { lastPlayedBookId.collect                 { currentLastPlayedBookId               = it } }
    }

    suspend fun setLibraryFolder(path: String) =
        context.dataStore.edit { it[Keys.LIBRARY_FOLDER]  = path }.let { }
    suspend fun setSkipForwardMs(ms: Long) =
        context.dataStore.edit { it[Keys.SKIP_FORWARD_MS] = ms }.let { }
    suspend fun setSkipBackMs(ms: Long) =
        context.dataStore.edit { it[Keys.SKIP_BACK_MS]    = ms }.let { }
    suspend fun setDefaultSpeed(speed: Float) =
        context.dataStore.edit { it[Keys.DEFAULT_SPEED]   = speed }.let { }
    suspend fun setGeminiApiKey(key: String) =
        context.dataStore.edit { it[Keys.GEMINI_API_KEY]          = key }.let { }
    suspend fun setDefaultAudioPresetId(id: Long) =
        context.dataStore.edit { it[Keys.DEFAULT_AUDIO_PRESET_ID] = id }.let { }
    suspend fun setWidgetDefaultCoverPath(path: String) =
        context.dataStore.edit { it[Keys.WIDGET_DEFAULT_COVER_PATH] = path }.let { }
    suspend fun setEbookFolder(path: String) =
        context.dataStore.edit { it[Keys.EBOOK_FOLDER] = path }.let { }
    suspend fun setPhantomSeriesCleanupDone(done: Boolean) =
        context.dataStore.edit { it[Keys.PHANTOM_SERIES_CLEANUP_DONE] = done }.let { }
    suspend fun setReaderFontSize(pct: Int) =
        context.dataStore.edit { it[Keys.READER_FONT_SIZE] = pct }.let { }
    suspend fun setHomeSection(name: String) =
        context.dataStore.edit { it[Keys.HOME_SECTION] = name }.let { }
    suspend fun setSort(option: String, direction: String) =
        context.dataStore.edit {
            it[Keys.SORT_OPTION]    = option
            it[Keys.SORT_DIRECTION] = direction
        }.let { }
    suspend fun setLastOpenBookId(id: Long) =
        context.dataStore.edit { it[Keys.LAST_OPEN_BOOK_ID] = id }.let { }
    suspend fun setLastPlayedBookId(id: Long) =
        context.dataStore.edit { it[Keys.LAST_PLAYED_BOOK_ID] = id }.let { }
    suspend fun setThemeBookId(id: Long) =
        context.dataStore.edit { it[Keys.THEME_BOOK_ID] = id }.let { }
    suspend fun addWidgetCustomColor(color: Long) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.WIDGET_CUSTOM_COLORS]?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()
        val updated = (listOf(color) + current.filterNot { it == color }).take(12)
        prefs[Keys.WIDGET_CUSTOM_COLORS] = updated.joinToString(",")
    }.let { }
    suspend fun setAutoRewindSeconds(s: Int) =
        context.dataStore.edit { it[Keys.AUTO_REWIND_SECONDS] = s }.let { }
    suspend fun setAutoRewindThresholdMinutes(m: Int) =
        context.dataStore.edit { it[Keys.AUTO_REWIND_THRESHOLD_MINUTES] = m }.let { }
    suspend fun setAppStoppedAt(ts: Long) =
        context.dataStore.edit { it[Keys.APP_STOPPED_AT] = ts }.let { }
    suspend fun setSkipSilenceMinMs(ms: Long) =
        context.dataStore.edit { it[Keys.SKIP_SILENCE_MIN_MS] = ms }.let { }
    suspend fun setSkipSilenceThreshold(level: Int) =
        context.dataStore.edit { it[Keys.SKIP_SILENCE_THRESHOLD] = level }.let { }
    suspend fun setSkipSilencePaddingMs(ms: Long) =
        context.dataStore.edit { it[Keys.SKIP_SILENCE_PADDING_MS] = ms.coerceIn(0L, 2_000L) }.let { }
    suspend fun setImportStructure(name: String) =
        context.dataStore.edit { it[Keys.IMPORT_STRUCTURE] = name }.let { }
    suspend fun setSkippedUpdateVersion(version: String) =
        context.dataStore.edit { it[Keys.SKIPPED_UPDATE_VERSION] = version }.let { }
    suspend fun setHomeViewMode(mode: String) =
        context.dataStore.edit { it[Keys.HOME_VIEW_MODE] = mode }.let { }
    suspend fun setPlayerShowSeriesCover(enabled: Boolean) =
        context.dataStore.edit { it[Keys.PLAYER_SHOW_SERIES_COVER] = enabled }.let { }
    suspend fun setAppTheme(name: String) =
        context.dataStore.edit { it[Keys.APP_THEME] = name }.let { }
    suspend fun setThemeColorSource(name: String) =
        context.dataStore.edit { it[Keys.THEME_COLOR_SOURCE] = name }.let { }
    suspend fun setCustomThemeColor(value: String) =
        context.dataStore.edit { it[Keys.CUSTOM_THEME_COLOR] = value }.let { }
    suspend fun setDarkMode(mode: String) =
        context.dataStore.edit { it[Keys.DARK_MODE] = mode }.let { }
    suspend fun setPureBlack(enabled: Boolean) =
        context.dataStore.edit { it[Keys.PURE_BLACK] = enabled }.let { }
    suspend fun setDynamicPills(enabled: Boolean) =
        context.dataStore.edit { it[Keys.DYNAMIC_PILLS] = enabled }.let { }
    suspend fun setWidgetAppColor(argb: Int) =
        context.dataStore.edit { it[Keys.WIDGET_APP_COLOR] = argb }.let { }
    suspend fun setWidgetHideWhenIdle(enabled: Boolean) {
        // Set the volatile snapshot eagerly (not just via the async collector above) so a widget
        // re-render fired immediately after this call — see WidgetUpdater.requestRender() callers
        // in SettingsViewModel — reads the new value instead of racing the DataStore write's own collect().
        currentWidgetHideWhenIdle = enabled
        context.dataStore.edit { it[Keys.WIDGET_HIDE_WHEN_IDLE] = enabled }.let { }
    }
    suspend fun setAutoBackupEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.AUTO_BACKUP_ENABLED] = enabled }.let { }
    suspend fun setAutoBackupFolderUri(uri: String) =
        context.dataStore.edit { it[Keys.AUTO_BACKUP_FOLDER_URI] = uri }.let { }
    suspend fun setAutoBackupLastRun(ts: Long, status: String) =
        context.dataStore.edit {
            it[Keys.AUTO_BACKUP_LAST_RUN_MS] = ts
            it[Keys.AUTO_BACKUP_LAST_STATUS] = status
        }.let { }
    suspend fun setBackupIncludeApiKey(enabled: Boolean) =
        context.dataStore.edit { it[Keys.BACKUP_INCLUDE_API_KEY] = enabled }.let { }
    suspend fun setSleepFadeSeconds(seconds: Int) =
        context.dataStore.edit { it[Keys.SLEEP_FADE_SECONDS] = seconds.coerceIn(0, 60) }.let { }
    suspend fun setSleepShakeEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.SLEEP_SHAKE_ENABLED] = enabled }.let { }
    suspend fun setSleepShakeResetMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.SLEEP_SHAKE_RESET_MINUTES] = minutes.coerceIn(1, 120) }.let { }
    suspend fun setSleepScheduleEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_ENABLED] = enabled }.let { }
    suspend fun setSleepScheduleStartMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_START_MINUTES] = minutes.coerceIn(0, 1439) }.let { }
    suspend fun setSleepScheduleEndMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_END_MINUTES] = minutes.coerceIn(0, 1439) }.let { }
    suspend fun setSleepScheduleDefaultMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.SLEEP_SCHEDULE_DEFAULT_MINUTES] = minutes.coerceIn(1, 180) }.let { }
    suspend fun setSleepTimerMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.SLEEP_TIMER_MINUTES] = minutes.coerceIn(1, 180) }.let { }
    suspend fun setAudioBalance(value: Float) =
        context.dataStore.edit { it[Keys.AUDIO_BALANCE] = value.coerceIn(-1f, 1f) }.let { }
    suspend fun setMonoAudio(enabled: Boolean) =
        context.dataStore.edit { it[Keys.MONO_AUDIO] = enabled }.let { }
    suspend fun setHeadsetMultiPressEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.HEADSET_MULTI_PRESS_ENABLED] = enabled }.let { }
    suspend fun setHeadsetDoublePressAction(action: String) =
        context.dataStore.edit { it[Keys.HEADSET_DOUBLE_PRESS_ACTION] = action }.let { }
    suspend fun setHeadsetTriplePressAction(action: String) =
        context.dataStore.edit { it[Keys.HEADSET_TRIPLE_PRESS_ACTION] = action }.let { }
    suspend fun setBtAutoResumeEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.BT_AUTO_RESUME_ENABLED] = enabled }.let { }
    suspend fun setBtAutoResumeWindowMinutes(minutes: Int) =
        context.dataStore.edit { it[Keys.BT_AUTO_RESUME_WINDOW_MINUTES] = minutes.coerceIn(1, 120) }.let { }
}
