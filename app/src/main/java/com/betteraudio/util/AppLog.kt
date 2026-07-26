package com.betteraudio.util

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Lightweight file-backed logger so the user can hand over a full trace of what happened
 * leading up to a bug. Writes timestamped lines to `filesDir/logs/app.log`, mirrors every
 * line to Logcat (always, regardless of the file-logging toggle below), rotates a single
 * backup at [MAX_BYTES], and captures uncaught exceptions (crashes) via a chained
 * default-uncaught-exception handler.
 *
 * File writes hold one [BufferedWriter] open on a single background thread rather than
 * stat-ing and re-opening the file on every line (a continuous syscall stream for an entire
 * listening session otherwise) — flushed periodically and at the two moments that actually
 * matter: the app backgrounding ([flush]) and a crash ([installCrashHandler]).
 *
 * Usage: `AppLog.i("Player", "play book=42")` / `AppLog.e("Scan", "failed", throwable)`.
 * All writes hop onto a single background thread so callers never block on disk.
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private const val FLUSH_INTERVAL_SECONDS = 5L
    private val writer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // Settings → Diagnostics offers app.log for sharing (e.g. attaching to a bug report), so
    // scrub anything that shouldn't leave the device: a Gemini key, or an absolute path that
    // could contain identifying folder/file names from the user's own library.
    private val API_KEY_PATTERN = Regex("AIzaSy[\\w-]+")
    // Spaces are allowed inside an intermediate segment (bounded by '/' on both sides) but not
    // in the trailing one, which has no closing delimiter in free-form log text — allowing
    // spaces there would eat into whatever word follows the path in the same log line.
    private val ABS_PATH_PATTERN = Regex("""/(?:[\w.\- ]+/)+[\w.\-]+""")
    // internal, not private: exercised directly by a fast JUnit test (AppLogRedactionTest)
    // instead of needing an instrumented test just to reach a pure string transform.
    internal fun redact(msg: String): String =
        ABS_PATH_PATTERN.replace(API_KEY_PATTERN.replace(msg, "[REDACTED_KEY]"), "[PATH]")

    @Volatile private var file: File? = null
    @Volatile private var backup: File? = null
    @Volatile private var openWriter: BufferedWriter? = null
    @Volatile private var currentBytes: Long = 0L
    // Off by default — the file write is what costs a stat+open/write/close per line; Logcat
    // mirroring above (see `log()`) is unconditional and always on, so nothing is lost for a
    // session watched live, only the persisted copy is skipped unless the user opts in.
    @Volatile private var fileLoggingEnabled: Boolean = false

    fun init(context: Context) {
        // Install the crash handler synchronously so it's guaranteed to already be in place by
        // the time init() returns, however long the rest of this setup takes — but defer the
        // mkdirs/PackageManager lookup and opening the writer onto the log executor, off the
        // caller's thread (VoyageApp.onCreate, i.e. app startup).
        installCrashHandler()
        val appContext = context.applicationContext
        writer.execute {
            synchronized(this) {
                val dir = File(appContext.filesDir, "logs").apply { mkdirs() }
                val f = File(dir, "app.log")
                file = f
                backup = File(dir, "app.1.log")
                currentBytes = if (f.exists()) f.length() else 0L
                openWriter = BufferedWriter(FileWriter(f, true))
                val pkg = appContext.packageName
                val ver = try {
                    val pi = appContext.packageManager.getPackageInfo(pkg, 0)
                    "${pi.versionName} (${pi.longVersionCodeCompat()})"
                } catch (_: Throwable) { "?" }
                writeLineLocked(
                    "I", "App",
                    "──────── logger started · $pkg $ver · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL} ────────"
                )
            }
        }
        writer.scheduleAtFixedRate(
            { synchronized(this) { try { openWriter?.flush() } catch (_: Throwable) {} } },
            FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS
        )
    }

    fun setFileLoggingEnabled(enabled: Boolean) { fileLoggingEnabled = enabled }

    /** Flush the pending buffer to disk immediately. Call when the app backgrounds. */
    fun flush() { writer.execute { synchronized(this) { try { openWriter?.flush() } catch (_: Throwable) {} } } }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            // Deliberately synchronous and NOT posted to the executor: the process may not
            // survive long enough for a queued task to run. Writes and flushes directly on the
            // crashing thread instead, and always attempts this regardless of the file-logging
            // toggle — a crash is exactly the trace worth keeping even when routine logging
            // isn't persisted.
            try {
                synchronized(this) {
                    writeLineLocked("E", "CRASH", "Uncaught on '${thread.name}': ${Log.getStackTraceString(ex)}")
                    openWriter?.flush()
                }
            } catch (_: Throwable) {}
            prev?.uncaughtException(thread, ex)
        }
    }

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) =
        log("E", tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)

    private fun log(level: String, tag: String, rawMsg: String) {
        val msg = redact(rawMsg)
        // Logcat mirror is unconditional — only the persisted file write is gated by the toggle.
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.e(tag, msg)
        }
        if (!fileLoggingEnabled) return
        writer.execute { synchronized(this) { writeLineLocked(level, tag, msg) } }
    }

    /**
     * Writes a single line to the already-open [openWriter], rotating first if the in-memory
     * byte count has grown past [MAX_BYTES]. Must only be called while holding the monitor on
     * `this` (both call sites synchronize) since it mutates [openWriter]/[currentBytes] and the
     * crash handler can run concurrently with a normal log call.
     */
    private fun writeLineLocked(level: String, tag: String, msg: String) {
        val f = file ?: return
        try {
            val line = "${stamp.format(Date())} $level/$tag: $msg\n"
            if (currentBytes + line.length > MAX_BYTES) {
                openWriter?.flush()
                openWriter?.close()
                backup?.let { f.copyTo(it, overwrite = true) }
                openWriter = BufferedWriter(FileWriter(f, false))
                currentBytes = 0L
            }
            openWriter?.write(line)
            currentBytes += line.length
        } catch (_: Throwable) { /* never let logging crash the app */ }
    }

    /** Full retained log (backup + current), tail-trimmed to [maxChars]. */
    fun recentText(maxChars: Int = 200_000): String {
        val f = file ?: return "(log not initialised)"
        // Flush first so a just-written line is actually visible when the user opens Diagnostics.
        try { openWriter?.flush() } catch (_: Throwable) {}
        val sb = StringBuilder()
        try {
            backup?.takeIf { it.exists() }?.let { sb.append(it.readText()) }
            if (f.exists()) sb.append(f.readText())
        } catch (e: Throwable) {
            return "(failed to read log: ${e.message})"
        }
        val s = sb.toString()
        return if (s.length > maxChars) "…(trimmed)…\n" + s.substring(s.length - maxChars) else s
    }

    fun logFile(): File? = file

    fun clear() {
        writer.execute {
            synchronized(this) {
                try {
                    openWriter?.close()
                    file?.writeText("")
                    backup?.delete()
                    file?.let { openWriter = BufferedWriter(FileWriter(it, true)) }
                    currentBytes = 0L
                } catch (_: Throwable) {}
            }
            i("App", "──────── log cleared ────────")
        }
    }
}

private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) longVersionCode
    else @Suppress("DEPRECATION") versionCode.toLong()
