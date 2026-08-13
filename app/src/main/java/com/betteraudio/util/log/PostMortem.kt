package com.betteraudio.util.log

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.betteraudio.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase 9 — "why did the process die last time", and "is the main thread stuck right now".
 * Both depend on blocker 3 (AppLog.init() knowing the log level synchronously at startup) to
 * actually be captured — see AppLog.kt's doc. Call [logExitReasons] once, early, from
 * VoyageApp.onCreate.
 */
object PostMortem {

    /**
     * Logs why the app's process ended last time: crash, ANR, user-swipe-kill, or an OEM/system
     * low-memory kill — exactly the "the widget went stale overnight" class of bug that's
     * otherwise unanswerable, given the background-kill behavior on some OEMs documented in
     * CLAUDE.md. API 30+ only ([ActivityManager.getHistoricalProcessExitReasons] doesn't exist
     * below that); silently does nothing on older devices rather than degrade in a confusing way.
     */
    fun logExitReasons(context: Context, maxCount: Int = 5) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            // pid=0 means "every recent exit for this package", not just the current process.
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, maxCount)
            if (reasons.isEmpty()) {
                AppLog.i(LogCat.SYSTEM, "no historical process exit reasons available (fresh install, or the OS hasn't recorded one yet)")
                return
            }
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            for (info in reasons) {
                val reason = reasonName(info.reason)
                val status = if (info.status != 0) " status=${info.status}" else ""
                val desc = info.description?.let { " '$it'" } ?: ""
                AppLog.i(
                    LogCat.SYSTEM,
                    "process exit: ${fmt.format(Date(info.timestamp))} reason=$reason importance=${importanceName(info.importance)}$status$desc"
                )
            }
        } catch (_: Throwable) {
            // Never let a diagnostics-only call affect startup.
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER" // the OnePlus/ColorOS-style background freeze CLAUDE.md documents
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED" // swiped away from recents
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "reason=$reason"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "VISIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "CACHED"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "GONE"
        else -> "$importance"
    }

    /**
     * A main-thread stall watchdog: posts a heartbeat to the main [Looper] every second from a
     * dedicated background thread, and warns if a heartbeat hasn't landed within [THRESHOLD_MS].
     * Uses [SystemClock.uptimeMillis] (pauses during deep sleep, same as the main thread does)
     * rather than wall-clock, and can be suppressed while the app is backgrounded via
     * [setSuppressed] — otherwise an OEM background freeze (see CLAUDE.md's background-kill
     * notes) would look identical to a real main-thread stall and fire constantly on exactly the
     * devices already known to be unreliable, defeating the point. A single process-wide
     * instance, matching AppLog's own singleton shape — MainActivity toggles [setSuppressed] from
     * onStart/onStop (there's no app-wide ProcessLifecycleOwner wired up in this project to hook
     * instead, so this only approximates "backgrounded", but foreground↔background is exactly the
     * transition an OEM freeze happens around, so it's the right approximation).
     */
    object Watchdog {
        private const val THRESHOLD_MS = 4_000L
        private val lastHeartbeatUptimeMs = AtomicLong(SystemClock.uptimeMillis())
        @Volatile private var suppressed = false
        @Volatile private var running = false
        private var thread: Thread? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        fun setSuppressed(suppress: Boolean) { suppressed = suppress }

        fun start() {
            if (running) return
            running = true
            thread = Thread({
                while (running) {
                    val checkAtMs = SystemClock.uptimeMillis()
                    mainHandler.post { lastHeartbeatUptimeMs.set(SystemClock.uptimeMillis()) }
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { return@Thread }
                    if (suppressed) continue
                    val staleness = SystemClock.uptimeMillis() - lastHeartbeatUptimeMs.get()
                    if (staleness > THRESHOLD_MS) {
                        AppLog.w(LogCat.SYSTEM, "main thread appears stalled: last heartbeat ${staleness}ms ago (checked at uptime=$checkAtMs)")
                    }
                }
            }, "AppLog-Watchdog").apply { isDaemon = true; start() }
        }

        fun stop() {
            running = false
            thread?.interrupt()
            thread = null
        }
    }
}
