package com.betteraudio.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

/**
 * App-wide settings persisted with DataStore.
 *
 * Exposes [Flow]s for reactive UI, plus `current*` cached values that the playback
 * service / controller can read synchronously without suspending.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val LIBRARY_FOLDER = stringPreferencesKey("library_folder")
        val SKIP_FORWARD_MS = longPreferencesKey("skip_forward_ms")
        val SKIP_BACK_MS = longPreferencesKey("skip_back_ms")
        val DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
    }

    companion object {
        const val DEFAULT_SKIP_FORWARD_MS = 30_000L
        const val DEFAULT_SKIP_BACK_MS = 15_000L
        const val DEFAULT_SPEED = 1.0f
    }

    val libraryFolder: Flow<String> =
        context.dataStore.data.map { it[Keys.LIBRARY_FOLDER] ?: "" }
    val skipForwardMs: Flow<Long> =
        context.dataStore.data.map { it[Keys.SKIP_FORWARD_MS] ?: DEFAULT_SKIP_FORWARD_MS }
    val skipBackMs: Flow<Long> =
        context.dataStore.data.map { it[Keys.SKIP_BACK_MS] ?: DEFAULT_SKIP_BACK_MS }
    val defaultSpeed: Flow<Float> =
        context.dataStore.data.map { it[Keys.DEFAULT_SPEED] ?: DEFAULT_SPEED }
    val anthropicApiKey: Flow<String> =
        context.dataStore.data.map { it[Keys.ANTHROPIC_API_KEY] ?: "" }

    @Volatile var currentSkipForwardMs = DEFAULT_SKIP_FORWARD_MS; private set
    @Volatile var currentSkipBackMs = DEFAULT_SKIP_BACK_MS; private set
    @Volatile var currentDefaultSpeed = DEFAULT_SPEED; private set
    @Volatile var currentLibraryFolder = ""; private set
    @Volatile var currentAnthropicApiKey = ""; private set

    init {
        scope.launch { skipForwardMs.collect { currentSkipForwardMs = it } }
        scope.launch { skipBackMs.collect { currentSkipBackMs = it } }
        scope.launch { defaultSpeed.collect { currentDefaultSpeed = it } }
        scope.launch { libraryFolder.collect { currentLibraryFolder = it } }
        scope.launch { anthropicApiKey.collect { currentAnthropicApiKey = it } }
    }

    suspend fun setLibraryFolder(path: String) =
        context.dataStore.edit { it[Keys.LIBRARY_FOLDER] = path }.let { }

    suspend fun setSkipForwardMs(ms: Long) =
        context.dataStore.edit { it[Keys.SKIP_FORWARD_MS] = ms }.let { }

    suspend fun setSkipBackMs(ms: Long) =
        context.dataStore.edit { it[Keys.SKIP_BACK_MS] = ms }.let { }

    suspend fun setDefaultSpeed(speed: Float) =
        context.dataStore.edit { it[Keys.DEFAULT_SPEED] = speed }.let { }

    suspend fun setAnthropicApiKey(key: String) =
        context.dataStore.edit { it[Keys.ANTHROPIC_API_KEY] = key }.let { }
}
