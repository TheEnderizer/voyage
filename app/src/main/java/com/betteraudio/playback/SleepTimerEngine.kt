package com.betteraudio.playback

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Owns the sleep timer: mode/state, the 1 Hz tick loop, volume fade-out, shake-to-extend
 * integration (via [shakeDetector]), the post-fire shake grace-window wakelock, and the
 * scheduled-auto-arm check. Single authority for both the widget's timer and the in-app
 * player's (see [PlaybackService]'s original field comment) — it must keep running even if the
 * app process (and PlayerController's own coroutine scope) has died; only PlaybackService's own
 * process (tied to the foreground notification) needs to survive.
 *
 * Modes are [PlaybackService.SLEEP_MODE_OFF]/`SLEEP_MODE_COUNTDOWN`/`SLEEP_MODE_END_OF_CHAPTER` —
 * those constants stay on PlaybackService since they're also the wire format for the
 * `CMD_SET_SLEEP_TIMER` session command PlayerController sends across.
 *
 * [player] is the minimal surface this engine needs back from PlaybackService — kept as an
 * interface so the timer math and orchestration here don't depend on Media3/ExoPlayer directly.
 */
class SleepTimerEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val shakeDetector: ShakeDetector,
    private val player: Player,
) {
    interface Player {
        fun isPlaying(): Boolean
        fun pause()
        fun play()
        fun getVolume(): Float
        fun setVolume(volume: Float)
        /** Absolute book-level position right now (see PlaybackService.bookPositionMsFor). */
        fun currentBookPositionMs(): Long
        fun isScreenOn(): Boolean
        /** Cheap re-render for widgets showing a COUNTDOWN (see WidgetUpdater.tickCountdown). */
        fun tickCountdownWidget()
        /** Cached-metadata re-render for END_OF_CHAPTER mode (see pushWidgetStateForSleepTick). */
        fun tickEndOfChapterWidget()
        /** Full state push (see PlaybackService.pushWidgetState). */
        fun pushFullWidgetState()
        fun broadcastSleepState(mode: String, remainingMs: Long)
    }

    companion object {
        private const val SHAKE_ARM_WINDOW_MS = 30_000L   // start listening this close to firing
        private const val SHAKE_GRACE_WINDOW_MS = 30_000L // keep listening this long after firing

        /** True when [nowMinutes] falls inside `[start, end)` of the day, handling a window that
         *  wraps past midnight (start > end, e.g. a 22:00-06:00 schedule). Pure. */
        fun isInScheduleWindow(nowMinutes: Int, start: Int, end: Int): Boolean =
            if (start <= end) nowMinutes in start until end else (nowMinutes >= start || nowMinutes < end)

        /** Fraction of pre-fade volume to apply at [remainingMs] into a [fadeMs]-long fade-out —
         *  1.0 at the fade's start, 0.0 once it reaches zero. Pure. */
        fun fadeFraction(remainingMs: Long, fadeMs: Long): Float =
            (remainingMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
    }

    var mode: String = PlaybackService.SLEEP_MODE_OFF
        private set
    private var endAtElapsedMs: Long = 0L        // COUNTDOWN target, SystemClock.elapsedRealtime()
    private var targetBookPositionMs: Long = 0L  // END_OF_CHAPTER target, absolute book-level ms
    private var resetMs: Long = 15 * 60_000L     // duration re-armed on shake-to-extend
    var remainingMsForWidget: Long = 0L
        private set
    private var tickJob: Job? = null
    private var fadeActive = false
    private var preFadeVolume = 1f
    private var graceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    // Guards the schedule from re-arming every time playback resumes within one listening
    // session; reset when a fresh set of media items is loaded (a genuinely new session).
    private var scheduleArmedThisSession = false

    /** The COUNTDOWN deadline for the widget snapshot, else 0 — mirrors the `sleepEnd` value
     *  PlaybackService's own pushWidgetState() computed inline before this was extracted. */
    val sleepEndAtElapsedMsForWidget: Long
        get() = if (mode == PlaybackService.SLEEP_MODE_COUNTDOWN) endAtElapsedMs else 0L

    fun setTimer(newMode: String, durationMs: Long, targetBookPositionMsArg: Long) {
        val prevMode = mode
        tickJob?.cancel()
        graceJob?.cancel()
        graceJob = null
        shakeDetector.stop()
        releaseWakeLock()
        if (fadeActive) {
            player.setVolume(preFadeVolume)
            fadeActive = false
        }

        mode = newMode
        if (durationMs > 0L) resetMs = durationMs
        when (newMode) {
            PlaybackService.SLEEP_MODE_COUNTDOWN -> {
                endAtElapsedMs = SystemClock.elapsedRealtime() + durationMs
                startTick()
            }
            PlaybackService.SLEEP_MODE_END_OF_CHAPTER -> {
                targetBookPositionMs = targetBookPositionMsArg
                startTick()
            }
            else -> {
                remainingMsForWidget = 0L
            }
        }
        AppLog.i(LogCat.SLEEP, "setTimer $prevMode -> $newMode durationMs=$durationMs targetBookPositionMs=$targetBookPositionMsArg")
        player.pushFullWidgetState()
        player.broadcastSleepState(mode, remainingMsForWidget)
    }

    /** Auto-arms the default sleep timer once per listening session when playback starts inside
     *  the configured schedule window (handles a window that wraps past midnight). */
    fun maybeAutoArmScheduled() {
        if (!settings.currentSleepScheduleEnabled) { AppLog.d(LogCat.SLEEP) { "maybeAutoArmScheduled: schedule disabled" }; return }
        if (mode != PlaybackService.SLEEP_MODE_OFF) { AppLog.d(LogCat.SLEEP) { "maybeAutoArmScheduled: a timer is already active ($mode)" }; return }
        if (scheduleArmedThisSession) { AppLog.d(LogCat.SLEEP) { "maybeAutoArmScheduled: already armed this session" }; return }
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val inWindow = isInScheduleWindow(
            nowMinutes, settings.currentSleepScheduleStartMinutes, settings.currentSleepScheduleEndMinutes
        )
        if (!inWindow) {
            AppLog.d(LogCat.SLEEP) {
                "maybeAutoArmScheduled: now=${nowMinutes}min outside window " +
                    "[${settings.currentSleepScheduleStartMinutes},${settings.currentSleepScheduleEndMinutes})"
            }
            return
        }
        scheduleArmedThisSession = true
        AppLog.i(LogCat.SLEEP, "auto-arming scheduled sleep timer (${settings.currentSleepScheduleDefaultMinutes}min)")
        setTimer(PlaybackService.SLEEP_MODE_COUNTDOWN, settings.currentSleepScheduleDefaultMinutes * 60_000L, 0L)
    }

    /** Call when a genuinely new playlist loads (a fresh listening session) — mirrors the
     *  original scheduleArmedThisSession reset on MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED. */
    fun resetScheduleArmedForNewSession() {
        scheduleArmedThisSession = false
    }

    private fun startTick() {
        tickJob = scope.launch {
            while (isActive && mode != PlaybackService.SLEEP_MODE_OFF) {
                val remaining = computeRemainingMs()
                remainingMsForWidget = remaining.coerceAtLeast(0L)
                maybeArmShake(remaining)
                val fadeMs = settings.currentSleepFadeSeconds * 1_000L
                if (remaining in 1..fadeMs && player.isPlaying()) {
                    applyFade(remaining, fadeMs)
                }
                if (remaining <= 0L) {
                    fire()
                    return@launch
                }
                // Screen off = no one can see the widget. Skip the render/push entirely — the
                // screen-on receiver forces one immediate correct re-render the moment it matters
                // again, so nothing is ever left stale, only un-rendered while unobserved.
                if (player.isScreenOn()) {
                    // COUNTDOWN mode needs no new data each tick — the widget recomputes remaining
                    // time at paint time from the already-persisted deadline, so a cheap countdown
                    // tick (re-renders only countdown-showing widgets) suffices. END_OF_CHAPTER has
                    // no fixed deadline (remaining shrinks with playback position, not just
                    // wall-clock time), so it still needs a state push each tick.
                    if (mode == PlaybackService.SLEEP_MODE_END_OF_CHAPTER) player.tickEndOfChapterWidget()
                    else player.tickCountdownWidget()
                }
                player.broadcastSleepState(mode, remainingMsForWidget)
                delay(1_000)
            }
        }
    }

    private fun computeRemainingMs(): Long = when (mode) {
        PlaybackService.SLEEP_MODE_COUNTDOWN -> endAtElapsedMs - SystemClock.elapsedRealtime()
        PlaybackService.SLEEP_MODE_END_OF_CHAPTER -> targetBookPositionMs - player.currentBookPositionMs()
        else -> 0L
    }

    /** Ramps volume down to silence as [remainingMs] approaches 0 within [fadeMs]. Captures the
     *  pre-fade volume once so it can be restored exactly (fade or cancel). Skips starting a NEW
     *  fade while the volume already reads reduced (< 99%) — most likely a transient audio-focus
     *  duck in progress, which this shouldn't fight or stomp on completion. */
    private fun applyFade(remainingMs: Long, fadeMs: Long) {
        if (!fadeActive) {
            if (player.getVolume() < 0.99f) {
                AppLog.d(LogCat.SLEEP) { "fade skipped: volume already reduced (${player.getVolume()}), likely an audio-focus duck in progress" }
                return
            }
            preFadeVolume = player.getVolume()
            fadeActive = true
            // Logged once per fade (the transition into fadeActive), not per tick — the fade
            // itself still updates every second until it fires.
            AppLog.d(LogCat.SLEEP) { "fade starting: remainingMs=$remainingMs fadeMs=$fadeMs preFadeVolume=$preFadeVolume" }
        }
        player.setVolume(preFadeVolume * fadeFraction(remainingMs, fadeMs))
    }

    private fun fire() {
        player.pause()
        if (fadeActive) {
            player.setVolume(preFadeVolume)
            fadeActive = false
        }
        mode = PlaybackService.SLEEP_MODE_OFF
        remainingMsForWidget = 0L
        AppLog.i(LogCat.SLEEP, "sleep timer fired, pausing")
        player.pushFullWidgetState()
        player.broadcastSleepState(mode, remainingMsForWidget)
        if (settings.currentSleepShakeEnabled) armShakeGraceWindow()
    }

    private fun maybeArmShake(remainingMs: Long) {
        if (!settings.currentSleepShakeEnabled) return
        if (remainingMs in 1..SHAKE_ARM_WINDOW_MS) shakeDetector.start()
    }

    /** After firing, keep listening for a shake a little longer (with a short wakelock so the
     *  sensor keeps delivering with the screen off) so "shake to resume" works right after the
     *  pause, not only in the countdown's final seconds. Best-effort past this window — no
     *  wakelock beyond it, so delivery isn't guaranteed with the screen off. */
    private fun armShakeGraceWindow() {
        shakeDetector.start()
        acquireWakeLock()
        graceJob = scope.launch {
            delay(SHAKE_GRACE_WINDOW_MS)
            shakeDetector.stop()
            releaseWakeLock()
            graceJob = null
        }
    }

    /** Wired as [ShakeDetector]'s onShake callback by whoever constructs this engine. */
    fun onShakeDetected() {
        AppLog.i(LogCat.SLEEP, "shake detected — extending sleep timer by ${resetMs / 60_000}min")
        graceJob?.cancel()
        graceJob = null
        releaseWakeLock()
        if (!player.isPlaying()) player.play()
        setTimer(PlaybackService.SLEEP_MODE_COUNTDOWN, resetMs, 0L)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = try {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Voyage:SleepShakeGrace").apply {
                setReferenceCounted(false)
                acquire(SHAKE_GRACE_WINDOW_MS)
            }
        } catch (e: Exception) {
            AppLog.e(LogCat.SLEEP, "sleep wakelock acquire failed", e)
            null
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Cancels everything in flight — call from PlaybackService.onDestroy(). */
    fun stop() {
        if (mode != PlaybackService.SLEEP_MODE_OFF) AppLog.i(LogCat.SLEEP, "stop: cancelling active timer (was $mode)")
        tickJob?.cancel()
        graceJob?.cancel()
        shakeDetector.stop()
        releaseWakeLock()
    }
}
