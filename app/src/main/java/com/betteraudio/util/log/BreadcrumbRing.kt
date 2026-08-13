package com.betteraudio.util.log

/**
 * Fixed-capacity ring of recent pre-formatted log lines, filled unconditionally by [LogEngine]
 * for INFO/WARN/ERROR regardless of the file-logging level (including OFF) — a crash or a caught
 * ERROR must carry a trail even when routine logging was never turned on. DEBUG lines only ever
 * reach this ring at VERBOSE, because [LogEngine.log] never even invokes the message lambda for
 * DEBUG below that level.
 *
 * Capped in BOTH entry count and total bytes: a crash/error line can embed a full stack trace
 * (`Log.getStackTraceString`), so a handful of ERROR breadcrumbs can be several KB each — an
 * entry-count-only cap could still be several MB. Each entry is truncated to [maxEntryBytes] and
 * the ring as a whole never holds more than [maxTotalBytes].
 *
 * Entries are pre-formatted strings assembled entirely on the calling thread before [add] is
 * called (see [LogEngine.log]) — this class only ever touches its own internal buffer, guarded by
 * its own lock, so [snapshot] is safe to call from the (separate, lock-free) crash handler.
 */
class BreadcrumbRing(
    private val maxEntries: Int = 300,
    private val maxEntryBytes: Int = 2 * 1024,
    private val maxTotalBytes: Int = 64 * 1024
) {
    private val lock = Any()
    private val entries = ArrayDeque<String>()
    private var totalBytes = 0

    fun add(line: String) {
        val truncated = truncateToBytes(line, maxEntryBytes)
        val bytes = truncated.toByteArray(Charsets.UTF_8).size
        synchronized(lock) {
            entries.addLast(truncated)
            totalBytes += bytes
            while ((entries.size > maxEntries || totalBytes > maxTotalBytes) && entries.isNotEmpty()) {
                val removed = entries.removeFirst()
                totalBytes -= removed.toByteArray(Charsets.UTF_8).size
            }
        }
    }

    /** Snapshot in chronological order (oldest first). */
    fun snapshot(): List<String> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear(); totalBytes = 0 }

    private fun truncateToBytes(s: String, maxBytes: Int): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return s
        // Truncate on a UTF-8 boundary rather than a raw byte cut, which could split a multi-byte
        // character and leave a corrupt tail when redecoded.
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + "…(truncated)"
    }
}
