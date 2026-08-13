package com.betteraudio.playback

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A Media3 [ProgressiveMediaExtractor] that demuxes with the platform-native
 * [android.media.MediaExtractor] instead of Media3's own `Mp4Extractor`.
 *
 * Why this exists: `Mp4Extractor` (and, as it turns out, `android.media.MediaParser`, which just
 * bundles a Java copy of the same code) builds the whole file's sample table — offset/size/
 * timestamp/flags per frame — on the Java heap, ~24 bytes/sample. A 261 h / 22M-frame audiobook
 * needs ~505 MB and OOMs no matter the heap size. `android.media.MediaExtractor` keeps that table
 * in native/stagefright memory and hands out one sample at a time, so the same file loads fine —
 * this is exactly the API `data/files/LargeAudioSplitter` used to rely on, now driven live.
 *
 * Only the demux is native; the samples are fed into ExoPlayer's ordinary [TrackOutput] pipeline,
 * so all the usual buffering, LoadControl back-pressure, seeking and renderer machinery is reused.
 *
 * Back-pressure hinges on [getCurrentInputPosition] advancing as bytes are read — the loading task
 * uses its growth to decide when to yield to LoadControl. Returning a constant would make it buffer
 * the entire file (re-OOM), so we report cumulative sample bytes.
 */
@UnstableApi
class MediaExtractorProgressiveExtractor : ProgressiveMediaExtractor {

    companion object {
        val FACTORY = ProgressiveMediaExtractor.Factory { _: PlayerId ->
            MediaExtractorProgressiveExtractor()
        }
        private const val MIN_BUFFER_BYTES = 256 * 1024
    }

    private var extractor: MediaExtractor? = null
    private var trackOutput: TrackOutput? = null
    private var durationUs = C.TIME_UNSET
    private var bytesRead = 0L

    private var readBuffer: ByteBuffer = ByteBuffer.allocateDirect(MIN_BUFFER_BYTES)
    private var scratch = ByteArray(MIN_BUFFER_BYTES)
    private val parsable = ParsableByteArray()

    // init() is called on every (re)start of the load task, always on the same instance. We build
    // the native extractor and register the track once; later calls reuse it (the framework issues
    // a seek() next when it needs to reposition), avoiding a costly native re-open per seek.
    override fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: MutableMap<String, MutableList<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput
    ) {
        if (extractor != null) return

        val path = uri.path ?: throw IOException("No file path in $uri")
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
        } catch (e: Exception) {
            ex.release()
            AppLog.w(LogCat.PLAYBACK, "MediaExtractorProgressiveExtractor: native setDataSource failed for $path: ${e.message}")
            throw IOException("MediaExtractor could not open $path", e)
        }
        val trackIndex = (0 until ex.trackCount).firstOrNull { i ->
            ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            val trackCount = ex.trackCount // read before release() — MediaExtractor state is undefined after
            ex.release()
            AppLog.w(LogCat.PLAYBACK, "MediaExtractorProgressiveExtractor: no audio track among $trackCount track(s) in $path")
            throw IOException("No audio track in $path")
        }

        val mediaFormat = ex.getTrackFormat(trackIndex)
        ex.selectTrack(trackIndex)
        durationUs = if (mediaFormat.containsKey(MediaFormat.KEY_DURATION)) {
            mediaFormat.getLong(MediaFormat.KEY_DURATION)
        } else {
            C.TIME_UNSET
        }

        val maxInput = if (mediaFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            MIN_BUFFER_BYTES
        }.coerceAtLeast(MIN_BUFFER_BYTES)
        readBuffer = ByteBuffer.allocateDirect(maxInput)
        scratch = ByteArray(maxInput)

        val track = output.track(0, C.TRACK_TYPE_AUDIO)
        track.format(buildFormat(mediaFormat))
        output.endTracks()
        output.seekMap(TimeOnlySeekMap(durationUs))

        extractor = ex
        trackOutput = track
        AppLog.d(LogCat.PLAYBACK) { "MediaExtractorProgressiveExtractor: opened $path track=$trackIndex durationUs=$durationUs bufferBytes=${readBuffer.capacity()}" }
    }

    override fun read(positionHolder: PositionHolder): Int {
        val ex = extractor ?: return Extractor.RESULT_END_OF_INPUT
        val track = trackOutput ?: return Extractor.RESULT_END_OF_INPUT

        readBuffer.clear()
        val sampleSize = ex.readSampleData(readBuffer, 0)
        if (sampleSize < 0) return Extractor.RESULT_END_OF_INPUT

        val timeUs = ex.sampleTime
        val sampleFlags = ex.sampleFlags

        if (scratch.size < sampleSize) scratch = ByteArray(sampleSize)
        readBuffer.position(0)
        readBuffer.get(scratch, 0, sampleSize)
        parsable.reset(scratch, sampleSize)
        track.sampleData(parsable, sampleSize)

        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) flags = flags or C.BUFFER_FLAG_KEY_FRAME
        track.sampleMetadata(timeUs, flags, sampleSize, 0, null)

        bytesRead += sampleSize
        ex.advance()
        return Extractor.RESULT_CONTINUE
    }

    override fun seek(position: Long, timeUs: Long) {
        // Seek by time; MediaExtractor lands on the closest sync sample (every AAC frame is a sync
        // sample, so this is frame-accurate). The byte [position] is meaningless here and ignored.
        extractor?.seekTo(timeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    }

    override fun getCurrentInputPosition(): Long = bytesRead

    override fun disableSeekingOnMp3Streams() { /* Not an MP3 path. */ }

    override fun release() {
        try {
            extractor?.release()
        } catch (_: Throwable) {
        }
        extractor = null
        trackOutput = null
    }

    private fun buildFormat(mediaFormat: MediaFormat): Format {
        val builder = Format.Builder()
            .setSampleMimeType(mediaFormat.getString(MediaFormat.KEY_MIME))
        if (mediaFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            builder.setChannelCount(mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        }
        if (mediaFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            builder.setSampleRate(mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
        }
        // Codec-specific config (AAC AudioSpecificConfig etc.) the decoder needs to initialise.
        val csd = ArrayList<ByteArray>()
        var i = 0
        while (true) {
            val key = "csd-$i"
            if (!mediaFormat.containsKey(key)) break
            val buffer = mediaFormat.getByteBuffer(key) ?: break
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            buffer.rewind()
            csd.add(bytes)
            i++
        }
        if (csd.isNotEmpty()) builder.setInitializationData(csd)
        return builder.build()
    }

    /** Seeking is done inside MediaExtractor by time, so every target time maps to itself. */
    private class TimeOnlySeekMap(private val durationUs: Long) : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
            SeekMap.SeekPoints(SeekPoint(timeUs, 0L))
    }
}
