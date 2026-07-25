package com.betteraudio.playback

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classifier is a whitelist, not a blacklist: only DISCONTINUITY_REASON_INTERNAL beyond
 * threshold is ever flagged. This locks in the two false-positives the plan audit caught in an
 * earlier (blacklist) draft — REMOVE fires on every normal book switch/series auto-advance, and
 * SILENCE_SKIP fires on ordinary silence trimming and can exceed any magnitude threshold on a
 * long silent gap — both must be ignored by reason, not by size.
 */
class JumpClassifierTest {

    // ── every non-INTERNAL reason is ignored regardless of magnitude ────────

    @Test
    fun `SEEK is always ignored regardless of magnitude`() {
        assertEquals(
            JumpDecision.IGNORE_INTENTIONAL,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_SEEK, 0L, 999_999L)
        )
    }

    @Test
    fun `SEEK_ADJUSTMENT is always ignored regardless of magnitude`() {
        assertEquals(
            JumpDecision.IGNORE_INTENTIONAL,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT, 0L, 999_999L)
        )
    }

    @Test
    fun `AUTO_TRANSITION is always ignored regardless of magnitude`() {
        assertEquals(
            JumpDecision.IGNORE_INTENTIONAL,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_AUTO_TRANSITION, 0L, 999_999L)
        )
    }

    @Test
    fun `REMOVE is always ignored even beyond threshold - the book-switch false positive`() {
        assertEquals(
            JumpDecision.IGNORE_INTENTIONAL,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_REMOVE, 0L, 999_999L, thresholdMs = 15_000)
        )
    }

    @Test
    fun `SKIP is always ignored regardless of magnitude`() {
        assertEquals(
            JumpDecision.IGNORE_INTENTIONAL,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_SKIP, 0L, 999_999L)
        )
    }

    @Test
    fun `SILENCE_SKIP is always ignored even beyond threshold - the long-silence false positive`() {
        assertEquals(
            JumpDecision.IGNORE_INTENTIONAL,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_SILENCE_SKIP, 0L, 60_000L, thresholdMs = 15_000)
        )
    }

    // ── INTERNAL is the only flaggable reason, gated by magnitude ───────────

    @Test
    fun `INTERNAL below threshold is a minor correction, not a jump`() {
        assertEquals(
            JumpDecision.IGNORE_MINOR,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_INTERNAL, 10_000L, 20_000L, thresholdMs = 15_000)
        )
    }

    @Test
    fun `INTERNAL exactly at the threshold is still ignored (boundary is inclusive)`() {
        assertEquals(
            JumpDecision.IGNORE_MINOR,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_INTERNAL, 0L, 15_000L, thresholdMs = 15_000)
        )
    }

    @Test
    fun `INTERNAL just past the threshold is flagged`() {
        assertEquals(
            JumpDecision.FLAG,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_INTERNAL, 0L, 15_001L, thresholdMs = 15_000)
        )
    }

    @Test
    fun `large forward INTERNAL jump is flagged`() {
        assertEquals(
            JumpDecision.FLAG,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_INTERNAL, 60_000L, 600_000L, thresholdMs = 15_000)
        )
    }

    @Test
    fun `large backward INTERNAL jump is flagged`() {
        assertEquals(
            JumpDecision.FLAG,
            JumpClassifier.classify(Player.DISCONTINUITY_REASON_INTERNAL, 600_000L, 60_000L, thresholdMs = 15_000)
        )
    }
}
