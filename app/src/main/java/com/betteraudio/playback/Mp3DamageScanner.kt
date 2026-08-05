package com.betteraudio.playback

import com.betteraudio.util.AppLog
import java.io.File
import java.io.RandomAccessFile

/**
 * Finds runs of bytes in an MP3 that are not valid MPEG audio frames — the damage left by an
 * incomplete or corrupted download.
 *
 * Why this exists: ExoPlayer's `Mp3Extractor` searches at most `MAX_SYNC_BYTES` (**128 KB**) for
 * the next valid frame header before giving up with ERROR_CODE_PARSING_CONTAINER_MALFORMED, and a
 * damaged audiobook routinely carries several hundred KB of garbage per gap — verified on a real
 * file with 9 gaps of ~300 KB each, spread evenly through 7.8 hours. It can never resync at ANY of
 * them, so playback dies a fraction of a second in. Android's own `MediaExtractor` was measured to
 * stop at the same point, so switching demuxer is not a way out either.
 *
 * The ranges found here are handed to [GapSkippingDataSource], which hides them from the extractor
 * so it only ever sees one continuous valid stream. Nothing is rewritten on disk and no audio is
 * re-encoded — the skipped bytes are unrecoverable garbage regardless; this only stops them from
 * halting playback.
 *
 * Scanning is a full sequential read, so it is deliberately a one-time, on-demand operation (run
 * once after a file actually fails, then cached in `AudioFile.damageRangesJson`), never part of a
 * library scan.
 */
object Mp3DamageScanner {

    /** A half-open physical byte range `[start, end)` that is not decodable audio. */
    data class Gap(val start: Long, val end: Long) {
        val size: Long get() = end - start
    }

    private const val READ_CHUNK = 1 shl 20            // 1 MB working window
    private const val OVERLAP = 64 * 1024              // so a frame straddling a chunk edge is seen
    /** Runs shorter than this are ordinary sync slop the extractor already tolerates. */
    private const val MIN_REPORTABLE_GAP = 8L
    /** Safety valve: a file this broken is beyond patching up. */
    private const val MAX_GAPS = 512

    private val BITRATES_V1L3 = intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320,-1)
    private val BITRATES_V2L3 = intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160,-1)
    private val SR_V1 = intArrayOf(44100,48000,32000,-1)
    private val SR_V2 = intArrayOf(22050,24000,16000,-1)
    private val SR_V25 = intArrayOf(11025,12000,8000,-1)

    /** Size of a leading ID3v2 tag (header + body [+ footer]), or 0 when absent/unreadable. */
    fun id3TagSizeBytes(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            val h = ByteArray(10)
            if (raf.read(h) != 10 || h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() ||
                h[2] != '3'.code.toByte()
            ) 0L
            else {
                val size = ((h[6].toLong() and 0x7f) shl 21) or ((h[7].toLong() and 0x7f) shl 14) or
                    ((h[8].toLong() and 0x7f) shl 7) or (h[9].toLong() and 0x7f)
                size + 10L + (if (h[5].toInt() and 0x10 != 0) 10L else 0L)
            }
        }
    } catch (_: Throwable) { 0L }

    /**
     * Frame length in bytes if a valid MPEG-1/2/2.5 Layer III header starts at [off], else -1.
     * Layer III only: these are MP3s, and accepting other layers would make false syncs likelier.
     */
    private fun frameLengthAt(b: ByteArray, off: Int, limit: Int): Int {
        if (off + 4 > limit) return -1
        if (b[off] != 0xFF.toByte() || (b[off + 1].toInt() and 0xE0) != 0xE0) return -1
        val ver = (b[off + 1].toInt() shr 3) and 0x03      // 0=2.5, 2=2, 3=1 (1 is reserved)
        val layer = (b[off + 1].toInt() shr 1) and 0x03    // 1 = Layer III
        if (ver == 1 || layer != 1) return -1
        val bri = (b[off + 2].toInt() shr 4) and 0x0F
        val sri = (b[off + 2].toInt() shr 2) and 0x03
        val pad = (b[off + 2].toInt() shr 1) and 0x01
        if (bri == 0 || bri == 15 || sri == 3) return -1
        val sr: Int; val br: Int; val spf: Int
        when (ver) {
            3 -> { sr = SR_V1[sri]; br = BITRATES_V1L3[bri]; spf = 1152 }
            2 -> { sr = SR_V2[sri]; br = BITRATES_V2L3[bri]; spf = 576 }
            else -> { sr = SR_V25[sri]; br = BITRATES_V2L3[bri]; spf = 576 }
        }
        if (sr <= 0 || br <= 0) return -1
        val len = (spf / 8) * br * 1000 / sr + pad
        return if (len >= 24) len else -1
    }

    /** True when [n] valid frames chain end-to-end from [off] — guards against false syncs. */
    private fun chainsFrom(b: ByteArray, off: Int, limit: Int, n: Int = 4): Boolean {
        var p = off
        repeat(n) {
            val l = frameLengthAt(b, p, limit)
            if (l < 0) return false
            p += l
        }
        return true
    }

    /**
     * Scans [path] and returns the damaged ranges, in ascending order and non-overlapping.
     * Empty means the file is clean (or unreadable — callers treat both as "nothing to skip").
     *
     * [shouldContinue] is polled between chunks so a cancelled scan stops promptly instead of
     * reading the rest of a multi-hundred-MB file.
     */
    fun scan(path: String, shouldContinue: () -> Boolean = { true }): List<Gap> {
        val file = File(path)
        if (!file.isFile) return emptyList()
        val size = file.length()
        if (size <= 0L) return emptyList()

        val gaps = ArrayList<Gap>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val tag = id3TagSizeBytes(file)
                // Everything before the first frame is metadata, not damage — never report it.
                var pos = tag
                var bufBase = tag
                var buf = ByteArray(READ_CHUNK)
                var filled = readAt(raf, bufBase, buf)
                if (filled <= 0) return emptyList()

                while (pos < size) {
                    if (!shouldContinue()) return emptyList()
                    var rel = (pos - bufBase).toInt()
                    // Refill when close to the end of the window so frame/gap detection never
                    // straddles the boundary unseen.
                    if (rel > filled - OVERLAP) {
                        bufBase = pos
                        filled = readAt(raf, bufBase, buf)
                        if (filled <= 0) break
                        rel = 0
                    }
                    val len = frameLengthAt(buf, rel, filled)
                    if (len > 0) { pos += len; continue }

                    // Desync: hunt forward for a solid chain, refilling as needed.
                    val badStart = pos
                    var resync = -1L
                    var searchBase = bufBase
                    var searchBuf = buf
                    var searchFilled = filled
                    var j = rel
                    while (true) {
                        while (j < searchFilled - 4) {
                            if (searchBuf[j] == 0xFF.toByte() &&
                                (searchBuf[j + 1].toInt() and 0xE0) == 0xE0 &&
                                chainsFrom(searchBuf, j, searchFilled)
                            ) { resync = searchBase + j; break }
                            j++
                        }
                        if (resync >= 0) break
                        val next = searchBase + maxOf(searchFilled - OVERLAP, 1)
                        if (next >= size || !shouldContinue()) break
                        searchBase = next
                        searchFilled = readAt(raf, searchBase, searchBuf)
                        if (searchFilled <= 0) break
                        j = 0
                    }
                    if (resync < 0) {
                        // Damage runs to EOF — trailing ID3v1/APE tags land here too, which is
                        // correct: they are not audio and the extractor should not see them.
                        if (size - badStart >= MIN_REPORTABLE_GAP) gaps.add(Gap(badStart, size))
                        break
                    }
                    if (resync - badStart >= MIN_REPORTABLE_GAP) gaps.add(Gap(badStart, resync))
                    if (gaps.size >= MAX_GAPS) {
                        AppLog.w("Damage", "scan of $path hit the $MAX_GAPS-gap cap; giving up")
                        return emptyList()
                    }
                    pos = resync
                    // Re-centre the main window on the resync point.
                    bufBase = resync
                    filled = readAt(raf, bufBase, buf)
                    if (filled <= 0) break
                }
            }
        } catch (e: Throwable) {
            AppLog.e("Damage", "scan failed for $path", e)
            return emptyList()
        }
        val total = gaps.sumOf { it.size }
        AppLog.i("Damage", "scanned $path: ${gaps.size} gap(s), $total bytes damaged")
        return gaps
    }

    private fun readAt(raf: RandomAccessFile, offset: Long, buf: ByteArray): Int {
        raf.seek(offset)
        var read = 0
        while (read < buf.size) {
            val n = raf.read(buf, read, buf.size - read)
            if (n <= 0) break
            read += n
        }
        return read
    }

    // ── Persistence format ────────────────────────────────────────────────────
    // "start-end,start-end,…" — compact enough to also ride in a URI query parameter, which is how
    // the ranges reach the playback service (see GapSkippingDataSource).

    fun encode(gaps: List<Gap>): String = gaps.joinToString(",") { "${it.start}-${it.end}" }

    fun decode(s: String?): List<Gap> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(',').mapNotNull { part ->
            val i = part.indexOf('-')
            if (i <= 0) return@mapNotNull null
            val a = part.substring(0, i).toLongOrNull() ?: return@mapNotNull null
            val b = part.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
            if (b > a) Gap(a, b) else null
        }.sortedBy { it.start }
    }
}
