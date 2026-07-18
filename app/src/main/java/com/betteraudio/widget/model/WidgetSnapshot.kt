package com.betteraudio.widget.model

import kotlinx.serialization.Serializable

/**
 * Persisted playback snapshot — the single source of truth widget rendering reads from. Written
 * by PlaybackService.pushWidgetState() on every playback event and persisted via
 * WidgetStateStore, so a cold provider start (reboot, launcher restart, app force-stopped) always
 * has real data to render instead of the old system's blank-until-first-broadcast widget.
 *
 * [writtenAtElapsedRealtimeMs] guards against a stale "still playing" snapshot surviving a reboot:
 * SystemClock.elapsedRealtime() resets to 0 on boot, so if the current elapsedRealtime() is ever
 * LESS than this stored value, a reboot happened since this snapshot was written and playback
 * state must be coerced to paused/idle (see WidgetStateStore.readCoerced).
 */
@Serializable
data class WidgetSnapshot(
    val bookId: Long = -1,
    val title: String = "",
    val author: String = "",
    val chapterTitle: String = "",
    val seriesName: String = "",
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    val boostDb: Int = 0,
    val bookCoverPath: String? = null,
    val seriesCoverPath: String? = null,
    val positionMs: Long = 0,
    val bookDurationMs: Long = 0,
    val chapterPositionMs: Long = 0,
    val chapterDurationMs: Long = 0,
    /** Elapsed-realtime deadline for a running COUNTDOWN-mode sleep timer; 0 = timer off. */
    val sleepEndAtElapsedMs: Long = 0,
    /** Fallback remaining-ms display for END_OF_CHAPTER mode (no fixed deadline to compute from). */
    val sleepRemainingMs: Long = 0,
    val writtenAtElapsedRealtimeMs: Long = 0,
)
