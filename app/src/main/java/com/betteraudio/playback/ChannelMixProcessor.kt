package com.betteraudio.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Stereo balance / mono-downmix, applied per-buffer directly on PCM samples (no decoder
 * involvement, no re-prepare) so a change takes effect immediately. [balance]/[mono] are
 * `@Volatile` — written from the session/controller thread (see PlaybackService's
 * CMD_SET_CHANNEL_MIX handler) and read here on the audio-render thread.
 *
 * [balance] in -1f..1f is a plain balance control (attenuates the *opposite* channel; never
 * mixes the channels) — negative attenuates the right channel, positive attenuates the left.
 * [mono] collapses both channels to their average and takes priority over [balance] when both
 * are set. Declared [isActive] for any stereo PCM16/float input regardless of current
 * balance/mono values (a no-op still copies through unchanged) so the sink never needs to
 * reconfigure the chain just because these were toggled mid-stream — a flush is enough.
 */
@UnstableApi
class ChannelMixProcessor : AudioProcessor {

    @Volatile var balance: Float = 0f
    @Volatile var mono: Boolean = false

    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        inputFormat.channelCount == 2 &&
            (inputFormat.encoding == C.ENCODING_PCM_16BIT || inputFormat.encoding == C.ENCODING_PCM_FLOAT)

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN) // same underlying buffer — advances inputBuffer's position too
        val out = obtainBuffer(remaining)
        when (inputFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(src, out)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(src, out)
            else -> out.put(src) // shouldn't happen — isActive() gates this
        }
        out.flip()
        outputBuffer = out
    }

    private fun obtainBuffer(minCapacity: Int): ByteBuffer {
        if (buffer.capacity() < minCapacity) {
            buffer = ByteBuffer.allocateDirect(minCapacity).order(ByteOrder.LITTLE_ENDIAN)
        } else {
            buffer.clear()
        }
        return buffer
    }

    /** Attenuation for (left, right) given the current [balance]; 1f = unchanged. Only meaningful
     *  when [mono] is off — mono ignores balance entirely (both channels become one average). */
    private fun balanceGains(): Pair<Float, Float> {
        val left = if (balance > 0f) 1f - balance else 1f
        val right = if (balance < 0f) 1f + balance else 1f
        return left to right
    }

    private fun processPcm16(src: ByteBuffer, out: ByteBuffer) {
        val isMono = mono
        val (leftGain, rightGain) = if (isMono) 1f to 1f else balanceGains()
        while (src.remaining() >= 4) {
            val l = src.short.toInt()
            val r = src.short.toInt()
            if (isMono) {
                val m = ((l + r) / 2).toShort()
                out.putShort(m)
                out.putShort(m)
            } else {
                out.putShort(clampToShort(l * leftGain))
                out.putShort(clampToShort(r * rightGain))
            }
        }
    }

    private fun processPcmFloat(src: ByteBuffer, out: ByteBuffer) {
        val isMono = mono
        val (leftGain, rightGain) = if (isMono) 1f to 1f else balanceGains()
        while (src.remaining() >= 8) {
            val l = src.float
            val r = src.float
            if (isMono) {
                val m = (l + r) / 2f
                out.putFloat(m)
                out.putFloat(m)
            } else {
                out.putFloat(l * leftGain)
                out.putFloat(r * rightGain)
            }
        }
    }

    private fun clampToShort(value: Float): Short =
        value.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && !outputBuffer.hasRemaining()

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}
