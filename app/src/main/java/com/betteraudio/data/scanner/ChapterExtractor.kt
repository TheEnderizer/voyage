package com.betteraudio.data.scanner

import java.io.RandomAccessFile

/** A chapter marker parsed from an audio file's embedded metadata. */
data class RawChapter(val title: String, val startMs: Long)

/**
 * Extracts embedded chapter markers from MP4-family audio files (M4B/M4A/MP4) by
 * reading the Nero `chpl` atom located at `moov/udta/chpl`. This is the format the
 * vast majority of audiobook M4B files use.
 *
 * Pure I/O + byte parsing, no external dependencies. Returns an empty list for files
 * with no embedded chapters (or anything that isn't a parseable MP4 container), so
 * callers can fall back to one-chapter-per-file.
 */
object ChapterExtractor {

    private val MP4_EXTS = setOf("m4b", "m4a", "mp4", "aac")

    fun extract(filePath: String, extension: String): List<RawChapter> {
        if (extension.lowercase() !in MP4_EXTS) return emptyList()
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val moov = findBox(raf, 0L, raf.length(), "moov") ?: return emptyList()
                val udta = findBox(raf, moov.contentStart, moov.end, "udta") ?: return emptyList()
                val chpl = findBox(raf, udta.contentStart, udta.end, "chpl") ?: return emptyList()
                parseChpl(raf, chpl)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class Box(val type: String, val contentStart: Long, val end: Long)

    /** Scan sibling boxes in [start, limit) for one whose type == [type]. */
    private fun findBox(raf: RandomAccessFile, start: Long, limit: Long, type: String): Box? {
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
            if (t == type) return Box(t, pos + headerLen, minOf(end, limit))
            pos = end
        }
        return null
    }

    private fun readType(raf: RandomAccessFile): String {
        val b = ByteArray(4)
        raf.readFully(b)
        return String(b, Charsets.US_ASCII)
    }

    /**
     * Nero `chpl` full-box layout:
     *   u8 version, u24 flags, [u32 reserved if version != 0], u8 chapterCount,
     *   then per chapter: u64 startTime (100-ns units), u8 titleLen, titleLen bytes (UTF-8).
     */
    private fun parseChpl(raf: RandomAccessFile, box: Box): List<RawChapter> {
        raf.seek(box.contentStart)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3) // flags
        if (version != 0) raf.skipBytes(4) // reserved
        val count = raf.readUnsignedByte()
        val result = ArrayList<RawChapter>(count)
        repeat(count) {
            if (raf.filePointer + 9 > box.end) return@repeat
            val start100ns = raf.readLong()
            val titleLen = raf.readUnsignedByte()
            val titleBytes = ByteArray(titleLen)
            raf.readFully(titleBytes)
            val title = String(titleBytes, Charsets.UTF_8).trim()
            result.add(RawChapter(title.ifBlank { "Chapter ${it + 1}" }, start100ns / 10_000L))
        }
        return result.sortedBy { it.startMs }
    }
}
