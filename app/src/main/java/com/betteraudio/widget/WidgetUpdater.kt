package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.betteraudio.R
import com.betteraudio.data.db.dao.WidgetBindingDao
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.entities.WidgetBinding
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.WidgetDesignCodec
import com.betteraudio.widget.model.WidgetSnapshot
import com.betteraudio.widget.render.IconAssets
import com.betteraudio.widget.render.WidgetPainter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single write path to every placed widget: PlaybackService pushes state here directly
 * (no more exported ACTION_UPDATE_WIDGET broadcasts, which OEMs are free to throttle/kill), and
 * this calls AppWidgetManager.updateAppWidget itself. All failures are logged, never swallowed —
 * the old system's broad catch-and-ignore blocks made "widget silently stopped updating" bugs
 * unreportable.
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stateStore: WidgetStateStore,
    private val designDao: WidgetDesignDao,
    private val bindingDao: WidgetBindingDao,
    private val settings: SettingsStore,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> AppLog.e("Widget", "unhandled failure in WidgetUpdater scope", e) }
    )

    private val renderMutex = Mutex()
    private var pendingRender = false

    /** Whether a widget's bound design has a countdown-showing SLEEP_TIMER element — refreshed on
     *  every render, consulted by [tickCountdown] so the 1 Hz sleep tick only re-renders widgets
     *  that actually need it instead of every placed widget. */
    private val countdownCache = ConcurrentHashMap<Int, Boolean>()

    /** Loads the persisted snapshot into memory and sweeps orphan bindings, ONCE, before this
     *  singleton does anything else. A cold provider render can arrive the instant Hilt finishes
     *  constructing this class (e.g. onUpdate() firing right after a reboot restores widgets) —
     *  without awaiting this job first, that render could read [WidgetStateStore.current] before
     *  [WidgetStateStore.loadIntoMemory] finishes its suspend point, defeating the whole point of
     *  persisting state for cold starts. Every path that touches `current` awaits this first. */
    private val readyJob: Deferred<Unit> = scope.async {
        stateStore.loadIntoMemory()
        gcOrphanBindings()
    }

    /** Persists [snapshot] and re-renders every placed widget from it. Called by PlaybackService
     *  on every playback event. */
    fun push(snapshot: WidgetSnapshot) {
        scope.launch {
            readyJob.await()
            stateStore.write(snapshot)
            triggerRenderAll()
        }
    }

    /** Re-renders every placed widget from the currently persisted/in-memory state, without a new
     *  snapshot — used after a design is saved, a binding changes, or a widget-affecting setting
     *  (default cover, hide-when-idle, app color) changes. */
    fun requestRender() {
        scope.launch { triggerRenderAll() }
    }

    /** Flips the last known snapshot to paused/idle — called from PlaybackService.onDestroy(),
     *  BEFORE it cancels its own serviceScope, so the widget never keeps showing "playing" after
     *  the service (and its player) are gone. Runs on this class's own scope, not the caller's, so
     *  it isn't cancelled by the caller tearing itself down right afterward. */
    fun pushPaused() {
        scope.launch {
            readyJob.await()
            stateStore.write(
                stateStore.current.copy(isPlaying = false, sleepEndAtElapsedMs = 0, sleepRemainingMs = 0)
            )
            triggerRenderAll()
        }
    }

    fun renderOneAsync(appWidgetId: Int) {
        scope.launch { renderOneSuspend(appWidgetId) }
    }

    /** Suspends until the render actually completes — used by [VoyageWidgetProvider]'s
     *  goAsync()-backed callbacks, which must not call `pending.finish()` until the work is done
     *  or the system may kill the process mid-render. renderOneInternal already catches and logs
     *  its own failures, so this never throws. */
    suspend fun renderOneSuspend(appWidgetId: Int) {
        renderOneInternal(AppWidgetManager.getInstance(context), appWidgetId)
    }

    suspend fun renderAllSuspend() {
        renderAllInternal()
    }

    /** Re-renders only the widgets whose bound design shows a sleep-timer countdown — called once
     *  per second by PlaybackService's existing sleep tick loop instead of the old system's global
     *  ACTION_UPDATE_WIDGET rebroadcast (which woke up and re-rendered every widget, every tick). */
    fun tickCountdown() {
        scope.launch {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, VoyageWidgetProvider::class.java))
                for (id in ids) {
                    if (countdownCache[id] == true) renderOneInternal(manager, id)
                }
            } catch (e: Exception) {
                AppLog.e("Widget", "tickCountdown failed", e)
            }
        }
    }

    fun onWidgetsDeleted(appWidgetIds: IntArray) {
        scope.launch { onWidgetsDeletedSuspend(appWidgetIds) }
    }

    suspend fun onWidgetsDeletedSuspend(appWidgetIds: IntArray) {
        try {
            bindingDao.deleteByIds(appWidgetIds.toList())
            appWidgetIds.forEach { countdownCache.remove(it) }
        } catch (e: Exception) {
            AppLog.e("Widget", "onWidgetsDeleted failed", e)
        }
    }

    /** Launcher backup/restore assigns new appWidgetIds — remap bindings so restored widgets keep
     *  their design instead of falling back to the placeholder (the old system never handled this
     *  at all). */
    fun onRestored(oldIds: IntArray, newIds: IntArray) {
        scope.launch { onRestoredSuspend(oldIds, newIds) }
    }

    suspend fun onRestoredSuspend(oldIds: IntArray, newIds: IntArray) {
        try {
            for (i in oldIds.indices) {
                val old = oldIds.getOrNull(i) ?: continue
                val new = newIds.getOrNull(i) ?: continue
                val designId = bindingDao.getDesignId(old) ?: continue
                bindingDao.upsert(WidgetBinding(new, designId, System.currentTimeMillis()))
                bindingDao.deleteByIds(listOf(old))
            }
            triggerRenderAll()
        } catch (e: Exception) {
            AppLog.e("Widget", "onRestored failed", e)
        }
    }

    /** Unbinds every widget placed with [designId] and re-renders them as the "pick a design"
     *  placeholder — called when a design is deleted from the gallery. */
    fun onDesignDeleted(designId: Long) {
        scope.launch {
            try {
                val bindings = bindingDao.bindingsForDesign(designId)
                bindingDao.deleteByDesignId(designId)
                if (bindings.isNotEmpty()) {
                    val manager = AppWidgetManager.getInstance(context)
                    for (b in bindings) renderOneInternal(manager, b.appWidgetId)
                }
            } catch (e: Exception) {
                AppLog.e("Widget", "onDesignDeleted failed for id=$designId", e)
            }
        }
    }

    private suspend fun triggerRenderAll() {
        if (renderMutex.isLocked) {
            pendingRender = true
            return
        }
        renderMutex.withLock {
            do {
                pendingRender = false
                renderAllInternal()
            } while (pendingRender)
        }
    }

    private suspend fun renderAllInternal() {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, VoyageWidgetProvider::class.java))
        for (id in ids) renderOneInternal(manager, id)
    }

    private suspend fun renderOneInternal(manager: AppWidgetManager, appWidgetId: Int) {
        try {
            readyJob.await()
            val views = RemoteViews(context.packageName, R.layout.widget_host)
            val designId = bindingDao.getDesignId(appWidgetId)
            val design = designId?.let { designDao.getById(it) }

            val opts = manager.getAppWidgetOptions(appWidgetId)
            val density = context.resources.displayMetrics.density
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(40)
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).coerceAtLeast(40)
            val (pxW, pxH) = capSize((wDp * density).toInt().coerceAtLeast(1), (hDp * density).toInt().coerceAtLeast(1))

            if (design == null) {
                countdownCache[appWidgetId] = false
                renderPlaceholder(views, pxW, pxH, appWidgetId)
                manager.updateAppWidget(appWidgetId, views)
                return
            }

            val doc = WidgetDesignCodec.decode(design.documentJson)
            countdownCache[appWidgetId] = doc.elements.any {
                it.type == ElementType.SLEEP_TIMER && it.showCountdown
            }

            val snapshot = stateStore.current
            val defaultCover = File(context.filesDir, "widget_default_cover.jpg").let {
                if (it.exists()) it.absolutePath else null
            }
            val paintOpts = WidgetPainter.PaintOptions(
                accentFallback = settings.currentWidgetAppColor,
                hideWhenIdle = settings.currentWidgetHideWhenIdle,
                defaultCoverPath = defaultCover,
            )

            val bitmap = WidgetPainter.paint(context, doc, design.aspectRatio, snapshot, pxW, pxH, paintOpts)
            views.setImageViewBitmap(R.id.iv_canvas, bitmap)

            val box = WidgetPainter.contentBox(pxW, pxH, design.aspectRatio)
            val scale = WidgetPainter.unitScale(box)
            val hideIdle = paintOpts.hideWhenIdle && !snapshot.isPlaying
            val claims = mutableListOf<Pair<RectF, android.app.PendingIntent>>()
            if (!hideIdle) {
                for (el in doc.elements) {
                    val pendingIntent = if (el.type.isControl) {
                        WidgetIntents.forControl(context, appWidgetId, el)
                    } else {
                        WidgetIntents.forTapAction(context, appWidgetId, el)
                    } ?: continue
                    claims += WidgetPainter.elementBoundingBox(el, box, scale) to pendingIntent
                }
            }
            // Fall-through click handling: the root view carries the open-app intent, and ONLY
            // cells actually claimed by an element get their own intent. Unclaimed transparent
            // cells have no click listener, so their taps bubble up to widget_root. This keeps the
            // RemoteViews Binder payload tiny (a handful of intents, not one per grid cell) — the
            // old "set an intent on all 144/256 cells" approach overran the transaction limit.
            views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openAppIntent(context))
            val assigned = HitGrid.assign(pxW, pxH, claims)
            for (r in 0 until HitGrid.ROWS) {
                for (c in 0 until HitGrid.COLS) {
                    val pi = assigned[r * HitGrid.COLS + c] ?: continue
                    val cellId = HitGrid.cellId(context, r, c)
                    if (cellId != 0) views.setOnClickPendingIntent(cellId, pi)
                }
            }

            manager.updateAppWidget(appWidgetId, views)
        } catch (e: Exception) {
            AppLog.e("Widget", "renderOne failed for id=$appWidgetId", e)
        }
    }

    /** Shown when a widget has no binding, or its bound design was deleted — tapping anywhere
     *  opens [WidgetConfigureActivity] to pick a design. */
    private fun renderPlaceholder(views: RemoteViews, w: Int, h: Int, appWidgetId: Int) {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(settings.currentWidgetAppColor)
        val size = min(w, h) * 0.34f
        ContextCompat.getDrawable(context, IconAssets.placeholder)?.mutate()?.apply {
            setTint(Color.WHITE)
            val left = (w - size) / 2f
            val top = (h - size) / 2f
            setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
            draw(canvas)
        }
        views.setImageViewBitmap(R.id.iv_canvas, bmp)

        // Root-only intent — the transparent grid cells have no listener, so every tap bubbles
        // up to widget_root and opens the design picker.
        views.setOnClickPendingIntent(R.id.widget_root, configurePendingIntent(appWidgetId))
    }

    private fun configurePendingIntent(appWidgetId: Int): android.app.PendingIntent {
        val intent = Intent(context, WidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return android.app.PendingIntent.getActivity(
            context, 1_000_000 + appWidgetId, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun gcOrphanBindings() {
        try {
            val manager = AppWidgetManager.getInstance(context)
            val liveIds = manager.getAppWidgetIds(ComponentName(context, VoyageWidgetProvider::class.java)).toSet()
            val stale = bindingDao.allBindings().map { it.appWidgetId }.filter { it !in liveIds }
            if (stale.isNotEmpty()) bindingDao.deleteByIds(stale)
        } catch (e: Exception) {
            AppLog.e("Widget", "orphan binding GC failed", e)
        }
    }

    /** Caps a granted size to a conservative bitmap budget so the RemoteViews stays well under the
     *  ~1 MB Binder transaction limit (a widget bitmap is parceled whole to the launcher). Max edge
     *  1000px and ~800k px total keeps an ARGB_8888 bitmap around 3 MB worst case — but most of
     *  that transports via the parcel's bitmap blob; the earlier 2.6 Mpx (~10 MB) cap combined with
     *  a per-cell intent on all 256 grid cells is what made the launcher fail to load the widget.
     *  A widget is viewed small, so 1000px on the long edge is plenty crisp. */
    private fun capSize(pxW: Int, pxH: Int): Pair<Int, Int> {
        var w = pxW
        var h = pxH
        val maxEdge = 1000
        if (max(w, h) > maxEdge) {
            val s = maxEdge.toFloat() / max(w, h)
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        val maxPixels = 800_000L
        if (w.toLong() * h.toLong() > maxPixels) {
            val s = sqrt(maxPixels.toDouble() / (w.toDouble() * h.toDouble()))
            w = (w * s).toInt().coerceAtLeast(1)
            h = (h * s).toInt().coerceAtLeast(1)
        }
        return w to h
    }
}
