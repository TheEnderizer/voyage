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
        // Legacy key kept so old Anthropic keys are silently ignored on next read
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
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
        // Resolved Material You ColorScheme.primary (ARGB Int), kept in sync from VoyageTheme so
        // themeless widget providers (no Compose context) can render an "app color" background.
        val WIDGET_APP_COLOR             = intPreferencesKey("widget_app_color")
        // When true, custom widgets render fully transparent (no elements) while nothing is
        // playing, instead of showing a cold play button that revives the last book.
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
    }

    val libraryFolder: Flow<String>  = context.dataStore.data.map { it[Keys.LIBRARY_FOLDER]  ?: "" }
    val skipForwardMs: Flow<Long>    = context.dataStore.data.map { it[Keys.SKIP_FORWARD_MS] ?: DEFAULT_SKIP_FORWARD_MS }
    val skipBackMs: Flow<Long>       = context.dataStore.data.map { it[Keys.SKIP_BACK_MS]    ?: DEFAULT_SKIP_BACK_MS }
    val defaultSpeed: Flow<Float>    = context.dataStore.data.map { it[Keys.DEFAULT_SPEED]   ?: DEFAULT_SPEED }
    val geminiApiKey: Flow<String>          = context.dataStore.data.map { it[Keys.GEMINI_API_KEY]          ?: "" }
    val defaultAudioPresetId: Flow<Long>    = context.dataStore.data.map { it[Keys.DEFAULT_AUDIO_PRESET_ID] ?: -1L }
    val sortOption: Flow<String>            = context.dataStore.data.map { it[Keys.SORT_OPTION]    ?: "TITLE" }
    val sortDirection: Flow<String>         = context.dataStore.data.map { it[Keys.SORT_DIRECTION] ?: "ASC" }
    val lastOpenBookId: Flow<Long>          = context.dataStore.data.map { it[Keys.LAST_OPEN_BOOK_ID] ?: -1L }
    val lastPlayedBookId: Flow<Long>        = context.dataStore.data.map { it[Keys.LAST_PLAYED_BOOK_ID] ?: -1L }
    val autoRewindSeconds: Flow<Int>          = context.dataStore.data.map { it[Keys.AUTO_REWIND_SECONDS] ?: DEFAULT_AUTO_REWIND_SECONDS }
    val autoRewindThresholdMinutes: Flow<Int> = context.dataStore.data.map { it[Keys.AUTO_REWIND_THRESHOLD_MINUTES] ?: DEFAULT_AUTO_REWIND_THRESHOLD_MINUTES }
    val appStoppedAt: Flow<Long>              = context.dataStore.data.map { it[Keys.APP_STOPPED_AT] ?: 0L }
    val skipSilenceMinMs: Flow<Long>          = context.dataStore.data.map { it[Keys.SKIP_SILENCE_MIN_MS] ?: DEFAULT_SKIP_SILENCE_MIN_MS }
    val skipSilenceThreshold: Flow<Int>       = context.dataStore.data.map { it[Keys.SKIP_SILENCE_THRESHOLD] ?: DEFAULT_SKIP_SILENCE_THRESHOLD }
    val skipSilencePaddingMs: Flow<Long>      = context.dataStore.data.map { it[Keys.SKIP_SILENCE_PADDING_MS] ?: DEFAULT_SKIP_SILENCE_PADDING_MS }
    val importStructure: Flow<String>         = context.dataStore.data.map { it[Keys.IMPORT_STRUCTURE] ?: "" }
    val skippedUpdateVersion: Flow<String>    = context.dataStore.data.map { it[Keys.SKIPPED_UPDATE_VERSION] ?: "" }
    val playerShowSeriesCover: Flow<Boolean>  = context.dataStore.data.map { it[Keys.PLAYER_SHOW_SERIES_COVER] ?: false }
    val homeViewMode: Flow<String>            = context.dataStore.data.map { it[Keys.HOME_VIEW_MODE] ?: "BOOKS" }
    val appTheme: Flow<String>                = context.dataStore.data.map { it[Keys.APP_THEME] ?: "" }
    val themeColorSource: Flow<String>        = context.dataStore.data.map { it[Keys.THEME_COLOR_SOURCE] ?: "WALLPAPER" }
    val widgetDefaultCoverPath: Flow<String>  = context.dataStore.data.map { it[Keys.WIDGET_DEFAULT_COVER_PATH] ?: "" }
    val ebookFolder: Flow<String>              = context.dataStore.data.map { it[Keys.EBOOK_FOLDER] ?: "" }
    val readerFontSize: Flow<Int>              = context.dataStore.data.map { it[Keys.READER_FONT_SIZE] ?: 100 }
    val homeSection: Flow<String>              = context.dataStore.data.map { it[Keys.HOME_SECTION] ?: "AUDIO" }
    val customThemeColor: Flow<String>         = context.dataStore.data.map { it[Keys.CUSTOM_THEME_COLOR] ?: "default" }
    val darkMode: Flow<String>                 = context.dataStore.data.map { it[Keys.DARK_MODE] ?: "AUTO" }
    val pureBlack: Flow<Boolean>               = context.dataStore.data.map { it[Keys.PURE_BLACK] ?: false }
    val widgetAppColor: Flow<Int>              = context.dataStore.data.map { it[Keys.WIDGET_APP_COLOR] ?: DEFAULT_WIDGET_APP_COLOR }
    val widgetHideWhenIdle: Flow<Boolean>      = context.dataStore.data.map { it[Keys.WIDGET_HIDE_WHEN_IDLE] ?: false }
    val autoBackupEnabled: Flow<Boolean>       = context.dataStore.data.map { it[Keys.AUTO_BACKUP_ENABLED] ?: false }
    val autoBackupFolderUri: Flow<String>      = context.dataStore.data.map { it[Keys.AUTO_BACKUP_FOLDER_URI] ?: "" }
    val autoBackupLastRunMs: Flow<Long>        = context.dataStore.data.map { it[Keys.AUTO_BACKUP_LAST_RUN_MS] ?: 0L }
    val autoBackupLastStatus: Flow<String>     = context.dataStore.data.map { it[Keys.AUTO_BACKUP_LAST_STATUS] ?: "" }
    val backupIncludeApiKey: Flow<Boolean>     = context.dataStore.data.map { it[Keys.BACKUP_INCLUDE_API_KEY] ?: false }

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
    @Volatile var currentImportStructure            = "";                                     private set
    @Volatile var currentWidgetAppColor             = DEFAULT_WIDGET_APP_COLOR;               private set
    @Volatile var currentWidgetHideWhenIdle         = false;                                   private set

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
        scope.launch { importStructure.collect           { currentImportStructure           = it } }
        scope.launch { widgetAppColor.collect            { currentWidgetAppColor            = it } }
        scope.launch { widgetHideWhenIdle.collect        { currentWidgetHideWhenIdle        = it } }
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
    suspend fun setWidgetAppColor(argb: Int) =
        context.dataStore.edit { it[Keys.WIDGET_APP_COLOR] = argb }.let { }
    suspend fun setWidgetHideWhenIdle(enabled: Boolean) =
        context.dataStore.edit { it[Keys.WIDGET_HIDE_WHEN_IDLE] = enabled }.let { }
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
}
