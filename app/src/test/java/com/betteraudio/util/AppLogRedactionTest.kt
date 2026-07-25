package com.betteraudio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * app.log is offered for sharing from Settings → Diagnostics, so anything written to it must
 * never contain a Gemini key or an absolute path that could identify the user's own files.
 */
class AppLogRedactionTest {

    @Test
    fun `redacts a Gemini API key`() {
        val msg = "Synopsis request failed url=https://x?key=AIzaSyD-9tSrke7dLdRZa1kMj7NIJRR2Pl-jNs4"
        val out = AppLog.redact(msg)
        assertFalse(out.contains("AIzaSy"))
        assertEquals("Synopsis request failed url=https://x?key=[REDACTED_KEY]", out)
    }

    @Test
    fun `redacts an absolute library path`() {
        val msg = "Scan done path=/storage/emulated/0/Audiobooks/TheHobbit imported=1"
        val out = AppLog.redact(msg)
        assertFalse(out.contains("/storage"))
        assertEquals("Scan done path=[PATH] imported=1", out)
    }

    @Test
    fun `a space in the final path segment only partially redacts`() {
        // Known limitation: the trailing segment can't safely include spaces (nothing bounds
        // where the path ends vs. the next word in the log line), so a folder name containing
        // a space only has its first word swallowed by the match. The directory structure
        // itself is still gone, which is the property that actually matters here.
        val msg = "Scan done path=/storage/emulated/0/Audiobooks/The Hobbit imported=1"
        val out = AppLog.redact(msg)
        assertFalse(out.contains("/storage"))
        assertEquals("Scan done path=[PATH] Hobbit imported=1", out)
    }

    @Test
    fun `leaves an ordinary message untouched`() {
        val msg = "isPlaying=true book=5 pos=5ms"
        assertEquals(msg, AppLog.redact(msg))
    }
}
