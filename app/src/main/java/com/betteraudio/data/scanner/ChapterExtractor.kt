package com.betteraudio.data.scanner

import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
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
                val chpl = Mp4Boxes.findPath(raf, "moov", "udta", "chpl")
                if (chpl == null) {
                    AppLog.d(LogCat.SCAN) { "no chpl atom in $filePath — falling back to one chapter per file" }
                    return emptyList()
                }
                val chapters = parseChpl(raf, chpl)
                AppLog.d(LogCat.SCAN) { "chpl: ${chapters.size} chapter(s) in $filePath" }
                chapters
            }
        } catch (e: Exception) {
            AppLog.e(LogCat.SCAN, "chpl parse failed for $filePath", e)
            emptyList()
        }
    }

    // Generous upper bound on a single chapter's start time, purely to detect box padding
    // misread as a further entry (a stray/garbage 64-bit value decodes to an enormous ms figure
    // far beyond this) — not a real per-file duration limit.
    private const val MAX_PLAUSIBLE_CHAPTER_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * Nero `chpl` full-box layout:
     *   u8 version, u24 flags, [u32 reserved if version != 0], u8 chapterCount,
     *   then per chapter: u64 startTime (100-ns units), u8 titleLen, titleLen bytes (UTF-8).
     *
     * The declared count is a **u8**, so a book with more than 255 chapters would report a
     * truncated count — this reads entries until [Mp4Box.end] instead of trusting it, treating
     * [declaredCount] as a lower-bound size hint only. Some writers pad the box after the last
     * real entry; each candidate entry's start time is sanity-checked (`0..MAX_PLAUSIBLE_CHAPTER_MS`)
     * before being accepted, so padding bytes decoded as a bogus giant timestamp stop the loop
     * instead of being synthesized into a garbage chapter.
     */
    private fun parseChpl(raf: RandomAccessFile, box: Mp4Box): List<RawChapter> {
        raf.seek(box.contentStart)
        val version = raf.readUnsignedByte()
        raf.skipBytes(3) // flags
        if (version != 0) raf.skipBytes(4) // reserved
        val declaredCount = raf.readUnsignedByte()
        val result = ArrayList<RawChapter>(declaredCount.coerceAtLeast(1))
        while (raf.filePointer + 9 <= box.end) {
            val start100ns = raf.readLong()
            val titleLen = raf.readUnsignedByte()
            if (raf.filePointer + titleLen > box.end) break  // malformed/padded tail — stop cleanly
            val titleBytes = ByteArray(titleLen)
            raf.readFully(titleBytes)
            val startMs = start100ns / 10_000L
            if (startMs < 0 || startMs > MAX_PLAUSIBLE_CHAPTER_MS) break
            val title = String(titleBytes, Charsets.UTF_8).trim()
            result.add(RawChapter(title.ifBlank { "Chapter ${result.size + 1}" }, startMs))
        }
        return result.sortedBy { it.startMs }
    }
}
