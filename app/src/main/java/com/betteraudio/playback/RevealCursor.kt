package com.betteraudio.playback

import androidx.media3.common.Player

/**
 * Pure, Android/Media3-object-free engine for how a companion pack's reveal cursor moves
 * (docs/companion-packs.md §6.2) — the three rules:
 *  1. Continuous listening advances it.
 *  2. A raw seek never moves it.
 *  3. A confirmed seek moves it either direction (handled separately — see
 *     [com.betteraudio.companion.RevealCursorRepository.confirmSeekTo] — this class only ever
 *     implements rules 1/2, the automatic side).
 *
 * Deliberately NOT built on [com.betteraudio.data.db.entities.SkipEvent.source]: that only
 * records a jump when the delta exceeds `SCRUB_HISTORY_MIN_MS` (5 minutes — see
 * `PlayerViewModel.recordSkip`), so a 4-minute scrub would write no event and this engine would
 * wrongly treat it as continuous. [Player.onPositionDiscontinuity]'s `reason` has no such gap: it
 * fires for every seek, of any size, from every input surface (touch scrub, skip button, chapter
 * jump, lock screen, Android Auto, headset, the session's `ForwardingPlayer`) — see
 * [PlaybackService.handleJumpDetection], which already classifies every discontinuity this way for
 * the unrelated jump-restore feature and is the call site that drives this engine too.
 *
 * One approximation, by design: this only distinguishes "was there any seek/flagged jump since the
 * last tick" — not the trailing genuine-listening portion of a tick that also contained a seek. A
 * tick containing a seek credits nothing toward reveal for that whole interval (default 30s, the
 * position-saver cadence); the very next tick catches up once listening has been unbroken for a
 * full interval. This can lag reveal behind genuine listening by up to one tick — never ahead of
 * it, which is the direction that matters for a spoiler-avoidance feature.
 */
class RevealCursor {

    /** Per-book engine state. Lives only in memory (this class is owned by [PlaybackService],
     *  itself a singleton service instance) — [PlaybackProgress.revealedMs] is the persisted
     *  value this state advances toward; this is only the bookkeeping [onTick] needs between
     *  writes. */
    private data class BookState(
        /** The book-global position as of the last tick or discontinuity we trust as continuous. */
        var lastKnownBookPosMs: Long,
        /** True when a seek/flagged jump happened since [lastKnownBookPosMs] was last trusted —
         *  consumed (and cleared) by the next [onTick] call, per the one-tick-lag approximation
         *  documented on the class. */
        var jumpedSinceLastTick: Boolean = false,
    )

    private val state = HashMap<Long, BookState>()

    /**
     * Call from [PlaybackService.handleJumpDetection] on every SEEK/SEEK_ADJUSTMENT discontinuity
     * — any size, no threshold (that gap is exactly what made `SkipEvent.source` unusable for
     * this; see class doc). Takes no position: whatever the seek's target turns out to be, the
     * very next [onTick] overwrites [BookState.lastKnownBookPosMs] with the real current position
     * anyway, so there is nothing to compute here — which also means this never needs
     * `bookPositionMsFor`'s cross-item envelope at all, unlike [onInternalDiscontinuity].
     * AUTO_TRANSITION/REMOVE/SKIP discontinuities need no call: file rollover is continuous by
     * definition, and playlist edits (book switch/stop) are handled by [forget] instead.
     */
    fun onSeek(bookId: Long) {
        state.getOrPut(bookId) { BookState(lastKnownBookPosMs = 0L) }.jumpedSinceLastTick = true
    }

    /**
     * Call from [PlaybackService.handleJumpDetection] only where it already computes both
     * positions for an INTERNAL, same-media-item discontinuity (the same guard that call site
     * already applies for its own jump-restore purpose, and for the same reason: a cross-item
     * INTERNAL discontinuity falls outside `bookPositionMsFor`'s safe envelope). Note skip-silence
     * itself needs no special case here at all: `Player.DISCONTINUITY_REASON_SILENCE_SKIP` is its
     * own reason, distinct from INTERNAL (see [JumpClassifierTest]), and `handleJumpDetection`'s
     * `if (reason != INTERNAL) return` guard means this method is never even called for it — a
     * silence-skip discontinuity triggers neither [onSeek] nor this, so it never sets
     * `jumpedSinceLastTick`, and the next [onTick] simply sees the post-skip position as ordinary
     * continuous progress (§15 test 5). What IGNORE_MINOR here actually covers is a genuine small
     * INTERNAL correction (e.g. the gap-skipping DataSource re-syncing near a damage boundary);
     * FLAG is a genuine unexplained large jump, treated the same as a seek, since the position
     * moved without the listener choosing it.
     */
    fun onInternalDiscontinuity(bookId: Long, oldBookPosMs: Long, newBookPosMs: Long) {
        val s = state.getOrPut(bookId) { BookState(lastKnownBookPosMs = oldBookPosMs) }
        if (JumpClassifier.classify(Player.DISCONTINUITY_REASON_INTERNAL, oldBookPosMs, newBookPosMs) == JumpDecision.FLAG) {
            s.jumpedSinceLastTick = true
        }
    }

    /**
     * Called once per position-saver tick with the book's current book-global position. Returns
     * the new revealedMs to persist, or null if nothing should change this tick (either no forward
     * progress, or a jump happened since the last tick and this interval is being conservatively
     * skipped per the class doc).
     */
    fun onTick(bookId: Long, currentBookPosMs: Long, currentRevealedMs: Long): Long? {
        val s = state.getOrPut(bookId) { BookState(lastKnownBookPosMs = currentBookPosMs) }
        val jumped = s.jumpedSinceLastTick
        s.jumpedSinceLastTick = false
        s.lastKnownBookPosMs = currentBookPosMs
        if (jumped) return null
        return if (currentBookPosMs > currentRevealedMs) currentBookPosMs else null
    }

    /** Call when a book stops being the active playback target (switched away from, or playback
     *  fully stopped) — without this, a stale [BookState] would let a future tick on the SAME book
     *  wrongly treat a huge elapsed-time gap as continuous the next time it resumes. Re-seeding on
     *  the next [onTick]/[onSeek]/[onInternalDiscontinuity] call (all `getOrPut`) is sufficient by
     *  itself for correctness, but dropping the entry also bounds this map's size to "books
     *  touched this session" instead of "books ever touched". */
    fun forget(bookId: Long) {
        state.remove(bookId)
    }
}
