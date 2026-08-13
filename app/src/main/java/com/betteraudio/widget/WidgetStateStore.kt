package com.betteraudio.widget

import android.content.Context
import android.os.SystemClock
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import com.betteraudio.widget.model.WidgetSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStateDataStore by preferencesDataStore(name = "widget_state")

/**
 * Persists the last known playback [WidgetSnapshot] so a cold-started widget provider (reboot,
 * launcher restart, app force-stopped) always has real data to render instead of an empty widget
 * — the core reliability fix over the old broadcast-only pipeline, which had no state until the
 * first ACTION_UPDATE_WIDGET arrived.
 */
@Singleton
class WidgetStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val key = stringPreferencesKey("snapshot_json")

    /** Synchronous snapshot for render paths that can't suspend (provider callbacks already do,
     *  but this avoids a DataStore read hop on every render when nothing has changed). */
    @Volatile var current: WidgetSnapshot = WidgetSnapshot()
        private set

    val flow: Flow<WidgetSnapshot> = context.widgetStateDataStore.data.map { prefs ->
        decode(prefs[key])
    }

    suspend fun write(snapshot: WidgetSnapshot) {
        current = snapshot
        try {
            context.widgetStateDataStore.edit { it[key] = json.encodeToString(snapshot) }
        } catch (e: Exception) {
            AppLog.e(LogCat.WIDGET, "failed to persist snapshot", e)
        }
    }

    /** Loads the persisted snapshot into [current] (call once at process start, before any
     *  render) and reboot-guards it: if elapsedRealtime() is now LESS than the time it was
     *  written, the device rebooted since — coerce to paused/idle so the widget never shows a
     *  stale "still playing" state that can no longer be true. */
    suspend fun loadIntoMemory() {
        val stored = try {
            decode(context.widgetStateDataStore.data.first()[key])
        } catch (e: Exception) {
            AppLog.e(LogCat.WIDGET, "failed to load persisted snapshot", e)
            null
        }
        current = coerceAfterPossibleReboot(stored ?: current)
    }

    private fun coerceAfterPossibleReboot(snapshot: WidgetSnapshot): WidgetSnapshot {
        if (snapshot.writtenAtElapsedRealtimeMs <= 0L) return snapshot
        val rebooted = SystemClock.elapsedRealtime() < snapshot.writtenAtElapsedRealtimeMs
        return if (rebooted) snapshot.copy(isPlaying = false, sleepEndAtElapsedMs = 0, sleepRemainingMs = 0) else snapshot
    }

    private fun decode(text: String?): WidgetSnapshot {
        if (text.isNullOrBlank()) return WidgetSnapshot()
        return try {
            json.decodeFromString<WidgetSnapshot>(text)
        } catch (e: Exception) {
            AppLog.e(LogCat.WIDGET, "failed to decode snapshot", e)
            WidgetSnapshot()
        }
    }
}
