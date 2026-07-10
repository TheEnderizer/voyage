package com.betteraudio.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Silence skipping whose parameters can change while audio is playing, and whose on/off toggle
 * actually takes effect immediately.
 *
 * Media3's [SilenceSkippingAudioProcessor] can't do either on its own:
 *  - its tuning parameters are constructor-only (`final` fields), so changing them meant rebuilding
 *    the whole player — in practice the app only read them once, in `PlaybackService.onCreate()`;
 *  - its `isActive()` returns its `enabled` flag, and `DefaultAudioSink` only recomputes its active
 *    processor chain in `configure()` (on an input **format** change), never on `flush()`. Toggling
 *    `enabled` mid-book therefore did nothing until the next file boundary.
 *
 * This wrapper fixes both by *composition* (the Media3 class is `final`, so it can't be subclassed):
 *  - [isActive] is true for any PCM-16 input regardless of [enabled], so the sink always keeps us in
 *    the chain and a plain flush is enough to pick up a toggle;
 *  - when disabled we pass audio through untouched (Media3's `queueInput` ignores `enabled`, so
 *    delegating to a "disabled" inner processor would still skip silence);
 *  - [setParams] rebuilds the inner processor and re-configures it against the stashed format.
 *
 * The inner processor is built with `silenceRetentionRatio = 1f` and
 * `maxSilenceToKeepDurationUs = silenceKeepUs`, so the silence left in place after a skip is
 * exactly `min(actualSilence, silenceKeepUs)` — i.e. what the "Silence to keep" setting says.
 */
@UnstableApi
class LiveSilenceSkippingProcessor : AudioProcessor {

    @Volatile private var enabled = false
    @Volatile private var minSilenceUs = 1_000_000L
    @Volatile private var silenceKeepUs = 300_000L
    @Volatile private var thresholdLevel: Short = 1024

    private var inner: SilenceSkippingAudioProcessor = newInner()
    private var pendingInputFormat = AudioProcessor.AudioFormat.NOT_SET
    private var inputFormat = AudioProcessor.AudioFormat.NOT_SET

    /** Pass-through buffer, used only while [enabled] is false. */
    private var passBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var passOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private fun newInner() = SilenceSkippingAudioProcessor(
        /* minimumSilenceDurationUs = */ minSilenceUs,
        /* silenceRetentionRatio    = */ 1f,
        /* maxSilenceToKeepDurationUs = */ silenceKeepUs,
        /* minVolumeToKeepPercentageWhenMuting = */ 0,
        /* silenceThresholdLevel = */ thresholdLevel
    ).apply { setEnabled(true) }

    // ── Live configuration ──────────────────────────────────────────────────

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        // No configure() needed — isActive() never changes, so the sink keeps us in the chain.
        // Reset the inner state so a freshly-enabled processor doesn't resume mid-silence.
        rebuildInner()
    }

    fun isEnabled(): Boolean = enabled

    /** @return true when the params actually changed (the caller may then nudge the pipeline). */
    fun setParams(minSilenceUs: Long, silenceKeepUs: Long, thresholdLevel: Short): Boolean {
        if (this.minSilenceUs == minSilenceUs &&
            this.silenceKeepUs == silenceKeepUs &&
            this.thresholdLevel == thresholdLevel
        ) return false
        this.minSilenceUs = minSilenceUs
        this.silenceKeepUs = silenceKeepUs
        this.thresholdLevel = thresholdLevel
        rebuildInner()
        return true
    }

    /** Replace the inner processor and bring it back to the current stream's format. */
    private fun rebuildInner() {
        inner.reset()
        inner = newInner()
        if (inputFormat != AudioProcessor.AudioFormat.NOT_SET) {
            runCatching {
                inner.configure(inputFormat)
                inner.flush()
            }
        }
    }

    // ── AudioProcessor ──────────────────────────────────────────────────────

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingInputFormat = inputAudioFormat
        // Silence skipping only drops samples; the format is unchanged either way.
        inner.configure(inputAudioFormat)
        return inputAudioFormat
    }

    private fun isActiveFor(format: AudioProcessor.AudioFormat): Boolean =
        format != AudioProcessor.AudioFormat.NOT_SET && format.encoding == C.ENCODING_PCM_16BIT

    /** Deliberately independent of [enabled] — see the class doc. */
    override fun isActive(): Boolean = isActiveFor(pendingInputFormat)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        if (enabled) {
            inner.queueInput(inputBuffer)
            return
        }
        // Pass-through: copy straight to our own output buffer.
        val remaining = inputBuffer.remaining()
        if (passBuffer.capacity() < remaining) {
            passBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            passBuffer.clear()
        }
        passBuffer.put(inputBuffer)
        passBuffer.flip()
        passOutput = passBuffer
    }

    override fun queueEndOfStream() {
        inputEnded = true
        if (enabled) inner.queueEndOfStream()
    }

    override fun getOutput(): ByteBuffer {
        if (enabled) return inner.output
        val out = passOutput
        passOutput = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean =
        if (enabled) inner.isEnded else inputEnded && !passOutput.hasRemaining()

    override fun flush() {
        inputEnded = false
        passOutput = AudioProcessor.EMPTY_BUFFER
        passBuffer = AudioProcessor.EMPTY_BUFFER
        inputFormat = pendingInputFormat
        inner.flush()
    }

    override fun reset() {
        flush()
        inputFormat = AudioProcessor.AudioFormat.NOT_SET
        pendingInputFormat = AudioProcessor.AudioFormat.NOT_SET
        inner.reset()
    }
}
