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
    }

    companion object {
        const val DEFAULT_SKIP_FORWARD_MS = 30_000L
        const val DEFAULT_SKIP_BACK_MS    = 15_000L
        const val DEFAULT_SPEED           = 1.0f
        const val DEFAULT_AUTO_REWIND_SECONDS           = 10
        const val DEFAULT_AUTO_REWIND_THRESHOLD_MINUTES = 30
        // Skip-silence engine config. Threshold is the SilenceSkippingAudioProcessor PCM level
        // below which audio counts as silence (higher = more aggressive). Applied when the
        // playback service builds its audio pipeline.
        const val DEFAULT_SKIP_SILENCE_MIN_MS    = 1_000L
        const val DEFAULT_SKIP_SILENCE_THRESHOLD = 1024
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
}
