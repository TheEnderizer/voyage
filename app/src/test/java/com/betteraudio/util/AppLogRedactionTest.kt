package com.betteraudio.util

import com.betteraudio.util.log.LogCat
import com.betteraudio.util.log.LogEngine
import com.betteraudio.util.log.LogLevel
import com.betteraudio.util.log.Severity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * app.log is offered for sharing from Settings → Diagnostics, so a Gemini API key must never
 * reach it in ANY form — including inside the in-memory breadcrumb ring, which a crash dumps
 * verbatim into its own file via AppLog's crash handler (see [LogEngine.log]'s redaction, which
 * happens once, on the caller, before the message reaches either the ring or the write queue).
 *
 * Absolute paths, by contrast, are DELIBERATELY left in place — the decided design is "full paths
 * stay on-device, scrubbed only in the Copy/Share output" (see Phase 2's plan), so this suite no
 * longer asserts paths are redacted at write time; the opposite is now the intended behavior.
 *
 * Exercises [LogEngine] directly against a temp dir rather than the [AppLog] singleton facade —
 * this is exactly what extracting the engine into its own injectable class (Phase 1) was for.
 */
class AppLogRedactionTest {
    private lateinit var dir: File
    private lateinit var engine: LogEngine

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("applog-test").toFile()
        engine = LogEngine(dir, LogLevel.ON)
        engine.start()
    }

    @After
    fun tearDown() {
        engine.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun `redacts a Gemini API key at write time`() {
        engine.log(Severity.INFO, LogCat.NET) {
            "Synopsis request failed url=https://x?key=AIzaSyD-9tSrke7dLdRZa1kMj7NIJRR2Pl-jNs4"
        }
        engine.flush()
        val text = engine.recentText(200_000)
        assertFalse("key leaked into the log", text.contains("AIzaSy"))
        assertTrue(text.contains("[REDACTED_KEY]"))
    }

    @Test
    fun `redacts a Gemini API key in the breadcrumb ring, which a crash dumps verbatim`() {
        engine.log(Severity.ERROR, LogCat.NET) {
            "request failed key=AIzaSyD-9tSrke7dLdRZa1kMj7NIJRR2Pl-jNs4"
        }
        val ring = engine.ringSnapshot()
        assertTrue(ring.isNotEmpty())
        assertFalse(ring.any { it.contains("AIzaSy") })
    }

    @Test
    fun `leaves an absolute library path untouched`() {
        engine.log(Severity.INFO, LogCat.SCAN) {
            "Scan done path=/storage/emulated/0/Audiobooks/TheHobbit imported=1"
        }
        engine.flush()
        val text = engine.recentText(200_000)
        assertTrue(
            "paths are meant to stay on-device — see the class doc",
            text.contains("/storage/emulated/0/Audiobooks/TheHobbit")
        )
    }

    @Test
    fun `leaves an ordinary message untouched`() {
        engine.log(Severity.INFO, LogCat.PLAYBACK) { "isPlaying=true book=5 pos=5ms" }
        engine.flush()
        val text = engine.recentText(200_000)
        assertTrue(text.contains("isPlaying=true book=5 pos=5ms"))
    }
}
