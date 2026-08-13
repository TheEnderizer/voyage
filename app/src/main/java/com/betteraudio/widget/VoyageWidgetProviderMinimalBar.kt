package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * A fixed-design launcher-picker entry — "Minimal Bar" (see [DefaultWidgetDesigns]). See
 * [VoyageWidgetProviderCoverControls]'s doc for the auto-bind-on-first-placement rationale; this
 * is the identical recipe for the other seeded default design.
 */
class VoyageWidgetProviderMinimalBar : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.Default)

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(9_000) {
                    val updater = updater(context)
                    for (id in appWidgetIds) {
                        updater.renderDefaultSuspend(id, DefaultWidgetDesigns.NAME_MINIMAL_BAR)
                    }
                }
            } catch (e: Exception) {
                AppLog.e(LogCat.WIDGET, "MinimalBar onUpdate failed", e)
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
                AppLog.e(LogCat.WIDGET, "MinimalBar onAppWidgetOptionsChanged failed for id=$appWidgetId", e)
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
                AppLog.e(LogCat.WIDGET, "MinimalBar onDeleted failed", e)
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
                AppLog.e(LogCat.WIDGET, "MinimalBar onRestored failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun updater(context: Context): WidgetUpdater =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater()
}
