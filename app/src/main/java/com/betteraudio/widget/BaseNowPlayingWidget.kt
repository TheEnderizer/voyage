package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/**
 * Base for the now-playing widgets. Subclasses build their own [RemoteViews] for their layout/size;
 * this class handles the lifecycle and the shared `ACTION_UPDATE_WIDGET` broadcast so all sizes
 * refresh together. Each provider resolves its *own* component's widget ids via `javaClass`.
 */
abstract class BaseNowPlayingWidget : AppWidgetProvider() {

    protected abstract fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        state: WidgetState
    ): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context, manager, id, WidgetRender.lastState))
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        manager.updateAppWidget(appWidgetId, buildViews(context, manager, appWidgetId, WidgetRender.lastState))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRender.ACTION_UPDATE_WIDGET) {
            val state = WidgetRender.stateFrom(intent)
            WidgetRender.lastState = state
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
            for (id in ids) {
                manager.updateAppWidget(id, buildViews(context, manager, id, state))
            }
        }
    }
}
