package com.betteraudio.playback

import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cascade is the single resolver behind every play/resume entry point (full player, home
 * resume card, series continuation, cold widget tap) — a regression here silently changes how a
 * book sounds depending on where it was started from, which is exactly the drift the unified
 * [AudioCascade.resolve] exists to prevent.
 */
class AudioCascadeTest {

    private fun book(skipSilence: Boolean = false) =
        Book(id = 1L, title = "T", folderPath = "/lib/t", skipSilenceEnabled = skipSilence)

    private fun progress(speed: Float = 1.0f, boost: Int = 0, eq: String? = null) =
        PlaybackProgress(bookId = 1L, playbackSpeed = speed, boostDb = boost, eqBandsJson = eq)

    private fun series(speed: Float? = null, boost: Int? = null, eq: String? = null, skip: Boolean? = null) =
        Series(id = 2L, name = "S", playbackSpeed = speed, boostDb = boost, eqBandsJson = eq, skipSilenceEnabled = skip)

    private fun preset(speed: Float = 1.0f, boost: Int = 0, eq: String? = null) =
        AudioPreset(name = "P", speedMult = speed, boostDb = boost, eqBandsJson = eq, isDefault = true)

    // ── speed ────────────────────────────────────────────────────────────────

    @Test
    fun `book speed override wins over series and preset`() {
        val r = AudioCascade.resolve(book(), progress(speed = 1.5f), series(speed = 1.2f), preset(speed = 1.1f), 1.0f)
        assertEquals(1.5f, r.speed)
    }

    @Test
    fun `neutral book speed inherits the series default`() {
        val r = AudioCascade.resolve(book(), progress(speed = 1.0f), series(speed = 1.2f), preset(speed = 1.1f), 1.0f)
        assertEquals(1.2f, r.speed)
    }

    @Test
    fun `no book or series speed falls to the global default preset`() {
        val r = AudioCascade.resolve(book(), progress(), series(), preset(speed = 1.1f), 1.0f)
        assertEquals(1.1f, r.speed)
    }

    @Test
    fun `no preset falls to the scalar fallback`() {
        val r = AudioCascade.resolve(book(), progress(), null, null, 1.3f)
        assertEquals(1.3f, r.speed)
    }

    @Test
    fun `null progress behaves like a fresh book`() {
        val r = AudioCascade.resolve(book(), null, series(speed = 1.2f), null, 1.0f)
        assertEquals(1.2f, r.speed)
    }

    // ── boost ────────────────────────────────────────────────────────────────

    @Test
    fun `book boost override wins`() {
        val r = AudioCascade.resolve(book(), progress(boost = 6), series(boost = 3), preset(boost = 2), 1.0f)
        assertEquals(6, r.boostDb)
    }

    @Test
    fun `neutral book boost inherits series then preset`() {
        assertEquals(3, AudioCascade.resolve(book(), progress(boost = 0), series(boost = 3), preset(boost = 2), 1.0f).boostDb)
        assertEquals(2, AudioCascade.resolve(book(), progress(boost = 0), series(), preset(boost = 2), 1.0f).boostDb)
        assertEquals(0, AudioCascade.resolve(book(), progress(boost = 0), null, null, 1.0f).boostDb)
    }

    // ── EQ ───────────────────────────────────────────────────────────────────

    @Test
    fun `book eq wins, else series, else preset, else null`() {
        val bookEq = "[1,0,0,0,0]"; val seriesEq = "[0,2,0,0,0]"; val presetEq = "[0,0,3,0,0]"
        assertEquals(bookEq, AudioCascade.resolve(book(), progress(eq = bookEq), series(eq = seriesEq), preset(eq = presetEq), 1.0f).eqBandsJson)
        assertEquals(seriesEq, AudioCascade.resolve(book(), progress(), series(eq = seriesEq), preset(eq = presetEq), 1.0f).eqBandsJson)
        assertEquals(presetEq, AudioCascade.resolve(book(), progress(), series(), preset(eq = presetEq), 1.0f).eqBandsJson)
        assertNull(AudioCascade.resolve(book(), progress(), null, null, 1.0f).eqBandsJson)
    }

    // ── skip silence ─────────────────────────────────────────────────────────

    @Test
    fun `skip silence is on if the book or the series enables it`() {
        assertTrue(AudioCascade.resolve(book(skipSilence = true), null, series(skip = false), null, 1.0f).skipSilence)
        assertTrue(AudioCascade.resolve(book(skipSilence = false), null, series(skip = true), null, 1.0f).skipSilence)
        assertFalse(AudioCascade.resolve(book(skipSilence = false), null, series(skip = false), null, 1.0f).skipSilence)
        assertFalse(AudioCascade.resolve(book(skipSilence = false), null, null, null, 1.0f).skipSilence)
    }

    // ── text fallback ────────────────────────────────────────────────────────

    @Test
    fun `text falls back to the series value only when the book value is blank`() {
        assertEquals("Book Author", AudioCascade.text("Book Author", "Series Author"))
        assertEquals("Series Author", AudioCascade.text("", "Series Author"))
        assertEquals("Series Author", AudioCascade.text(null, "Series Author"))
        assertNull(AudioCascade.text(null, null))
        assertNull(AudioCascade.text("", "  "))
    }

    // ── auto-rewind ──────────────────────────────────────────────────────────

    @Test
    fun `no prior pause means no rewind`() {
        assertEquals(0L, AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 5, appStoppedAt = 0L, lastPausedAt = 0L, nowMs = 1_000L))
        assertEquals(0L, AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 5, appStoppedAt = 0L, lastPausedAt = -1L, nowMs = 1_000L))
    }

    @Test
    fun `disabled rewind (zero seconds) means no rewind regardless of other state`() {
        assertEquals(0L, AudioCascade.autoRewindMs(rewindSeconds = 0, thresholdMinutes = 5, appStoppedAt = 500L, lastPausedAt = 100L, nowMs = 1_000L))
    }

    @Test
    fun `app fully stopped since the pause forces the full rewind regardless of elapsed time`() {
        val r = AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 5, appStoppedAt = 200L, lastPausedAt = 100L, nowMs = 150L)
        assertEquals(30_000L, r)
    }

    @Test
    fun `under the threshold since pause means no rewind`() {
        // thresholdMinutes = 5 -> 300_000ms; only 100ms elapsed.
        val r = AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 5, appStoppedAt = 0L, lastPausedAt = 1_000L, nowMs = 1_100L)
        assertEquals(0L, r)
    }

    @Test
    fun `at or past the threshold since pause triggers the full rewind`() {
        val thresholdMs = 5 * 60_000L
        val atThreshold = AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 5, appStoppedAt = 0L, lastPausedAt = 1_000L, nowMs = 1_000L + thresholdMs)
        val pastThreshold = AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 5, appStoppedAt = 0L, lastPausedAt = 1_000L, nowMs = 1_000L + thresholdMs + 1)
        assertEquals(30_000L, atThreshold)
        assertEquals(30_000L, pastThreshold)
    }

    @Test
    fun `zero threshold minutes means no rewind when app was not fully stopped`() {
        val r = AudioCascade.autoRewindMs(rewindSeconds = 30, thresholdMinutes = 0, appStoppedAt = 0L, lastPausedAt = 1_000L, nowMs = 999_999L)
        assertEquals(0L, r)
    }
}
