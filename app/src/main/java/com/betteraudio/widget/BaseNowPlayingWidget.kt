package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Base for the now-playing widgets. Subclasses build their own [RemoteViews] for their layout/size;
 * this class handles the lifecycle and the shared `ACTION_UPDATE_WIDGET` broadcast so all sizes
 * refresh together. Each provider resolves its *own* component's widget ids via `javaClass`.
 *
 * [buildViews] decodes a bitmap and runs [androidx.palette.graphics.Palette] over it (see
 * [WidgetRender]) — real work, not a cheap RemoteViews build — and `ACTION_UPDATE_WIDGET` fires
 * once a second during a sleep-timer countdown, so every callback here hops off the main thread
 * with `goAsync()`, matching [com.betteraudio.widget.custom.CustomWidgetProvider].
 */
abstract class BaseNowPlayingWidget : AppWidgetProvider() {

    protected abstract fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        state: WidgetState
    ): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                for (id in appWidgetIds) {
                    manager.updateAppWidget(id, buildViews(context, manager, id, WidgetRender.lastState))
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                manager.updateAppWidget(appWidgetId, buildViews(context, manager, appWidgetId, WidgetRender.lastState))
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != WidgetRender.ACTION_UPDATE_WIDGET) return
        val state = WidgetRender.stateFrom(intent)
        WidgetRender.lastState = state
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
                for (id in ids) {
                    manager.updateAppWidget(id, buildViews(context, manager, id, state))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
