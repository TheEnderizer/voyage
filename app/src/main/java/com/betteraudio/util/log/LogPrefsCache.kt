package com.betteraudio.util.log

import android.content.Context

/**
 * Tiny synchronous cache of the current log level, read once by `AppLog.init()` before the Hilt
 * graph or DataStore's async `Flow` machinery exist (`VoyageApp.onCreate` calls `AppLog.init()`
 * before `super.onCreate()`). `SettingsStore.setLogLevel` writes it eagerly, and the `logLevel`
 * collector in `VoyageApp.onCreate` writes it on every emission too — that backfill is what makes
 * an existing install (which already has a level in DataStore but never wrote this cache) recover
 * correctly on its very next launch rather than staying stuck reading the OFF default forever.
 *
 * DataStore remains the source of truth for the level; this is a startup-only read-through cache,
 * not a second store. A plain `SharedPreferences` read is a few ms of synchronous I/O, cheap
 * enough to do on the main thread during `Application.onCreate` — versus the DataStore path,
 * which needs a coroutine + `Flow` collection + an IO-dispatched read and lands tens to hundreds
 * of ms later, dropping every line logged before it does.
 *
 * First `SharedPreferences` usage in this project (everywhere else uses DataStore, see
 * `SettingsStore`) — deliberate, for the synchronous-read property DataStore doesn't offer.
 * `android:allowBackup="false"` means this file is never cloud-restored onto a device whose
 * DataStore disagrees with it.
 */
object LogPrefsCache {
    private const val PREFS_NAME = "voyage_log_prefs"
    private const val KEY_LEVEL = "log_level"
    private const val KEY_BUDGET_MB = "log_budget_mb"

    /** Mirrors [com.betteraudio.data.settings.SettingsStore]'s own OFF default — a fresh install
     *  or cleared app data has no file at all, and both must agree on what "nothing set yet"
     *  means so a first launch never accidentally starts up in a logging state the user never
     *  chose. */
    const val DEFAULT_LEVEL = "OFF"
    /** Mirrors [com.betteraudio.data.settings.SettingsStore.DEFAULT_LOG_BUDGET_MB]. Also read
     *  synchronously at startup (alongside the level) so the very first startup sweep — which
     *  runs before DataStore's Flow machinery is up — already enforces a real budget instead of
     *  skipping eviction on that first pass. */
    const val DEFAULT_BUDGET_MB = 2f

    fun write(context: Context, level: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LEVEL, level).apply()
    }

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LEVEL, DEFAULT_LEVEL) ?: DEFAULT_LEVEL

    fun writeBudgetMb(context: Context, mb: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BUDGET_MB, mb).apply()
    }

    fun readBudgetMb(context: Context): Float =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_BUDGET_MB, DEFAULT_BUDGET_MB)
}
