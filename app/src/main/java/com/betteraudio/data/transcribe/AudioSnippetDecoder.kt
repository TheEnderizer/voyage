package com.betteraudio.data.transcribe

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.betteraudio.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a time window of an arbitrary audio file (mp3/m4a/m4b/flac/ogg/… — anything the platform
 * codecs play) into 16 kHz mono 16-bit PCM, the format Vosk expects. Pure MediaExtractor +
 * MediaCodec; no new dependency. Resampling is a naive linear interpolation — more than adequate
 * for speech recognition.
 */
object AudioSnippetDecoder {

    const val TARGET_HZ = 16_000
    private const val TIMEOUT_US = 10_000L

    /** Decode `[startMs, startMs+durationMs)` of [filePath] to mono 16 kHz PCM, or null on error. */
    suspend fun decode(filePath: String, startMs: Long, durationMs: Long): ShortArray? =
        withContext(Dispatchers.Default) {
            runCatching { decodeInternal(filePath, startMs, durationMs) }
                .onFailure { AppLog.e("Decode", "snippet decode failed for $filePath", it) }
                .getOrNull()
        }

    private fun decodeInternal(filePath: String, startMs: Long, durationMs: Long): ShortArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(filePath)
        val trackIndex = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); return null }

        val inputFormat = extractor.getTrackFormat(trackIndex)
        extractor.selectTrack(trackIndex)
        val startUs = startMs * 1000L
        val endUs = (startMs + durationMs) * 1000L
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: run { extractor.release(); return null }
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        var srcHz = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono = ArrayList<Short>(TARGET_HZ * (durationMs / 1000).toInt().coerceAtLeast(1))

        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEOS = true
                        // Only keep samples inside the requested window (seek lands on a prior sync frame).
                        if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= startUs - 100_000L) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            appendMono(outBuf, channels, mono)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        srcHz = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        if (mono.isEmpty()) return null
        val samples = ShortArray(mono.size) { mono[it] }
        return if (srcHz == TARGET_HZ) samples else resampleLinear(samples, srcHz, TARGET_HZ)
    }

    /** Read 16-bit PCM from [buf], down-mix to mono by averaging channels, append to [out]. */
    private fun appendMono(buf: ByteBuffer, channels: Int, out: ArrayList<Short>) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buf.asShortBuffer()
        val n = shorts.remaining()
        if (channels <= 1) {
            for (i in 0 until n) out.add(shorts.get())
        } else {
            var i = 0
            while (i + channels <= n) {
                var sum = 0
                for (c in 0 until channels) sum += shorts.get()
                out.add((sum / channels).toShort())
                i += channels
            }
        }
    }

    private fun resampleLinear(input: ShortArray, srcHz: Int, dstHz: Int): ShortArray {
        if (input.isEmpty() || srcHz <= 0) return input
        val ratio = dstHz.toDouble() / srcHz
        val outLen = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val a = input[idx.coerceIn(0, input.lastIndex)].toInt()
            val b = input[(idx + 1).coerceIn(0, input.lastIndex)].toInt()
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }
}
