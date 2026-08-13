package com.betteraudio.util

import android.content.Context
import android.util.Log
import com.betteraudio.util.log.LogCat
import com.betteraudio.util.log.LogEngine
import com.betteraudio.util.log.LogLevel
import com.betteraudio.util.log.LogPrefsCache
import com.betteraudio.util.log.Severity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin, process-global facade over [LogEngine] — the actual writer/rotation/ring logic lives
 * there as a plain injectable class so it can be unit-tested against a temp dir. This object's
 * only real jobs are: own the one process-wide `LogEngine` instance, install the crash handler,
 * and expose the same call surface every existing site in the app already uses.
 *
 * New call sites should use the category overloads — `AppLog.i(LogCat.PLAYBACK) { "..." }` — the
 * lambda is only invoked when the line will actually be used (see [LogEngine.log]). The legacy
 * `AppLog.i(tag: String, msg: String)` overloads exist only so the ~180 call sites written against
 * the old free-form string tags keep compiling; Phase 3 migrates all of them to [LogCat] and
 * deletes these.
 */
object AppLog {
    @Volatile private var engine: LogEngine? = null
    private val crashHandled = AtomicBoolean(false)

    fun init(context: Context) {
        val appContext = context.applicationContext
        // Synchronous mkdirs, before anything else — a crash in the first milliseconds of a
        // first-ever launch must still have a directory to write into (blocker 1 / NEW-2). The
        // crash handler is installed right after, also synchronously, so it's guaranteed in place
        // by the time init() returns.
        val dir = File(appContext.filesDir, "logs").apply { mkdirs() }
        installCrashHandler(appContext, dir)

        // Read the level (and budget) synchronously from the startup cache — NOT from DataStore,
        // which would arrive tens to hundreds of ms later via a Flow and drop everything logged
        // before then, including the single most valuable line a future phase adds here: why the
        // process died last time (blocker 3).
        val initialLevel = LogLevel.from(LogPrefsCache.read(appContext))
        val initialBudgetMb = LogPrefsCache.readBudgetMb(appContext)
        val e = LogEngine(dir, initialLevel, initialBudgetMb)
        e.start()
        e.runStartupSweep()
        engine = e

        val pkg = appContext.packageName
        val ver = try {
            val pi = appContext.packageManager.getPackageInfo(pkg, 0)
            "${pi.versionName} (${pi.longVersionCodeCompat()})"
        } catch (_: Throwable) { "?" }
        e.log(Severity.INFO, LogCat.SYSTEM) {
            "──────── logger started · $pkg $ver · Android ${android.os.Build.VERSION.RELEASE} · " +
                "${android.os.Build.MODEL} · level=$initialLevel ────────"
        }
    }

    fun setLevel(level: LogLevel) { engine?.setLevel(level) }
    fun setBudgetMb(mb: Float) { engine?.setBudgetMb(mb) }

    // ── Category API (new call sites) ───────────────────────────────────────────────────────
    // Prefer the lambda overloads for anything in a warm/hot path (loops, tickers, per-frame
    // code) — that's what lets the message go uncomputed when the line won't be used (see
    // LogEngine.log). The plain-String overloads exist for everywhere else: INFO/WARN/ERROR
    // always evaluate their message regardless of form (ring fill is unconditional for those
    // severities), so a String argument costs nothing extra there — it's only DEBUG where the
    // lambda form actually saves work below VERBOSE, which is why these overloads are provided
    // for convenience but the lambda form remains the recommended default for DEBUG call sites.
    fun d(cat: LogCat, msg: () -> String) { engine?.log(Severity.DEBUG, cat, null, msg) }
    fun i(cat: LogCat, msg: () -> String) { engine?.log(Severity.INFO, cat, null, msg) }
    fun w(cat: LogCat, msg: () -> String) { engine?.log(Severity.WARN, cat, null, msg) }
    fun e(cat: LogCat, msg: () -> String) { engine?.log(Severity.ERROR, cat, null, msg) }
    fun d(cat: LogCat, msg: String) { engine?.log(Severity.DEBUG, cat, null) { msg } }
    fun i(cat: LogCat, msg: String) { engine?.log(Severity.INFO, cat, null) { msg } }
    fun w(cat: LogCat, msg: String) { engine?.log(Severity.WARN, cat, null) { msg } }
    fun e(cat: LogCat, msg: String, tr: Throwable? = null) { engine?.log(Severity.ERROR, cat, tr) { msg } }
    // The legacy String-tag overloads (d/i/w/e(tag: String, msg: String)) that every pre-Phase-3
    // call site used are gone — Phase 3 migrated all ~180 of them to LogCat directly and this
    // deletion is what proved the migration was actually complete (a straggler would simply fail
    // to compile rather than silently keep routing through LogCat.forLegacyTag). Do not re-add
    // them; if a new String-tag call site appears, migrate it instead.

    /** Flush the pending buffer to disk immediately. Call when the app backgrounds. */
    fun flush() { engine?.flush() }

    fun clear() { engine?.clear() }

    /** Full retained log, newest segments first, tail-trimmed to [maxChars]. Does file I/O
     *  (including decompression of older segments) — callers must not invoke this on the main
     *  thread; see SettingsViewModel's async wrapper. */
    fun recentText(maxChars: Int = 200_000): String = engine?.recentText(maxChars) ?: "(log not initialised)"

    fun logFile(): File? = engine?.liveFile()

    /** [recentText] with absolute paths scrubbed by default — used by the Diagnostics Copy
     *  button. Pass [redactPaths] = false for the "share raw" escape hatch. */
    fun recentTextForShare(maxChars: Int = 200_000, redactPaths: Boolean = true): String {
        val text = recentText(maxChars)
        return if (redactPaths) com.betteraudio.util.log.ShareRedaction.scrubPaths(text) else text
    }

    fun stats(): LogEngine.LogStats? = engine?.stats()

    /** Writes a share bundle (zip of live + segments + crash files) to [target], scrubbing
     *  absolute paths when [redactPaths] is true. Does file I/O — call off the main thread. */
    fun buildShareBundle(target: File, redactPaths: Boolean) { engine?.buildShareBundle(target, redactPaths) }

    private fun installCrashHandler(context: Context, dir: File) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            // Deliberately does NOT touch LogEngine's writer/lock at all (blocker 1) — a crash
            // must never be able to block on whatever the writer thread happens to be doing (a
            // slow rotation, in particular). Writes its own file via a fresh FileOutputStream,
            // synchronously, on the crashing thread.
            //
            // First-crash-wins: if two threads crash within the same window, only the winner
            // writes a file (reading the ring and opening a stream is itself not instant, and two
            // threads doing it concurrently could interleave); the loser skips straight to `prev`
            // rather than risk not terminating cleanly.
            if (crashHandled.compareAndSet(false, true)) {
                try { writeCrashFile(dir, thread, ex) } catch (_: Throwable) {}
            }
            prev?.uncaughtException(thread, ex)
        }
    }

    private fun writeCrashFile(dir: File, thread: Thread, ex: Throwable) {
        val ring = engine?.ringSnapshot() ?: emptyList()
        // A thread-identifying suffix in the filename: harmless even though only ever one file is
        // written per process crash under the first-wins guard above, and keeps the name
        // collision-free. identityHashCode rather than the deprecated Thread.id — this only needs
        // to distinguish threads, not report a real OS thread id.
        val file = File(dir, "crash-${System.currentTimeMillis()}-${System.identityHashCode(thread)}.log")
        FileOutputStream(file).use { out ->
            fun w(s: String) = out.write((s + "\n").toByteArray(Charsets.UTF_8))
            w("──────── CRASH on '${thread.name}' ────────")
            for (line in ring) w(line)
            w("E CRASH: ${Log.getStackTraceString(ex)}")
            out.flush()
        }
    }
}

private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) longVersionCode
    else @Suppress("DEPRECATION") versionCode.toLong()
