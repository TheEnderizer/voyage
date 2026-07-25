package com.betteraudio.playback

import androidx.media3.common.Player
import kotlin.math.abs

/** Decision for a single `onPositionDiscontinuity` event. */
enum class JumpDecision { IGNORE_INTENTIONAL, IGNORE_MINOR, FLAG }

/**
 * Pure, Android/Media3-object-free classifier for whether a playback position discontinuity is a
 * genuine involuntary "jump" worth surfacing to the user, or normal playback plumbing. Whitelist,
 * not blacklist: only [Player.DISCONTINUITY_REASON_INTERNAL] beyond [thresholdMs] is ever FLAGged.
 * Every other reason — SEEK/SEEK_ADJUSTMENT (user or app-initiated seeks, already logged via
 * `recordSkip`/skip buttons), AUTO_TRANSITION (normal file rollover — book position is continuous
 * across files so it isn't a jump anyway), REMOVE/SKIP (playlist edits on book change/stop, which
 * fire on every book switch and series auto-advance), and SILENCE_SKIP (benign silence trimming,
 * which can exceed any magnitude threshold on a long silent gap so must be excluded by reason, not
 * size) — is intentional/expected and must map to an explicit ignore.
 */
object JumpClassifier {
    fun classify(
        reason: Int,
        oldBookPosMs: Long,
        newBookPosMs: Long,
        thresholdMs: Long = 15_000,
    ): JumpDecision {
        if (reason != Player.DISCONTINUITY_REASON_INTERNAL) return JumpDecision.IGNORE_INTENTIONAL
        return if (abs(newBookPosMs - oldBookPosMs) <= thresholdMs) JumpDecision.IGNORE_MINOR else JumpDecision.FLAG
    }
}
