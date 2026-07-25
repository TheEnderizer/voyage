package com.betteraudio.data.scanner

import com.betteraudio.util.AppLog
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
 *
 * Box walking lives in [Mp4Boxes] (64-bit safe — audiobook m4b files exceed 2 GB routinely).
 */
object ChapterExtractor {

    fun extract(filePath: String, extension: String): List<RawChapter> {
        if (extension.lowercase() !in Mp4Boxes.MP4_EXTS) return emptyList()
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val chpl = Mp4Boxes.findPath(raf, "moov", "udta", "chpl") ?: return emptyList()
                parseChpl(raf, chpl)
            }
        } catch (e: Exception) {
            AppLog.e("Chapters", "chpl parse failed for $filePath", e)
            emptyList()
        }
    }

    /**
     * Nero `chpl` full-box layout:
     *   u8 version, u24 flags, [u32 reserved if version != 0], u8 chapterCount,
     *   then per chapter: u64 startTime (100-ns units), u8 titleLen, titleLen bytes (UTF-8).
     *
     * Note the count is a **u8**, so a book with more than 255 chapters yields a truncated list
     * whose last entry does not reach the end of the audio. Callers that derive spans from these
     * markers must not assume they cover the whole file.
     */
    private fun parseChpl(raf: RandomAccessFile, box: Mp4Box): List<RawChapter> {
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
