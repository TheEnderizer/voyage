package com.betteraudio.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.HapticSlider
import com.betteraudio.ui.haptics.LocalHaptics

/**
 * The app's seek bar, in whichever design the user picked — shared by **both** looks.
 *
 * It used to be `ImmersiveScrubber`, private to the Immersive player, while Material You drew a
 * bare `HapticSlider`. That meant the four painted designs were unreachable for half the users and
 * the Material slider was unreachable for the other half, for no reason either theme could point
 * at: the designs only ever needed an accent and a track colour. This is the merge of the two —
 * one gesture contract, one set of haptics, [ScrubberStyle] choosing the paint.
 *
 * What the painted designs get, which a Material `Slider` cannot express:
 *  - **The whole height is the target.** The rail itself is 6dp — far too thin to hit — so the
 *    28dp-plus box takes the touch: a tap anywhere seeks there, a drag scrubs continuously.
 *  - **A bead, not a thumb.** The playhead stands proud of the rail with a soft accent glow, so it
 *    reads as lit from the artwork and does not cover the track the way a 20dp circle does.
 *  - **It swells under the thumb.** Touch it and rail, bead and glow grow together on one spring,
 *    so the control acknowledges the touch instead of merely following it.
 *
 * [ScrubberStyle.CLASSIC] takes the other branch and renders the real M3 Slider, because that is
 * the one design whose visuals belong to Material rather than to us — see [ScrubberStyle].
 *
 * The contract is the same either way and matches what both players already spoke: [onScrubStart]
 * fires once as a gesture begins (the caller stashes its pre-scrub position for jump history),
 * [onScrub] streams the dragged fraction, and [onScrubEnd] carries the final one — so the caller
 * never has to read back the drag state it just wrote.
 */
@Composable
fun VoyageScrubber(
    /** Live position as 0..1. Ignored while a drag is in flight (the caller feeds back its own
     *  drag fraction, exactly as it did for the sliders this replaced). */
    fraction: Float,
    accent: Color,
    trackColor: Color,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier,
    style: ScrubberStyle = LocalScrubberStyle.current,
    enabled: Boolean = true,
    /** Only consulted for [ScrubberStyle.CLASSIC]; the painted designs colour themselves from
     *  [accent] and [trackColor]. Null builds one from those two, so a caller with no scheme of
     *  its own still gets a bar that matches the rest of the screen. */
    sliderColors: SliderColors? = null
) {
    val f = fraction.coerceIn(0f, 1f)

    if (style == ScrubberStyle.CLASSIC) {
        // Slider reports continuous change plus a single "finished", which maps straight onto the
        // start/stream/commit contract above — no drag bookkeeping beyond "has it begun".
        var sliding by remember { mutableStateOf(false) }
        // The released value is read back from here, not from the composed [fraction]: "finished"
        // can arrive before the recomposition carrying the last streamed value, and committing a
        // frame-old fraction is a seek to the wrong place. A snapshot write is visible immediately
        // on the same thread, so this always holds what the thumb last reported.
        val latest = remember { mutableFloatStateOf(f) }
        val colors = sliderColors ?: SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = trackColor
        )
        HapticSlider(
            value = f,
            onValueChange = { v ->
                if (!sliding) { sliding = true; onScrubStart() }
                latest.floatValue = v
                onScrub(v)
            },
            onValueChangeFinished = { sliding = false; onScrubEnd(latest.floatValue) },
            enabled = enabled,
            colors = colors,
            modifier = modifier.fillMaxWidth()
        )
        return
    }

    var dragging by remember { mutableStateOf(false) }
    // 0 at rest, 1 while the thumb is down — drives every dimension at once, so the swell reads as
    // one object reacting rather than four properties animating.
    val swell by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "scrubberSwell"
    )
    val haptics = LocalHaptics.current
    // Detents for a control with no steps of its own. Coarse enough that a slow drag has grain
    // rather than a hum, fine enough that a fast one still reports distance travelled.
    val detents = 40
    var lastDetent by remember { mutableIntStateOf(Int.MIN_VALUE) }

    // Both gesture blocks below are keyed on `Unit`, so they are started ONCE and keep running
    // across every later recomposition — which means whatever they captured directly, they keep.
    // These callbacks close over the CURRENT CHAPTER at the call site, so a directly-captured
    // onScrubEnd kept mapping the drag into whichever chapter happened to be playing when the
    // block started: pick a new chapter from the list, drag this scrubber, and playback jumped
    // back into the old one — the scrubber, the pill and the time row all showing the new chapter
    // the whole time. Reading them through rememberUpdatedState keeps the gesture coroutine alive
    // (re-keying it would cancel an in-flight drag) while still calling the latest lambda. The
    // Material sliders never had this: Slider does the same internally.
    val latestScrubStart = rememberUpdatedState(onScrubStart)
    val latestScrub = rememberUpdatedState(onScrub)
    val latestScrubEnd = rememberUpdatedState(onScrubEnd)
    val latestEnabled = rememberUpdatedState(enabled)

    Canvas(
        modifier
            .fillMaxWidth()
            // Each design declares the height it needs — Horizon's curve wants room the hairline
            // does not — and the 28dp floor keeps every one of them a comfortable target.
            .height(style.height)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (!latestEnabled.value) return@detectTapGestures
                    val target = (offset.x / size.width).coerceIn(0f, 1f)
                    haptics.play(Feel.Select)
                    latestScrubStart.value()
                    latestScrub.value(target)
                    latestScrubEnd.value(target)
                }
            }
            .pointerInput(Unit) {
                // Tracked here rather than in composition: the drag callbacks need the latest
                // value synchronously on release, and a recomposition may not have run yet.
                var latest = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (!latestEnabled.value) return@detectHorizontalDragGestures
                        latest = (offset.x / size.width).coerceIn(0f, 1f)
                        dragging = true
                        lastDetent = (latest * detents).toInt()
                        haptics.play(Feel.Grab)
                        latestScrubStart.value()
                        latestScrub.value(latest)
                    },
                    onDragEnd = {
                        if (!dragging) return@detectHorizontalDragGestures
                        dragging = false
                        haptics.play(Feel.Release)
                        latestScrubEnd.value(latest)
                    },
                    onDragCancel = {
                        if (!dragging) return@detectHorizontalDragGestures
                        dragging = false
                        haptics.play(Feel.Release)
                        latestScrubEnd.value(latest)
                    },
                    onHorizontalDrag = { change, _ ->
                        if (!dragging) return@detectHorizontalDragGestures
                        change.consume()
                        latest = (change.position.x / size.width).coerceIn(0f, 1f)
                        val detent = (latest * detents).toInt()
                        if (detent != lastDetent) {
                            lastDetent = detent
                            // The ends are walls, not notches: running out of chapter should feel
                            // different from crossing into the next tenth of it.
                            if (latest <= 0.0005f || latest >= 0.9995f) haptics.play(Feel.Boundary)
                            else haptics.play(Feel.Step)
                        }
                        latestScrub.value(latest)
                    }
                )
            }
    ) {
        drawScrubber(style, f, swell, accent, trackColor)
    }
}
