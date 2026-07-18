package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.betteraudio.util.AppLog
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * The single free-size widget provider — thin by design: it only reacts to system lifecycle
 * events (resize, add, remove, restore) and delegates all real work to [WidgetUpdater] via
 * [WidgetEntryPoint] (providers aren't Hilt-injected; the system constructs them via reflection).
 * Playback-driven updates never go through this class at all — PlaybackService calls
 * WidgetUpdater directly, which is what makes rendering immune to OEM broadcast throttling.
 *
 * Every callback uses goAsync() + a bounded timeout + logged failures, never a bare fire-and-forget
 * coroutine with a swallowed exception — the old system's biggest reliability gap.
 */
class VoyageWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(9_000) {
                    val updater = updater(context)
                    for (id in appWidgetIds) updater.renderOneSuspend(id)
                }
            } catch (e: Exception) {
                AppLog.e("Widget", "onUpdate failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(9_000) { updater(context).renderOneSuspend(appWidgetId) }
            } catch (e: Exception) {
                AppLog.e("Widget", "onAppWidgetOptionsChanged failed for id=$appWidgetId", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(9_000) { updater(context).onWidgetsDeletedSuspend(appWidgetIds) }
            } catch (e: Exception) {
                AppLog.e("Widget", "onDeleted failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(9_000) { updater(context).onRestoredSuspend(oldWidgetIds, newWidgetIds) }
            } catch (e: Exception) {
                AppLog.e("Widget", "onRestored failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun updater(context: Context): WidgetUpdater =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater()
}
