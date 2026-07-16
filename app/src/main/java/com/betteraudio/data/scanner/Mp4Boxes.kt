package com.betteraudio.data.scanner

import java.io.RandomAccessFile

/** A located MP4 box: its payload spans `[contentStart, end)`. */
internal data class Mp4Box(val type: String, val contentStart: Long, val end: Long)

/**
 * Minimal, 64-bit-safe MPEG-4 box walking, shared by [ChapterExtractor] (Nero `chpl` chapters) and
 * [Mp4Probe] (sample counts). Everything is `Long`: audiobook m4b files routinely exceed 2 GB, and
 * a 7 GB file puts its `moov` *after* a `mdat` declared with a 64-bit `largesize`.
 */
internal object Mp4Boxes {

    val MP4_EXTS = setOf("m4b", "m4a", "mp4", "aac")

    /** Scan sibling boxes in `[start, limit)` for the first whose type == [type]. */
    fun findBox(raf: RandomAccessFile, start: Long, limit: Long, type: String): Mp4Box? {
        var pos = start
        while (pos + 8 <= limit) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val t = readType(raf)
            var headerLen = 8L
            when (size) {
                1L -> { size = raf.readLong(); headerLen = 16L }       // 64-bit largesize
                0L -> size = limit - pos                                // extends to end
            }
            if (size < headerLen) return null
            val end = pos + size
            if (t == type) return Mp4Box(t, pos + headerLen, minOf(end, limit))
            pos = end
        }
        return null
    }

    /** Walk a nested path of box types, e.g. `moov`, `trak`, `mdia`. Null if any hop is missing. */
    fun findPath(raf: RandomAccessFile, vararg path: String): Mp4Box? {
        var start = 0L
        var end = raf.length()
        var box: Mp4Box? = null
        for (type in path) {
            box = findBox(raf, start, end, type) ?: return null
            start = box.contentStart
            end = box.end
        }
        return box
    }

    fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }
}
