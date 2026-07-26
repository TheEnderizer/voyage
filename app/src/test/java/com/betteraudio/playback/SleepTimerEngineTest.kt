package com.betteraudio.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two genuinely pure computations behind the sleep timer: the scheduled-auto-arm window
 * check (which must handle a window that wraps past midnight, e.g. 22:00-06:00) and the
 * volume-fade fraction. Everything else in [SleepTimerEngine] is stateful orchestration against
 * the real player/session/widget updater and is covered by manual/live verification instead.
 */
class SleepTimerEngineTest {

    // ── isInScheduleWindow ──────────────────────────────────────────────────

    @Test
    fun `non-wrapping window includes times strictly between start and end`() {
        // 21:00 (1260) to 23:00 (1380)
        assertTrue(SleepTimerEngine.isInScheduleWindow(1320, 1260, 1380))
    }

    @Test
    fun `non-wrapping window excludes times before start`() {
        assertFalse(SleepTimerEngine.isInScheduleWindow(1200, 1260, 1380))
    }

    @Test
    fun `non-wrapping window excludes the end minute itself`() {
        assertFalse(SleepTimerEngine.isInScheduleWindow(1380, 1260, 1380))
    }

    @Test
    fun `non-wrapping window includes the start minute itself`() {
        assertTrue(SleepTimerEngine.isInScheduleWindow(1260, 1260, 1380))
    }

    @Test
    fun `wrapping window (22-00 to 06-00) includes late night`() {
        // 22:00 = 1320, 06:00 = 360
        assertTrue(SleepTimerEngine.isInScheduleWindow(1350, 1320, 360)) // 22:30
    }

    @Test
    fun `wrapping window includes early morning before end`() {
        assertTrue(SleepTimerEngine.isInScheduleWindow(120, 1320, 360)) // 02:00
    }

    @Test
    fun `wrapping window excludes daytime between end and start`() {
        assertFalse(SleepTimerEngine.isInScheduleWindow(720, 1320, 360)) // 12:00
    }

    @Test
    fun `wrapping window excludes the end minute itself`() {
        assertFalse(SleepTimerEngine.isInScheduleWindow(360, 1320, 360))
    }

    // ── fadeFraction ────────────────────────────────────────────────────────

    @Test
    fun `fadeFraction is 1 at the very start of the fade`() {
        assertEquals(1f, SleepTimerEngine.fadeFraction(10_000L, 10_000L), 0.0001f)
    }

    @Test
    fun `fadeFraction is 0 once remaining reaches zero`() {
        assertEquals(0f, SleepTimerEngine.fadeFraction(0L, 10_000L), 0.0001f)
    }

    @Test
    fun `fadeFraction is halfway at half the fade duration`() {
        assertEquals(0.5f, SleepTimerEngine.fadeFraction(5_000L, 10_000L), 0.0001f)
    }

    @Test
    fun `fadeFraction clamps above 1 for remaining beyond the fade window`() {
        assertEquals(1f, SleepTimerEngine.fadeFraction(20_000L, 10_000L), 0.0001f)
    }

    @Test
    fun `fadeFraction clamps below 0 for negative remaining`() {
        assertEquals(0f, SleepTimerEngine.fadeFraction(-5_000L, 10_000L), 0.0001f)
    }
}
