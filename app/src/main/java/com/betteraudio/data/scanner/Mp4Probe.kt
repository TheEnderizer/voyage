package com.betteraudio.data.scanner

import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import java.io.RandomAccessFile

/**
 * Cheap structural facts about an MP4/M4A/M4B file, read from box headers only (a handful of seeks,
 * no sample tables touched).
 *
 * Why this exists: ExoPlayer's `Mp4Extractor` builds an in-memory `TrackSampleTable` for the whole
 * file — `long[] offsets` + `int[] sizes` + `long[] timestampsUs` + `int[] flags`, i.e.
 * [BYTES_PER_SAMPLE] per sample. Very long single-file audiobooks blow past the heap: a 48 h book
 * (7.5M AAC frames) wants ~172 MB, and a 261 h one (22M frames) wants ~505 MB, which no heap size
 * will satisfy. Reading `stsz.sample_count` up front lets
 * [com.betteraudio.playback.LargeFileMediaSourceFactory] route such a file around `Mp4Extractor`
 * entirely instead of letting the player OOM.
 */
object Mp4Probe {

    /** ExoPlayer `TrackSampleTable`: 8 (offset) + 4 (size) + 8 (timestampUs) + 4 (flags). */
    const val BYTES_PER_SAMPLE = 24L

    /**
     * Total samples in the file's largest track (the audio track — a chapter `text` track has orders
     * of magnitude fewer). 0 when the file isn't a parseable MP4 or has no `stsz`.
     */
    fun sampleCount(filePath: String, extension: String): Long {
        if (extension.lowercase() !in Mp4Boxes.MP4_EXTS) return 0L
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val moov = Mp4Boxes.findBox(raf, 0L, raf.length(), "moov") ?: return 0L
                var best = 0L
                var cursor = moov.contentStart
                while (cursor < moov.end) {
                    val trak = Mp4Boxes.findBox(raf, cursor, moov.end, "trak") ?: break
                    val count = stszSampleCount(raf, trak)
                    if (count > best) best = count
                    cursor = trak.end
                }
                best
            }
        } catch (e: Exception) {
            AppLog.e(LogCat.PLAYBACK, "sampleCount failed for $filePath", e)
            0L
        }
    }

    /**
     * Duration in ms straight from `moov/mvhd`, or 0 if unreadable.
     *
     * `MediaMetadataRetriever` returns *no duration at all* for very large m4b files (observed on a
     * 7 GB / 261 h book: METADATA_KEY_DURATION comes back null without throwing). A zero duration is
     * corrosive — it collapses every embedded chapter to position 0 (`buildChapters` clamps with
     * `coerceIn(0, durationMs)`) and makes the player show 0:00 — so fall back to the container's
     * own header, which is always present and cheap to read.
     */
    fun durationMs(filePath: String, extension: String): Long {
        if (extension.lowercase() !in Mp4Boxes.MP4_EXTS) return 0L
        return try {
            RandomAccessFile(filePath, "r").use { raf ->
                val mvhd = Mp4Boxes.findPath(raf, "moov", "mvhd") ?: return 0L
                raf.seek(mvhd.contentStart)
                val version = raf.readUnsignedByte()
                raf.skipBytes(3)                                    // flags
                val timescale: Long
                val duration: Long
                if (version == 1) {
                    raf.skipBytes(16)                               // creation + modification (u64 each)
                    timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                    duration = raf.readLong()
                } else {
                    raf.skipBytes(8)                                // creation + modification (u32 each)
                    timescale = raf.readInt().toLong() and 0xFFFFFFFFL
                    duration = raf.readInt().toLong() and 0xFFFFFFFFL
                }
                if (timescale <= 0L || duration <= 0L) 0L else duration * 1000L / timescale
            }
        } catch (e: Exception) {
            AppLog.e(LogCat.PLAYBACK, "durationMs failed for $filePath", e)
            0L
        }
    }

    /** `trak > mdia > minf > stbl > stsz`, then the u32 `sample_count` at payload offset +8. */
    private fun stszSampleCount(raf: RandomAccessFile, trak: Mp4Box): Long {
        val mdia = Mp4Boxes.findBox(raf, trak.contentStart, trak.end, "mdia") ?: return 0L
        val minf = Mp4Boxes.findBox(raf, mdia.contentStart, mdia.end, "minf") ?: return 0L
        val stbl = Mp4Boxes.findBox(raf, minf.contentStart, minf.end, "stbl") ?: return 0L
        val stsz = Mp4Boxes.findBox(raf, stbl.contentStart, stbl.end, "stsz") ?: return 0L
        if (stsz.contentStart + 12 > stsz.end) return 0L
        raf.seek(stsz.contentStart + 8)   // skip version+flags (4) and sample_size (4)
        return raf.readInt().toLong() and 0xFFFFFFFFL
    }
}
