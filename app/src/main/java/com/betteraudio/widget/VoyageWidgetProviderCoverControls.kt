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
 * A fixed-design launcher-picker entry — "Cover & Controls" (see [DefaultWidgetDesigns]). Unlike
 * [VoyageWidgetProvider] (the freeform "Custom" entry) this has no configure activity: placing it
 * auto-binds to the seeded design on first [onUpdate] (via [WidgetUpdater.renderDefaultSuspend]),
 * so it shows the real design immediately instead of a blank/placeholder widget.
 */
class VoyageWidgetProviderCoverControls : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(9_000) {
                    val updater = updater(context)
                    for (id in appWidgetIds) {
                        updater.renderDefaultSuspend(id, DefaultWidgetDesigns.NAME_COVER_CONTROLS)
                    }
                }
            } catch (e: Exception) {
                AppLog.e("Widget", "CoverControls onUpdate failed", e)
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
                AppLog.e("Widget", "CoverControls onAppWidgetOptionsChanged failed for id=$appWidgetId", e)
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
                AppLog.e("Widget", "CoverControls onDeleted failed", e)
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
                AppLog.e("Widget", "CoverControls onRestored failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun updater(context: Context): WidgetUpdater =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater()
}
