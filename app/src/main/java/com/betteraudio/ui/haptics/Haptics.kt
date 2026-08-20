package com.betteraudio.ui.haptics

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * How much the app is allowed to buzz. A global user setting, because haptics are the one part of
 * a UI that a person cannot look away from.
 */
enum class HapticStrength(val label: String, val blurb: String) {
    OFF("Off", "Voyage never vibrates. Your system's own typing and gesture haptics are unaffected."),
    LIGHT(
        "Light",
        "Only the crisp, quiet feels. Nothing thuds, and dragging a slider or scrubber stops " +
            "ticking through its steps."
    ),
    FULL(
        "Full",
        "The whole vocabulary: taps, toggles, detents while you drag, and a fuller confirm when " +
            "something actually changes."
    );

    companion object {
        fun from(raw: String): HapticStrength = entries.firstOrNull { it.name == raw } ?: FULL
    }
}

/**
 * The app's haptic vocabulary.
 *
 * The rule behind it: **a haptic should describe what just happened, and weigh what it cost.**
 * A tap that reveals something is a light tick; a toggle is asymmetric so you can feel which way
 * it went without looking; a drag gets a grab, detents and a release, because that is what a
 * physical control does; and only a change that outlives the gesture earns a full confirm.
 *
 * Everything is played through [View.performHapticFeedback], never a raw `Vibrator`. That means
 * the OEM's own tuned waveform for each constant, obedience to the system haptics setting, and no
 * `VIBRATE` permission — at the price of a smaller palette on older releases, which [resolve]
 * degrades through deliberately rather than falling silent.
 */
enum class Feel {
    /** An ordinary control did its ordinary thing: a button, a row, a menu item, a chip. */
    Tap,

    /** One option out of several became the chosen one. Crisper and smaller than [Tap]. */
    Select,

    /** A switch went on. Deliberately not the same as [ToggleOff]. */
    ToggleOn,

    /** A switch went off. */
    ToggleOff,

    /** A drag has taken hold — the scrubber, a fast-scroll rail, an element in the widget editor. */
    Grab,

    /** A drag ended and its value was committed. */
    Release,

    /** One detent passed mid-drag: a slider step, a chapter boundary, a snap line. */
    Step,

    /** A drag hit a limit, or a list hit its end. Duller than [Step], so a wall feels like a wall. */
    Boundary,

    /** A sheet, overlay or menu opened. */
    Reveal,

    /** …and closed. */
    Dismiss,

    /** Durable state changed and the change stuck: a bookmark saved, a cover applied, an icon set. */
    Commit,

    /** Refused, blocked, or about to be destructive. */
    Warn,

    /** A long press reached its threshold. */
    LongPress,

    /** Play, pause, skip. The app's most-used physical control, and the only tap that gets weight. */
    Transport
}

/** A ceiling on [Feel.Step], so a fast drag across 200 detents cannot turn into a buzz. */
private const val MIN_STEP_GAP_MS = 26L

private fun resolve(feel: Feel, strength: HapticStrength): Int? {
    if (strength == HapticStrength.OFF) return null
    val sdk = Build.VERSION.SDK_INT
    val light = strength == HapticStrength.LIGHT
    return when (feel) {
        Feel.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
        Feel.Select -> HapticFeedbackConstants.CLOCK_TICK

        // TOGGLE_ON/OFF are the only pair the platform ships that differ from each other by
        // direction rather than by strength — worth the API 34 gate, because "did that go on or
        // off" is exactly the question a haptic can answer without the eyes.
        Feel.ToggleOn ->
            if (sdk >= 34 && !light) HapticFeedbackConstants.TOGGLE_ON
            else HapticFeedbackConstants.CLOCK_TICK
        Feel.ToggleOff ->
            if (sdk >= 34 && !light) HapticFeedbackConstants.TOGGLE_OFF
            else HapticFeedbackConstants.CLOCK_TICK

        Feel.Grab -> when {
            light -> HapticFeedbackConstants.CLOCK_TICK
            sdk >= 34 -> HapticFeedbackConstants.DRAG_START
            sdk >= 30 -> HapticFeedbackConstants.GESTURE_START
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        Feel.Release -> when {
            light -> HapticFeedbackConstants.CLOCK_TICK
            sdk >= 30 -> HapticFeedbackConstants.GESTURE_END
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }

        // The one feel Light drops outright: continuous detents are what make heavy haptics feel
        // heavy, and everything else in the vocabulary survives without them.
        Feel.Step -> when {
            light -> null
            sdk >= 34 -> HapticFeedbackConstants.SEGMENT_TICK
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        Feel.Boundary -> when {
            sdk >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
            sdk >= 30 -> HapticFeedbackConstants.GESTURE_END
            else -> HapticFeedbackConstants.CLOCK_TICK
        }

        Feel.Reveal -> when {
            sdk >= 30 -> HapticFeedbackConstants.GESTURE_START
            else -> HapticFeedbackConstants.CLOCK_TICK
        }
        Feel.Dismiss -> when {
            sdk >= 30 -> HapticFeedbackConstants.GESTURE_END
            else -> HapticFeedbackConstants.CLOCK_TICK
        }

        Feel.Commit -> when {
            light -> HapticFeedbackConstants.VIRTUAL_KEY
            sdk >= 30 -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        Feel.Warn -> when {
            sdk >= 30 -> HapticFeedbackConstants.REJECT
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        Feel.LongPress -> HapticFeedbackConstants.LONG_PRESS
        Feel.Transport -> when {
            light -> HapticFeedbackConstants.VIRTUAL_KEY
            sdk >= 30 -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.KEYBOARD_TAP
        }
    }
}

/** Plays the vocabulary. Obtain from [LocalHaptics]; never construct one per call site. */
@Stable
class Haptics internal constructor(
    private val view: View?,
    private val strength: HapticStrength
) {
    private var lastStepAt = 0L

    fun play(feel: Feel) {
        val target = view ?: return
        val constant = resolve(feel, strength) ?: return
        if (feel == Feel.Step) {
            val now = SystemClock.uptimeMillis()
            if (now - lastStepAt < MIN_STEP_GAP_MS) return
            lastStepAt = now
        }
        // No FLAG_IGNORE_GLOBAL_SETTING: a user who turned haptics off in Android meant it.
        target.performHapticFeedback(constant)
    }

    fun tap() = play(Feel.Tap)
    fun select() = play(Feel.Select)
    fun toggle(on: Boolean) = play(if (on) Feel.ToggleOn else Feel.ToggleOff)
    fun grab() = play(Feel.Grab)
    fun release() = play(Feel.Release)
    fun step() = play(Feel.Step)
    fun boundary() = play(Feel.Boundary)
    fun reveal() = play(Feel.Reveal)
    fun dismiss() = play(Feel.Dismiss)
    fun commit() = play(Feel.Commit)
    fun warn() = play(Feel.Warn)
    fun longPress() = play(Feel.LongPress)
    fun transport() = play(Feel.Transport)

    companion object {
        /** The no-op instance the CompositionLocal defaults to, so nothing crashes outside a
         *  provider (previews, tests, the widget's own composition). */
        val None = Haptics(null, HapticStrength.OFF)
    }
}

val LocalHaptics = staticCompositionLocalOf { Haptics.None }

/**
 * What a plain press inside this subtree feels like — read by the app-wide press hook in
 * `VoyageIndication`.
 *
 * [Feel.Tap] everywhere by default. A region whose presses mean something more specific declares
 * it once with [PressFeel] rather than editing every control inside it, and a control that plays
 * its own considered feel provides `null` so it cannot buzz twice — once as a generic tap and once
 * as, say, a toggle.
 */
val LocalPressFeel = staticCompositionLocalOf<Feel?> { Feel.Tap }

/** Declares what presses feel like for everything inside [content]. */
@Composable
fun PressFeel(feel: Feel?, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPressFeel provides feel, content = content)
}

@Composable
fun rememberHaptics(strength: HapticStrength): Haptics {
    val view = LocalView.current
    return remember(view, strength) { Haptics(view, strength) }
}
