package com.betteraudio.widget.custom

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.betteraudio.R
import com.betteraudio.widget.WidgetRender
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Shared behaviour for the 4 size-bucket custom-widget providers. AppWidgetProvider instances
 * aren't Hilt-injected (the system constructs them via reflection), so DB/settings access goes
 * through [CustomWidgetEntryPoint]. All 4 buckets share one host layout (widget_custom_host.xml)
 * with a static 6×6 tap-grid (see [WidgetGrid]) and one renderer ([CustomWidgetRenderer]).
 */
abstract class CustomWidgetProvider(private val bucket: WidgetSizeBucket) : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                for (id in appWidgetIds) renderOne(context, manager, id)
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
                renderOne(context, manager, appWidgetId)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != WidgetRender.ACTION_UPDATE_WIDGET) return
        WidgetRender.lastState = WidgetRender.stateFrom(intent)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
                for (id in ids) renderOne(context, manager, id)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context.applicationContext, CustomWidgetEntryPoint::class.java
                )
                entryPoint.widgetBindingDao().deleteByIds(appWidgetIds.toList())
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun renderOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, CustomWidgetEntryPoint::class.java
        )
        val designId = entryPoint.widgetBindingDao().getDesignId(appWidgetId)
        val settings = entryPoint.settingsStore()
        val views = RemoteViews(context.packageName, R.layout.widget_custom_host)

        if (designId == null) {
            // Not configured yet (shouldn't normally happen — the configure activity binds
            // before the first render). Show nothing but keep the widget tappable to open the app.
            views.setOnClickPendingIntent(R.id.widget_root, WidgetRender.openAppIntent(context))
            manager.updateAppWidget(appWidgetId, views)
            return
        }
        val design = entryPoint.customWidgetDesignDao().getById(designId)
        if (design == null) {
            views.setOnClickPendingIntent(R.id.widget_root, WidgetRender.openAppIntent(context))
            manager.updateAppWidget(appWidgetId, views)
            return
        }

        val opts = manager.getAppWidgetOptions(appWidgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, bucket.minWidthDp).coerceAtLeast(40)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, bucket.minHeightDp).coerceAtLeast(40)
        val pxW = WidgetRender.dp(context, wDp)
        val pxH = WidgetRender.dp(context, hDp)

        val state = WidgetRender.lastState
        val bitmap = CustomWidgetRenderer.render(
            context, design, state,
            appColor = settings.currentWidgetAppColor,
            hideWhenIdle = settings.currentWidgetHideWhenIdle,
            pxW = pxW, pxH = pxH
        )
        views.setImageViewBitmap(R.id.iv_canvas, bitmap)

        val elements = WidgetElementCodec.decode(design.elementsJson)
        val cellIntents = arrayOfNulls<android.app.PendingIntent>(WidgetGrid.ROWS * WidgetGrid.COLS)
        val hideIdle = settings.currentWidgetHideWhenIdle && !state.isPlaying
        // Same effective (aspect-corrected, icon-square) rect the renderer draws each element at —
        // see WidgetElement.effectiveRect — so a tap only lands on a button's cells, not on
        // whatever oversized legacy footprint an older app version may have saved for it.
        val realAspect = pxW.toFloat() / pxH.toFloat()
        if (!hideIdle) {
            for (el in elements) {
                val pi = WidgetActionIntents.forElement(context, appWidgetId, el) ?: continue
                val eff = el.effectiveRect(realAspect)
                for (r in WidgetGrid.rowRange(eff[1], eff[3])) {
                    for (c in WidgetGrid.colRange(eff[0], eff[2])) {
                        cellIntents[r * WidgetGrid.COLS + c] = pi
                    }
                }
            }
        }
        val openApp = WidgetRender.openAppIntent(context)
        for (r in 0 until WidgetGrid.ROWS) {
            for (c in 0 until WidgetGrid.COLS) {
                val id = WidgetGrid.cellId(context, r, c)
                if (id == 0) continue
                views.setOnClickPendingIntent(id, cellIntents[r * WidgetGrid.COLS + c] ?: openApp)
            }
        }

        manager.updateAppWidget(appWidgetId, views)
    }
}

class CustomWidgetSmall : CustomWidgetProvider(WidgetSizeBucket.SMALL)
class CustomWidgetWide : CustomWidgetProvider(WidgetSizeBucket.WIDE)
class CustomWidgetTall : CustomWidgetProvider(WidgetSizeBucket.TALL)
class CustomWidgetLarge : CustomWidgetProvider(WidgetSizeBucket.LARGE)
