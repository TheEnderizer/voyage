package com.betteraudio.util.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the behavior Phase 1 actually changed: which severities invoke their message lambda at
 * all (the whole point of the inline-lambda call sites — skip work, not just skip the write), and
 * what persists to disk vs. only to the in-memory ring at each [LogLevel]. The roll-failure
 * recovery invariant (blocker 2) is covered via the [LogEngine] `writerFactory` fault-injection
 * seam (see the "roll failure recovery" tests below) rather than fragile filesystem tricks
 * (e.g. read-only dirs behave inconsistently across platforms) — real ENOSPC can't be simulated
 * portably, but a writer that throws on demand exercises the exact same recovery path.
 */
class LogEngineTest {
    private lateinit var dir: File
    private lateinit var engine: LogEngine

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("logengine-test").toFile()
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        dir.deleteRecursively()
    }

    private fun newEngine(level: LogLevel): LogEngine =
        LogEngine(dir, level).also { it.start(); engine = it }

    @Test
    fun `debug lambda is never invoked at ON — this is the whole point of the lambda overloads`() {
        val e = newEngine(LogLevel.ON)
        val calls = AtomicInteger(0)
        repeat(5) { e.log(Severity.DEBUG, LogCat.PLAYBACK) { calls.incrementAndGet(); "x" } }
        assertEquals(0, calls.get())
    }

    @Test
    fun `debug lambda IS invoked at VERBOSE`() {
        val e = newEngine(LogLevel.VERBOSE)
        val calls = AtomicInteger(0)
        e.log(Severity.DEBUG, LogCat.PLAYBACK) { calls.incrementAndGet(); "x" }
        assertEquals(1, calls.get())
    }

    @Test
    fun `info lambda is invoked even at OFF — the ring must fill unconditionally`() {
        val e = newEngine(LogLevel.OFF)
        val calls = AtomicInteger(0)
        e.log(Severity.INFO, LogCat.LIBRARY) { calls.incrementAndGet(); "bootstrap: restored" }
        assertEquals(1, calls.get())
        assertTrue(e.ringSnapshot().any { it.contains("bootstrap: restored") })
    }

    @Test
    fun `at OFF, ERROR reaches disk but INFO and WARN do not`() {
        val e = newEngine(LogLevel.OFF)
        e.log(Severity.INFO, LogCat.SCAN) { "info line" }
        e.log(Severity.WARN, LogCat.SCAN) { "warn line" }
        e.log(Severity.ERROR, LogCat.SCAN) { "error line" }
        val text = e.recentText(200_000)
        assertFalse("INFO must not persist at OFF", text.contains("info line"))
        assertFalse("WARN must not persist at OFF", text.contains("warn line"))
        assertTrue("ERROR must persist even at OFF", text.contains("error line"))
        // All three still went into the ring, regardless of level.
        val ring = e.ringSnapshot().joinToString("\n")
        assertTrue(ring.contains("info line"))
        assertTrue(ring.contains("warn line"))
        assertTrue(ring.contains("error line"))
    }

    @Test
    fun `at ON, INFO and WARN persist but DEBUG does not`() {
        val e = newEngine(LogLevel.ON)
        e.log(Severity.DEBUG, LogCat.SCAN) { "debug line" }
        e.log(Severity.INFO, LogCat.SCAN) { "info line" }
        val text = e.recentText(200_000)
        assertFalse(text.contains("debug line"))
        assertTrue(text.contains("info line"))
    }

    @Test
    fun `at VERBOSE, DEBUG persists too`() {
        val e = newEngine(LogLevel.VERBOSE)
        e.log(Severity.DEBUG, LogCat.SCAN) { "debug line" }
        val text = e.recentText(200_000)
        assertTrue(text.contains("debug line"))
    }

    @Test
    fun `setLevel takes effect on the next call`() {
        val e = newEngine(LogLevel.OFF)
        e.log(Severity.INFO, LogCat.SCAN) { "before" }
        e.setLevel(LogLevel.ON)
        e.log(Severity.INFO, LogCat.SCAN) { "after" }
        val text = e.recentText(200_000)
        assertFalse(text.contains("before"))
        assertTrue(text.contains("after"))
    }

    @Test
    fun `ring is capped so a run of large ERROR entries cannot grow unbounded`() {
        val e = newEngine(LogLevel.ON)
        val bigTrace = "x".repeat(10_000)
        repeat(50) { e.log(Severity.ERROR, LogCat.PLAYBACK) { bigTrace } }
        val ring = e.ringSnapshot()
        val totalBytes = ring.sumOf { it.toByteArray(Charsets.UTF_8).size }
        assertTrue("ring must stay bounded even under a run of large entries, was $totalBytes bytes", totalBytes <= 128 * 1024)
    }

    // ── Phase 2: segmented retention ────────────────────────────────────────────────────────

    @Test
    fun `rolling past the segment cap produces a gzipped segment and a fresh live log`() {
        val e = newEngine(LogLevel.ON)
        val bigLine = "x".repeat(600_000) // safely over the 512KB segment cap on its own
        e.log(Severity.INFO, LogCat.SYSTEM) { bigLine }
        e.log(Severity.INFO, LogCat.SYSTEM) { "after roll" }
        e.flush()

        val segFiles = dir.listFiles { f -> f.name.startsWith("seg-") } ?: emptyArray()
        assertTrue("expected at least one closed segment", segFiles.isNotEmpty())
        assertTrue("closed segments should be gzipped", segFiles.all { it.name.endsWith(".log.gz") })

        val text = e.recentText(2_000_000)
        assertTrue("live.log must be usable again after the roll", text.contains("after roll"))
        assertTrue("the rolled content must still be readable back from the segment", text.contains(bigLine))
    }

    @Test
    fun `eviction deletes the oldest segments to stay under budget`() {
        // A tiny budget forces eviction on every roll — easier to assert on than timing a real
        // multi-MB budget in a unit test.
        val e = LogEngine(dir, LogLevel.ON, initialBudgetMb = 0.5f).also { it.start(); engine = it }
        val bigLine = "y".repeat(600_000)
        // Each of these forces at least one roll; by the last one, early segments should have
        // been evicted to stay near the 0.5MB budget.
        repeat(4) { i -> e.log(Severity.INFO, LogCat.SYSTEM) { "$bigLine-$i" } }
        e.flush()

        val totalOnDisk = (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.name.startsWith("seg-") || it.name == "live.log" }
            .sumOf { it.length() }
        // Some slack: eviction only runs after a roll completes, so the segment that triggered it
        // is briefly present. Still should be well below repeating all 4 uncompressed (2.4MB+).
        assertTrue("expected eviction to keep the directory well under the unbounded size, was $totalOnDisk bytes", totalOnDisk < 1_800_000)
    }

    @Test
    fun `a shrinking budget change evicts immediately, not just on the next roll`() {
        val e = LogEngine(dir, LogLevel.ON, initialBudgetMb = 20f).also { it.start(); engine = it }
        val bigLine = "z".repeat(600_000)
        repeat(3) { i -> e.log(Severity.INFO, LogCat.SYSTEM) { "$bigLine-$i" } }
        e.flush()
        val beforeCount = dir.listFiles { f -> f.name.startsWith("seg-") }?.size ?: 0
        assertTrue(beforeCount > 0)

        e.setBudgetMb(0.5f)

        val afterTotal = (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.name.startsWith("seg-") || it.name == "live.log" }
            .sumOf { it.length() }
        assertTrue("setBudgetMb must evict immediately, was $afterTotal bytes", afterTotal < 1_000_000)
    }

    @Test
    fun `startup sweep migrates legacy files and removes the stale share export`() {
        val e = newEngine(LogLevel.ON)
        e.shutdown()
        File(dir, "app.log").writeText("legacy content")
        File(dir, "app.1.log").writeText("legacy backup")
        File(dir, "voyage-log.txt").writeText("stale export")
        // An orphan plain segment, as a process killed mid-gzip would leave behind.
        File(dir, "seg-1-2.log").writeText("orphaned segment content")

        val e2 = LogEngine(dir, LogLevel.ON).also { it.start(); engine = it }
        e2.runStartupSweep()

        assertFalse(File(dir, "app.log").exists())
        assertFalse(File(dir, "app.1.log").exists())
        assertFalse(File(dir, "voyage-log.txt").exists())
        assertFalse("orphan plain segment should be gzipped or removed", File(dir, "seg-1-2.log").exists())
    }

    @Test
    fun `share bundle redacts paths by default and leaves them when asked for raw`() {
        val e = newEngine(LogLevel.ON)
        e.log(Severity.INFO, LogCat.SCAN) { "Scan done path=/storage/emulated/0/Audiobooks/TheHobbit" }
        e.flush()

        val redactedZip = File(dir, "bundle-redacted.zip")
        e.buildShareBundle(redactedZip, redactPaths = true)
        val redactedText = readZipText(redactedZip)
        assertFalse(redactedText.contains("/storage"))
        assertTrue(redactedText.contains("[PATH]"))

        val rawZip = File(dir, "bundle-raw.zip")
        e.buildShareBundle(rawZip, redactPaths = false)
        val rawText = readZipText(rawZip)
        assertTrue(rawText.contains("/storage/emulated/0/Audiobooks/TheHobbit"))
    }

    @Test
    fun `stats reports bytes on disk and a plausible coverage window`() {
        val e = newEngine(LogLevel.ON)
        e.log(Severity.INFO, LogCat.SYSTEM) { "hello" }
        e.flush()
        val stats = e.stats()
        assertTrue(stats.bytesOnDisk > 0)
        assertTrue(stats.coverageMs >= 0)
    }

    // ── Phase 10: roll-failure recovery (blocker 2), via fault injection ───────────────────────

    @Test
    fun `a roll that fails to open the new writer recovers on a later write`() {
        var callCount = 0
        // Fails only the SECOND open (the fresh live.log the roll tries to open after closing the
        // old one) — the first call is start()'s own initial open, which must succeed so the
        // engine is usable at all.
        val e = LogEngine(dir, LogLevel.ON, writerFactory = { file, append ->
            callCount++
            if (callCount == 2) throw java.io.IOException("simulated ENOSPC")
            java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(file, append), Charsets.UTF_8))
        }).also { it.start(); engine = it }

        val bigLine = "x".repeat(600_000) // forces a roll on the very next write
        e.log(Severity.INFO, LogCat.SYSTEM) { bigLine }
        // The roll's reopen (callCount==2) threw, so the recovery path's OWN reopen attempt
        // (callCount==3, using the real writer again) is what must leave the engine usable.
        e.log(Severity.INFO, LogCat.SYSTEM) { "still alive" }
        e.flush()

        val text = e.recentText(2_000_000)
        assertTrue("writer must recover and accept new lines after a failed roll", text.contains("still alive"))
        assertTrue("the failure must be recorded, not silently swallowed", text.contains("log roll failed"))
    }

    @Test
    fun `a roll that fails entirely (no writer at all) leaves the engine degraded but not crashing`() {
        var callCount = 0
        val e = LogEngine(dir, LogLevel.ON, writerFactory = { file, append ->
            callCount++
            // Fail every open from the second one on — simulates a volume that's gone entirely
            // unwritable partway through the session (e.g. an SD card yanked out).
            if (callCount >= 2) throw java.io.IOException("simulated permanent failure")
            java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(file, append), Charsets.UTF_8))
        }).also { it.start(); engine = it }

        val bigLine = "x".repeat(600_000)
        // None of these may throw out of log()/flush() even though every underlying write now fails.
        e.log(Severity.INFO, LogCat.SYSTEM) { bigLine }
        e.log(Severity.INFO, LogCat.SYSTEM) { "after permanent failure" }
        e.flush()
        // No assertion on content — the point is that the process is still alive and responsive.
    }

    private fun readZipText(zip: File): String {
        val sb = StringBuilder()
        java.util.zip.ZipInputStream(zip.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                sb.append(zis.readBytes().toString(Charsets.UTF_8))
                entry = zis.nextEntry
            }
        }
        return sb.toString()
    }
}
