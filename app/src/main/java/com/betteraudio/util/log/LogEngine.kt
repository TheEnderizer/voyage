package com.betteraudio.util.log

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The file-backed logging engine behind [com.betteraudio.util.AppLog]'s thin facade. A plain,
 * injectable class (dir/level/budget passed in, no process-global state) so it can be constructed
 * directly against a temp dir in unit tests, unlike the old `AppLog` singleton `object` whose
 * executor lived in `<clinit>` with no shutdown.
 *
 * Layout in [dir]:
 *  - `live.log` — the current segment, plain text, up to [SEGMENT_MAX_BYTES].
 *  - `seg-<openedAtMs>-<closedAtMs>.log.gz` — closed, gzipped segments. The open timestamp is in
 *    the filename so the retained window's coverage is computable ([stats]) without decompressing
 *    anything.
 *  - `crash-<ts>-<id>.log` — one file per process crash (see `AppLog`'s crash handler, which
 *    writes these directly and never goes through this class).
 *
 * What this class fixes relative to the original single-file `app.log`/`app.1.log` design:
 *   - **Blocker 2** — a failed roll can never leave [openWriter] closed-but-referenced. Every
 *     write path ends with a usable writer or `null`, never a stale closed one, so one bad roll
 *     (e.g. ENOSPC) degrades gracefully on the next write instead of killing logging for the rest
 *     of the process.
 *   - **Blocker 1** — this class never participates in crash handling; a crash can never block on
 *     whatever this class's writer thread happens to be doing (e.g. gzipping a segment).
 *   - **Blocker 4** — the breadcrumb [ring] fills unconditionally for INFO/WARN/ERROR at any
 *     level (including OFF); DEBUG only reaches it at VERBOSE, because [log] never invokes the
 *     message lambda for DEBUG below that level.
 *   - **Blocker 5** — the Logcat mirror is gated independently of the file level.
 *   - A bounded, drop-oldest write queue serviced by its own plain [Thread].
 *   - **Size-based retention** ([evictLocked]) against a user-configurable byte budget — oldest
 *     segments are deleted first; `live.log` and the newest 3 crash files are exempt from the
 *     size loop (crash files beyond the newest 3 are deleted outright, not size-evicted). At
 *     [LogLevel.OFF] a separate, much smaller hard cap applies to crash files instead of the
 *     user's budget — see [evictLocked].
 */
class LogEngine(
    private val dir: File,
    initialLevel: LogLevel,
    initialBudgetMb: Float = LogPrefsCache.DEFAULT_BUDGET_MB,
    // Fault-injection seam for tests (blocker 2's roll-failure-recovery invariant needs a way to
    // make a specific write throw on demand — real ENOSPC can't be simulated portably). Production
    // callers never pass this; the default is the real BufferedWriter-over-FileOutputStream.
    private val writerFactory: (File, Boolean) -> BufferedWriter = { file, append ->
        BufferedWriter(OutputStreamWriter(FileOutputStream(file, append), Charsets.UTF_8))
    }
) {
    // Not `var` properties — Kotlin would generate synthetic accessors that clash on the JVM with
    // the explicit setLevel/setBudgetMb functions below.
    @Volatile private var currentLevel: LogLevel = initialLevel
    val level: LogLevel get() = currentLevel
    @Volatile private var budgetBytes: Long = mbToBytes(initialBudgetMb)

    private companion object {
        const val SEGMENT_MAX_BYTES = 512 * 1024L
        const val FLUSH_INTERVAL_SECONDS = 5L
        const val QUEUE_CAPACITY = 2000
        const val OFF_STATE_CAP_BYTES = 256 * 1024L
        const val KEEP_NEWEST_CRASH_FILES = 3
        // Rough estimate used only for the UI's approximate line count on compressed segments —
        // never for anything correctness-sensitive. ~120 bytes/line is this format's typical
        // uncompressed line length; ~8x is a typical gzip ratio for repetitive log text.
        const val EST_BYTES_PER_LINE = 120L
        const val EST_GZIP_RATIO = 8L
        val API_KEY_PATTERN = Regex("AIzaSy[\\w-]+")
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
        val SEG_NAME = Regex("""seg-(\d+)-(\d+)\.log(\.gz)?""")

        fun mbToBytes(mb: Float): Long = (mb.coerceIn(0.5f, 20f) * 1024 * 1024).toLong()
    }

    private data class Rec(val line: String, val severity: Severity)

    /** Fills unconditionally regardless of [level] — see class doc. Read by AppLog's crash
     *  handler via [ringSnapshot], never written to by it. */
    private val ring = BreadcrumbRing()

    private val liveFile = File(dir, "live.log")

    // Guards all direct mutation of openWriter/currentBytes/rollFailures/degraded/segment files.
    // The writer thread, flush(), clear(), recentText(), the startup sweep and eviction all take
    // this — deliberately NOT taken by AppLog's crash handler (blocker 1's fix).
    private val fileLock = Any()
    private var openWriter: BufferedWriter? = null
    private var currentBytes: Long = 0L
    private var rollFailures: Int = 0
    private var degraded: Boolean = false
    private var segmentOpenedAtMs: Long = System.currentTimeMillis()

    private val queue = ArrayBlockingQueue<Rec>(QUEUE_CAPACITY)
    private val droppedCount = AtomicLong(0)
    @Volatile private var writerThread: Thread? = null
    @Volatile private var running = false
    private var flushExecutor: ScheduledExecutorService? = null

    fun start() {
        synchronized(fileLock) {
            currentBytes = if (liveFile.exists()) liveFile.length() else 0L
            openWriter = try { openWriterFor(liveFile, append = true) } catch (t: Throwable) { null }
            degraded = (openWriter == null)
        }
        running = true
        writerThread = Thread({ drainLoop() }, "AppLog-Writer").apply { isDaemon = true; start() }
        flushExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "AppLog-Flush").apply { isDaemon = true } }
        // Gate only the periodic timer on level != OFF (not error/warn flushing in writeRecord —
        // that stays unconditional so an Off-state ERROR is never left sitting unflushed with only
        // Activity.onStop as a trigger).
        flushExecutor?.scheduleAtFixedRate({
            if (level != LogLevel.OFF) flush()
        }, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Cleans up debris a previous process could have left behind, then enforces the byte budget.
     * Idempotent — safe to call on every launch. Migrates away from the pre-Phase-2 single-file
     * scheme (`app.log`/`app.1.log`) and the old in-`logs/`-dir share export (`voyage-log.txt`,
     * superseded by the fixed-name bundle in `filesDir/backup_share/`), both of which would
     * otherwise sit uncounted (and unevictable, since neither matches the segment/live naming
     * eviction looks for) in the budgeted directory forever.
     */
    fun runStartupSweep() {
        synchronized(fileLock) {
            File(dir, "app.log").delete()
            File(dir, "app.1.log").delete()
            File(dir, "voyage-log.txt").delete()
            // A process killed mid-gzip (see the OEM background-kill notes in CLAUDE.md) can leave
            // an uncompressed seg-*.log with no matching .gz — finish the job or give up cleanly.
            dir.listFiles { f -> f.name.startsWith("seg-") && f.name.endsWith(".log") }
                ?.forEach { plain -> if (!gzipInPlaceLocked(plain)) plain.delete() }
            evictLocked()
        }
    }

    fun setLevel(newLevel: LogLevel) { currentLevel = newLevel }

    /** Re-runs eviction immediately so shrinking the budget takes effect right away rather than
     *  waiting for the next roll. */
    fun setBudgetMb(mb: Float) {
        budgetBytes = mbToBytes(mb)
        synchronized(fileLock) { evictLocked() }
    }

    /**
     * Central call every `AppLog.d/i/w/e` overload routes through. [msg] is only invoked when
     * this line will be used for something: always for INFO/WARN/ERROR (ring fill is
     * unconditional), and for DEBUG only when [level] is VERBOSE — that single condition is what
     * lets the inline-lambda call sites genuinely skip work instead of just skipping the write.
     */
    fun log(severity: Severity, cat: LogCat, tr: Throwable? = null, msg: () -> String) {
        val lvl = level
        val shouldCompute = severity != Severity.DEBUG || lvl == LogLevel.VERBOSE
        if (!shouldCompute) return

        val epochMs = System.currentTimeMillis()
        val threadName = Thread.currentThread().name
        val rawMsg = if (tr != null) "${msg()}\n${android.util.Log.getStackTraceString(tr)}" else msg()
        // AIzaSy redaction happens here, on the caller, always — this is what feeds BOTH the ring
        // and the file queue below, so a key can never reach either one unredacted (in particular
        // it can never reach a crash file, which reads the ring directly and never revisits this
        // string). Path scrubbing is deliberately NOT done here — full paths stay in the on-device
        // log; only buildShareBundle() scrubs them, and only when asked to.
        val redacted = API_KEY_PATTERN.replace(rawMsg, "[REDACTED_KEY]")
        val line = "${TIME_FMT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))} " +
            "${severity.code} ${cat.name} [$threadName] $redacted"

        ring.add(line)
        mirrorToLogcat(severity, cat, redacted)

        val shouldPersist = when {
            severity == Severity.ERROR -> true                       // always, even at OFF
            lvl == LogLevel.OFF -> false                              // INFO/WARN/DEBUG: nothing else persists at OFF
            severity == Severity.DEBUG -> lvl == LogLevel.VERBOSE
            else -> true                                              // INFO/WARN at ON or VERBOSE
        }
        if (shouldPersist) enqueue(Rec(line, severity))
    }

    private fun mirrorToLogcat(severity: Severity, cat: LogCat, msg: String) {
        val tag = cat.name
        if (!com.betteraudio.BuildConfig.DEBUG && !android.util.Log.isLoggable(tag, priorityFor(severity))) return
        when (severity) {
            Severity.DEBUG -> android.util.Log.d(tag, msg)
            Severity.INFO  -> android.util.Log.i(tag, msg)
            Severity.WARN  -> android.util.Log.w(tag, msg)
            Severity.ERROR -> android.util.Log.e(tag, msg)
        }
    }

    private fun priorityFor(s: Severity) = when (s) {
        Severity.DEBUG -> android.util.Log.DEBUG
        Severity.INFO -> android.util.Log.INFO
        Severity.WARN -> android.util.Log.WARN
        Severity.ERROR -> android.util.Log.ERROR
    }

    /** Drop-oldest on overflow — this is a write-behind buffer, so the newest line (closest to
     *  whatever is currently happening) is what's worth keeping; drop-newest would instead throw
     *  away the lines closest to a failure. */
    private fun enqueue(rec: Rec) {
        if (!queue.offer(rec)) {
            queue.poll()
            droppedCount.incrementAndGet()
            queue.offer(rec)
        }
    }

    private fun drainLoop() {
        while (running || queue.isNotEmpty()) {
            val rec = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            writeRecord(rec)
        }
    }

    /** Drains any currently-queued records on the CALLING thread — races harmlessly with the
     *  background writer thread over the same [queue] (each record is popped by exactly one of
     *  them; either way the actual file write is serialized through [writeRecord]'s own
     *  [fileLock]). Without this, [flush] would only flush whatever the writer thread had already
     *  drained within its own 200ms poll window — not a real guarantee for a caller like
     *  MainActivity.onStop that needs "everything logged so far is now on disk" before the
     *  process might get killed. */
    private fun drainQueueSync() {
        while (true) {
            val rec = queue.poll() ?: break
            writeRecord(rec)
        }
    }

    private fun writeRecord(rec: Rec) {
        synchronized(fileLock) {
            val dropped = droppedCount.getAndSet(0)
            if (dropped > 0) {
                writeLineRawLocked("${Severity.WARN.code} ${LogCat.SYSTEM.name} [AppLog-Writer] queue overflow — dropped $dropped buffered line(s)")
            }
            val bytes = (rec.line + "\n").toByteArray(Charsets.UTF_8).size
            if (currentBytes + bytes > SEGMENT_MAX_BYTES) rollLocked()
            writeLineRawLocked(rec.line)
            // Unconditional flush on W/E — the 5s periodic timer is gated on level != OFF, so an
            // Off-state ERROR (the one thing Off-state logging exists to keep) must not depend on
            // it; MainActivity.onStop is the only other flush trigger and doesn't fire for a
            // foregrounded PlaybackService with no Activity.
            if (rec.severity == Severity.WARN || rec.severity == Severity.ERROR) flushLocked()
        }
    }

    private fun writeLineRawLocked(line: String) {
        val w = openWriter ?: return
        try {
            val bytes = (line + "\n").toByteArray(Charsets.UTF_8).size
            w.write(line); w.newLine()
            currentBytes += bytes
        } catch (_: Throwable) { /* never let logging crash the app */ }
    }

    /** Invariant: after this returns, [openWriter] is either usable or `null` — never
     *  closed-but-referenced. Must be called holding [fileLock]. Closes and renames `live.log` to
     *  a plain segment, gzips it, reopens a fresh `live.log`, then enforces the budget. */
    private fun rollLocked() {
        try {
            openWriter?.flush(); openWriter?.close(); openWriter = null
            if (liveFile.exists() && liveFile.length() > 0) {
                val closedAtMs = System.currentTimeMillis()
                val plainSeg = File(dir, "seg-$segmentOpenedAtMs-$closedAtMs.log")
                if (!liveFile.renameTo(plainSeg)) {
                    // Cross-filesystem rename can fail even within one app's filesDir on some OEM
                    // storage setups — fall back to copy+delete rather than losing the roll.
                    liveFile.copyTo(plainSeg, overwrite = true)
                    liveFile.delete()
                }
                // A gzip failure here isn't a roll failure — the plain segment is left in place
                // and picked up by the next runStartupSweep(), or just counted toward the budget
                // as-is by evictLocked() in the meantime.
                gzipInPlaceLocked(plainSeg)
            }
            segmentOpenedAtMs = System.currentTimeMillis()
            openWriter = openWriterFor(liveFile, append = false)
            currentBytes = 0L
            rollFailures = 0
            degraded = false
            evictLocked()
        } catch (t: Throwable) {
            rollFailures++
            openWriter = try { openWriterFor(liveFile, append = true) } catch (_: Throwable) { null }
            currentBytes = try { liveFile.length() } catch (_: Throwable) { 0L }
            degraded = (openWriter == null)
            if (openWriter != null) {
                writeLineRawLocked("${Severity.WARN.code} ${LogCat.SYSTEM.name} [AppLog] log roll failed (attempt #$rollFailures): ${t.message}")
            }
        }
    }

    /** Returns true on success. Must be called holding [fileLock]. */
    private fun gzipInPlaceLocked(plain: File): Boolean = try {
        val gz = File(plain.parentFile, plain.name + ".gz")
        GZIPOutputStream(FileOutputStream(gz)).use { out -> plain.inputStream().use { it.copyTo(out) } }
        plain.delete()
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Enforces the byte budget (or, at [LogLevel.OFF], the fixed [OFF_STATE_CAP_BYTES] cap on
     * crash files only — that cap supersedes the "keep newest 3" rule below it, since at OFF
     * there's no user budget to speak of). Deletes oldest segments first; `live.log` and the
     * newest [KEEP_NEWEST_CRASH_FILES] crash files are exempt from the size loop. If the exempt
     * set alone exceeds the budget, stops and logs a warning instead of looping forever trying to
     * reach an unreachable target. Must be called holding [fileLock].
     */
    private fun evictLocked() {
        val allFiles = dir.listFiles()?.toList() ?: return
        val crashFiles = allFiles.filter { it.name.startsWith("crash-") }.sortedByDescending { it.lastModified() }

        if (currentLevel == LogLevel.OFF) {
            var total = 0L
            for (f in crashFiles) {
                total += f.length()
                if (total > OFF_STATE_CAP_BYTES) f.delete()
            }
            return
        }

        // Crash files beyond the newest N are deleted outright — they're capped by count, not by
        // the size loop below.
        val exemptCrash = crashFiles.take(KEEP_NEWEST_CRASH_FILES)
        crashFiles.drop(KEEP_NEWEST_CRASH_FILES).forEach { it.delete() }

        val liveLen = if (liveFile.exists()) liveFile.length() else 0L
        val exemptTotal = liveLen + exemptCrash.sumOf { it.length() }
        if (exemptTotal > budgetBytes) {
            writeLineRawLocked(
                "${Severity.WARN.code} ${LogCat.SYSTEM.name} [AppLog] log directory over budget " +
                    "(unevictable files alone = ${exemptTotal}B > budget ${budgetBytes}B) — nothing further to evict"
            )
            return
        }

        // Both plain (not-yet-gzipped) and .gz segments count and are evicted the same way —
        // oldest first by mtime, which matches creation order since segments are never touched
        // after being closed.
        val segFiles = allFiles.filter { it.name.startsWith("seg-") }.sortedBy { it.lastModified() }
        var runningTotal = exemptTotal + segFiles.sumOf { it.length() }
        for (f in segFiles) {
            if (runningTotal <= budgetBytes) break
            val len = f.length()
            if (f.delete()) runningTotal -= len
        }
    }

    private fun openWriterFor(file: File, append: Boolean): BufferedWriter = writerFactory(file, append)

    fun flush() {
        drainQueueSync()
        synchronized(fileLock) { flushLocked() }
    }
    private fun flushLocked() { try { openWriter?.flush() } catch (_: Throwable) {} }

    fun clear() {
        synchronized(fileLock) {
            try {
                openWriter?.close()
                dir.listFiles { f -> f.name.startsWith("seg-") }?.forEach { it.delete() }
                liveFile.writeText("", Charsets.UTF_8)
                openWriter = openWriterFor(liveFile, append = true)
                currentBytes = 0L
                segmentOpenedAtMs = System.currentTimeMillis()
            } catch (_: Throwable) {}
        }
        ring.clear()
        log(Severity.INFO, LogCat.SYSTEM) { "──────── log cleared ────────" }
    }

    /** Full retained log, newest segments first up to [maxChars], tail-trimmed. A synchronous,
     *  potentially-decompressing read — callers on Android (Diagnostics) dispatch this onto
     *  Dispatchers.IO themselves; this class deliberately stays coroutine-free. */
    fun recentText(maxChars: Int): String {
        drainQueueSync()
        return synchronized(fileLock) {
            flushLocked()
            val sb = StringBuilder()
            try {
                if (liveFile.exists()) sb.append(liveFile.readText(Charsets.UTF_8))
            } catch (e: Throwable) {
                return "(failed to read log: ${e.message})"
            }
            if (sb.length < maxChars) {
                val segFiles = (dir.listFiles { f -> f.name.startsWith("seg-") } ?: emptyArray())
                    .sortedByDescending { it.lastModified() } // newest first
                for (f in segFiles) {
                    if (sb.length >= maxChars) break
                    try {
                        val content = readSegmentText(f)
                        sb.insert(0, content) // walking backwards in time, so prepend
                    } catch (_: Throwable) { /* skip an unreadable segment rather than fail the whole read */ }
                }
            }
            val s = sb.toString()
            if (s.length > maxChars) "…(trimmed)…\n" + s.substring(s.length - maxChars) else s
        }
    }

    private fun readSegmentText(f: File): String =
        if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8)
        else f.readText(Charsets.UTF_8)

    data class LogStats(
        val bytesOnDisk: Long,
        val budgetBytes: Long,
        val approxLines: Long,
        val coverageMs: Long
    )

    /** Approximate by design (the line count over compressed segments is an estimate — see
     *  [EST_BYTES_PER_LINE]/[EST_GZIP_RATIO]) — good enough for the Diagnostics size/coverage
     *  readout, not meant for anything exact. */
    fun stats(): LogStats {
        drainQueueSync()
        return synchronized(fileLock) {
            val segFiles = dir.listFiles { f -> f.name.startsWith("seg-") }?.toList() ?: emptyList()
            val liveLen = if (liveFile.exists()) liveFile.length() else 0L
            val bytesOnDisk = liveLen + segFiles.sumOf { it.length() }

            var liveLines = 0L
            try { if (liveFile.exists()) liveFile.forEachLine { liveLines++ } } catch (_: Throwable) {}
            val segLinesEstimate = segFiles.sumOf { it.length() * EST_GZIP_RATIO / EST_BYTES_PER_LINE }

            val oldestOpenMs = segFiles.mapNotNull { parseOpenedAtMs(it.name) }.minOrNull() ?: segmentOpenedAtMs
            val coverageMs = (System.currentTimeMillis() - oldestOpenMs).coerceAtLeast(0L)

            LogStats(bytesOnDisk, budgetBytes, liveLines + segLinesEstimate, coverageMs)
        }
    }

    private fun parseOpenedAtMs(name: String): Long? = SEG_NAME.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * Writes a zip bundle of everything retained (live + segments + crash files) to [target] for
     * sharing. When [redactPaths] is true, absolute filesystem paths are scrubbed from the
     * bundled copy — the on-device log itself is never touched by this. Overwrites [target] if it
     * already exists (callers use a fixed filename so repeated shares self-overwrite instead of
     * accumulating in `backup_share/`).
     */
    fun buildShareBundle(target: File, redactPaths: Boolean) {
        drainQueueSync()
        synchronized(fileLock) {
            flushLocked()
            fun scrub(s: String) = if (redactPaths) ShareRedaction.scrubPaths(s) else s
            ZipOutputStream(FileOutputStream(target)).use { zip ->
                fun addEntry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(scrub(text).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                try { if (liveFile.exists()) addEntry("live.log", liveFile.readText(Charsets.UTF_8)) } catch (_: Throwable) {}
                (dir.listFiles { f -> f.name.startsWith("seg-") } ?: emptyArray())
                    .sortedBy { it.lastModified() }
                    .forEach { f -> try { addEntry(f.name.removeSuffix(".gz"), readSegmentText(f)) } catch (_: Throwable) {} }
                (dir.listFiles { f -> f.name.startsWith("crash-") } ?: emptyArray())
                    .sortedBy { it.lastModified() }
                    .forEach { f -> try { addEntry(f.name, f.readText(Charsets.UTF_8)) } catch (_: Throwable) {} }
            }
        }
    }

    fun liveFile(): File = liveFile
    fun ringSnapshot(): List<String> = ring.snapshot()

    fun shutdown() {
        running = false
        writerThread?.join(1000)
        drainQueueSync() // catch anything enqueued in the small window around the join above
        flushExecutor?.shutdown()
        synchronized(fileLock) { try { openWriter?.flush(); openWriter?.close() } catch (_: Throwable) {} }
    }
}
