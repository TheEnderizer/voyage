package com.betteraudio.data.files

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.betteraudio.data.scanner.ChapterExtractor
import com.betteraudio.data.scanner.Mp4Probe
import com.betteraudio.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Splits an over-large single-file audiobook into per-chapter parts **without re-encoding**.
 *
 * ExoPlayer's `Mp4Extractor` builds the whole sample table on the Java heap (see [Mp4Probe]), so a
 * 261 h / 22M-frame m4b can never be played whole. Android's `MediaExtractor`/`MediaMuxer` are
 * native and keep those tables off-heap, so they can read such a file and copy its AAC frames out
 * verbatim — same bytes, same codec, just chopped into playable pieces.
 *
 * The original file is kept, renamed to `<name>.<ext>.original` so the scanner ignores it
 * (`original` is not in `AUDIO_EXTENSIONS`) rather than importing the book twice.
 */
@Singleton
class LargeAudioSplitter @Inject constructor() {

    /** A planned output part: `[startUs, endUs)` of the source. */
    data class Part(val index: Int, val title: String, val startUs: Long, val endUs: Long)

    sealed interface Progress {
        data object Idle : Progress
        data class Running(val partsDone: Int, val partsTotal: Int, val fraction: Float) : Progress
        data class Done(val partsWritten: Int) : Progress
        data class Failed(val message: String) : Progress
    }

    private val _progress = MutableStateFlow<Progress>(Progress.Idle)
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    companion object {
        /**
         * Offer to split above this many samples. 8M frames ≈ 192 MB of ExoPlayer sample-table
         * arrays, which already needs `android:largeHeap`. Tune against real devices.
         */
        const val SPLIT_SUGGEST_SAMPLES = 8_000_000L

        /** No part may exceed this. Also the fallback slice length when there are no chapters. */
        private const val MAX_PART_US = 60L * 60 * 1_000_000   // 60 min

        private const val SUFFIX = "original"
    }

    fun shouldSuggestSplit(filePath: String, extension: String): Boolean =
        Mp4Probe.sampleCount(filePath, extension) > SPLIT_SUGGEST_SAMPLES

    /**
     * Plan the parts for [source] without touching anything — drives the confirmation dialog.
     * Uses embedded chapter marks when present, else fixed [MAX_PART_US] slices.
     */
    fun plan(source: File, durationUs: Long): List<Part> {
        if (durationUs <= 0L) return emptyList()
        val chapters = ChapterExtractor.extract(source.absolutePath, source.extension)

        // Nero `chpl` stores its chapter count in a u8, so a book with >255 chapters yields a
        // truncated list that stops well short of the end. Never trust the markers to span the
        // file: build the boundaries, then subdivide anything longer than MAX_PART_US.
        val marks = chapters
            .map { it.startMs * 1_000L to it.title }
            .filter { it.first in 0 until durationUs }
            .distinctBy { it.first }
            .sortedBy { it.first }

        val bounds = if (marks.isEmpty()) listOf(0L to "") else marks
        val out = ArrayList<Part>()
        for ((i, mark) in bounds.withIndex()) {
            val (start, title) = mark
            val end = bounds.getOrNull(i + 1)?.first ?: durationUs
            var cursor = start
            var sub = 0
            while (cursor < end) {
                val stop = minOf(cursor + MAX_PART_US, end)
                val n = out.size + 1
                val label = when {
                    title.isBlank() -> "Part %02d".format(n)
                    sub == 0 -> title
                    else -> "$title (${sub + 1})"
                }
                out.add(Part(n, label, cursor, stop))
                cursor = stop
                sub++
            }
        }
        return out
    }

    /** Bytes of free space needed: the copy is verbatim, so ~the source's own size. */
    fun hasEnoughFreeSpace(source: File): Boolean =
        (source.parentFile?.usableSpace ?: 0L) > source.length() + 64L * 1024 * 1024

    /**
     * Perform the split. Returns the files written, or throws. Cancellable; on failure or
     * cancellation, any partially-written outputs are deleted and the source is left untouched.
     */
    suspend fun split(source: File, durationUs: Long): List<File> = withContext(Dispatchers.IO) {
        _progress.value = Progress.Running(0, 0, 0f)
        val written = ArrayList<File>()
        try {
            if (!hasEnoughFreeSpace(source)) {
                error("Not enough free space — splitting needs about ${source.length() / (1024 * 1024)} MB.")
            }
            val parts = plan(source, durationUs)
            if (parts.isEmpty()) error("Could not work out where to split this file.")
            AppLog.i("Split", "splitting ${source.name} into ${parts.size} parts (${durationUs / 1_000_000}s)")

            val folder = source.parentFile ?: error("File has no parent folder.")
            val stem = source.nameWithoutExtension

            parts.forEachIndexed { i, part ->
                coroutineContext.ensureActive()
                _progress.value = Progress.Running(i, parts.size, i.toFloat() / parts.size)
                val target = File(folder, "%04d - %s.m4a".format(part.index, part.title.safeFileName()))
                writePart(source, target, part)
                written.add(target)
            }

            // Only once every part is on disk: retire the source so the scanner sees the parts and
            // not the (unplayable) original. Kept, not deleted.
            val retired = File(folder, "${source.name}.$SUFFIX")
            if (!source.renameTo(retired)) {
                AppLog.e("Split", "could not rename source to ${retired.name}; parts written anyway", null)
            }

            _progress.value = Progress.Done(written.size)
            AppLog.i("Split", "done: ${written.size} parts from ${source.name} (stem=$stem)")
            written
        } catch (e: Throwable) {
            written.forEach { runCatching { it.delete() } }
            if (e is kotlinx.coroutines.CancellationException) {
                _progress.value = Progress.Idle
                throw e
            }
            AppLog.e("Split", "split failed for ${source.absolutePath}", e as? Exception)
            _progress.value = Progress.Failed(e.message ?: "Split failed")
            throw e
        }
    }

    /** Copy `[part.startUs, part.endUs)` of the source's audio track into [target], frame for frame. */
    private suspend fun writePart(source: File, target: File, part: Part) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in ${source.name}")

            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)
            // Every AAC frame is a sync frame, so the cut lands exactly on the requested boundary.
            extractor.seekTo(part.startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTrack = muxer.addTrack(format)
            muxer.start()

            val maxInput = format.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                ?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) ?: (256 * 1024)
            val buffer = ByteBuffer.allocateDirect(maxInput.coerceAtLeast(64 * 1024))
            val info = MediaCodec.BufferInfo()

            while (true) {
                coroutineContext.ensureActive()
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime >= part.endUs) break

                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break

                info.offset = 0
                info.size = size
                info.presentationTimeUs = sampleTime - part.startUs   // rebase each part to zero
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outTrack, buffer, info)

                if (!extractor.advance()) break
            }
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun String.safeFileName(): String =
        replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "Part" }
}
