package com.betteraudio.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Lightweight file-backed logger so the user can hand over a full trace of what happened
 * leading up to a bug. Writes timestamped lines to `filesDir/logs/app.log`, mirrors every
 * line to Logcat, rotates a single backup at [MAX_BYTES], and captures uncaught exceptions
 * (crashes) via a chained default-uncaught-exception handler.
 *
 * Usage: `AppLog.i("Player", "play book=42")` / `AppLog.e("Scan", "failed", throwable)`.
 * All writes hop onto a single background thread so callers never block on disk.
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024L
    private val writer = Executors.newSingleThreadExecutor()
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

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "app.log")
        backup = File(dir, "app.1.log")
        installCrashHandler()
        val pkg = context.packageName
        val ver = try {
            val pi = context.packageManager.getPackageInfo(pkg, 0)
            "${pi.versionName} (${pi.longVersionCodeCompat()})"
        } catch (_: Throwable) { "?" }
        i("App", "──────── logger started · $pkg $ver · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL} ────────")
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try { writeLine("E", "CRASH", "Uncaught on '${thread.name}': ${Log.getStackTraceString(ex)}") } catch (_: Throwable) {}
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
        when (level) {
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.e(tag, msg)
        }
        writer.execute { writeLine(level, tag, msg) }
    }

    /** Writes a single line, rotating the file first if it has grown past [MAX_BYTES]. */
    private fun writeLine(level: String, tag: String, msg: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                backup?.let { f.copyTo(it, overwrite = true) }
                f.writeText("")
            }
            f.appendText("${stamp.format(Date())} $level/$tag: $msg\n")
        } catch (_: Throwable) { /* never let logging crash the app */ }
    }

    /** Full retained log (backup + current), tail-trimmed to [maxChars]. */
    fun recentText(maxChars: Int = 200_000): String {
        val f = file ?: return "(log not initialised)"
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
            try { file?.writeText(""); backup?.delete() } catch (_: Throwable) {}
            i("App", "──────── log cleared ────────")
        }
    }
}

private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) longVersionCode
    else @Suppress("DEPRECATION") versionCode.toLong()
