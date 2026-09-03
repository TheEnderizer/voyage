package com.betteraudio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Locks in docs/companion-packs.md §6.2's three-rule contract for the automatic side of the
 * reveal cursor (rule 3, the confirmed-seek control, is out of scope for this pure engine — see
 * RevealCursor's class doc). Every test drives [RevealCursor] directly with the same reason
 * values [PlaybackService.handleJumpDetection] would pass, so a regression here is a regression
 * in the actual wiring, not just the model.
 */
class RevealCursorTest {

    // ── rule 1: continuous listening advances it ─────────────────────────────

    @Test
    fun `ordinary tick with no discontinuity advances to the current position`() {
        val cursor = RevealCursor()
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 60_000L, currentRevealedMs = 30_000L)
        assertEquals(60_000L, newRevealed)
    }

    @Test
    fun `a tick that has not moved forward advances nothing`() {
        val cursor = RevealCursor()
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 30_000L, currentRevealedMs = 30_000L)
        assertNull(newRevealed)
    }

    @Test
    fun `consecutive continuous ticks keep advancing`() {
        val cursor = RevealCursor()
        var revealed = 0L
        cursor.onTick(1L, 30_000L, revealed)?.let { revealed = it }
        cursor.onTick(1L, 60_000L, revealed)?.let { revealed = it }
        cursor.onTick(1L, 90_000L, revealed)?.let { revealed = it }
        assertEquals(90_000L, revealed)
    }

    // ── rule 2: a raw seek never moves it ────────────────────────────────────

    @Test
    fun `a seek blocks the very next tick even though position moved forward`() {
        val cursor = RevealCursor()
        cursor.onSeek(bookId = 1L)
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 9_000_000L, currentRevealedMs = 30_000L)
        assertNull(newRevealed)
    }

    @Test
    fun `a small 4-minute seek is blocked just like a large one - the SkipEvent threshold gap this replaces`() {
        // docs/companion-packs.md §6.2: SkipEvent.source only records a jump past 5 minutes
        // (SCRUB_HISTORY_MIN_MS), which is exactly the gap RevealCursor exists to close. onSeek
        // takes no magnitude at all, so there is no threshold to fall under.
        val cursor = RevealCursor()
        cursor.onSeek(bookId = 1L)
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 270_000L, currentRevealedMs = 30_000L)
        assertNull(newRevealed)
    }

    @Test
    fun `listening resumes normally on the tick after the seek`() {
        val cursor = RevealCursor()
        cursor.onSeek(bookId = 1L)
        // The seeked-to tick: blocked, but lastKnownBookPosMs is now re-seeded to 700_000.
        assertNull(cursor.onTick(bookId = 1L, currentBookPosMs = 700_000L, currentRevealedMs = 30_000L))
        // Next tick: ordinary continuous progress from the new position.
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 730_000L, currentRevealedMs = 30_000L)
        assertEquals(730_000L, newRevealed)
    }

    @Test
    fun `an unrelated discontinuity reason like AUTO_TRANSITION never blocks a tick`() {
        // AUTO_TRANSITION/REMOVE/SKIP/SILENCE_SKIP all need no call into RevealCursor at all (see
        // PlaybackService.handleJumpDetection and RevealCursor.onInternalDiscontinuity's doc) —
        // this test stands in for "nothing was called", which is the actual production behavior.
        val cursor = RevealCursor()
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 45_000L, currentRevealedMs = 30_000L)
        assertEquals(45_000L, newRevealed)
    }

    // ── INTERNAL discontinuities: minor corrections are continuous, large ones are not ──

    @Test
    fun `a small INTERNAL correction under JumpClassifier's threshold does not block the tick`() {
        val cursor = RevealCursor()
        cursor.onInternalDiscontinuity(bookId = 1L, oldBookPosMs = 30_000L, newBookPosMs = 40_000L)
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 40_000L, currentRevealedMs = 30_000L)
        assertEquals(40_000L, newRevealed)
    }

    @Test
    fun `a large INTERNAL jump past JumpClassifier's threshold blocks the tick like a seek`() {
        val cursor = RevealCursor()
        cursor.onInternalDiscontinuity(bookId = 1L, oldBookPosMs = 30_000L, newBookPosMs = 700_000L)
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 700_000L, currentRevealedMs = 30_000L)
        assertNull(newRevealed)
    }

    // ── per-book isolation ────────────────────────────────────────────────────

    @Test
    fun `a seek on one book never blocks another book's tick`() {
        val cursor = RevealCursor()
        cursor.onSeek(bookId = 1L)
        val newRevealed = cursor.onTick(bookId = 2L, currentBookPosMs = 60_000L, currentRevealedMs = 30_000L)
        assertEquals(60_000L, newRevealed)
    }

    // ── forget() ────────────────────────────────────────────────────────────

    @Test
    fun `forget clears state so a later seek does not leak into a book restarted from scratch`() {
        val cursor = RevealCursor()
        cursor.onSeek(bookId = 1L)
        cursor.forget(bookId = 1L)
        // A fresh BookState is seeded from this tick's own position, not left mid-jump.
        val newRevealed = cursor.onTick(bookId = 1L, currentBookPosMs = 10_000L, currentRevealedMs = 0L)
        assertEquals(10_000L, newRevealed)
    }
}
