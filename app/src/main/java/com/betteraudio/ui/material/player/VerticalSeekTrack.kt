package com.betteraudio.ui.material.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * The landscape player's seek control: a vertical slider (see [rotateVertical] — Material3 1.4.0
 * ships a `VerticalSlider`, but it is `internal` in Kotlin visibility despite public JVM bytecode
 * and is not callable from app code) with the remaining time above it and the elapsed time below,
 * so the numbers sit at the ends of the run they describe.
 *
 * Bottom = start, top = end — dragging DOWN rewinds, dragging UP fast-forwards (see
 * [rotateVertical]'s doc for the derivation). The landscape transport rail is ordered to agree
 * with this: up is forward, everywhere on that rail.
 *
 * Serves BOTH modes the horizontal sliders in `PlayerScreen.kt` do: pass the chapter's duration +
 * start offset for chapter seek, or the book's total + 0 for whole-book seek. The caller keeps
 * doing the ms math; this only ever speaks in fractions.
 *
 * The live drag value is read ONLY inside this composable (for the two labels), so scrubbing
 * recomposes this Column and nothing above it.
 */
@Composable
internal fun VerticalSeekTrack(
    /** Live position as 0..1 of [durationMs]. Ignored while the user is dragging. */
    fraction: Float,
    durationMs: Long,
    /** Absolute ms the fraction's 0 point corresponds to (chapter start, or 0 for whole-book).
     *  Used only to build the accessibility description; the labels are relative. */
    baseMs: Long,
    /** Absolute ms to seek to, computed by the caller from the released fraction. */
    onSeekFraction: (Float) -> Unit,
    /** Fired once when a scrub gesture begins — the caller stashes the pre-scrub position. */
    onScrubStart: () -> Unit,
    colors: SliderColors,
    labelColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Caption above the rail ("Chapter"/"Book"). Landscape shows both rails side by side, so each
     *  has to say which run it measures; pass null for a lone rail that needs no disambiguation. */
    label: String? = null,
) {
    // Same drag pattern as the horizontal chapter/book sliders in PlayerScreen.kt: while
    // dragFrac is non-null the thumb follows the finger but playback keeps running from the
    // original spot; the seek (and jump-history record) happens once, on release. This also
    // captures a TAP correctly (onValueChange fires for a tap too, not just a drag), so a
    // tap-seek's scrub origin is never silently dropped.
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val displayFrac = dragFrac ?: fraction.coerceIn(0f, 1f)
    val displayMs = (displayFrac * durationMs).toLong()

    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        label?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = labelColor.copy(alpha = 0.75f)
            )
        }
        Text(
            "-${formatDuration((durationMs - displayMs).coerceAtLeast(0L))}",
            style = MaterialTheme.typography.labelMedium,
            color = labelColor
        )
        Slider(
            value = displayFrac,
            onValueChange = { f ->
                if (dragFrac == null) onScrubStart()
                dragFrac = f
            },
            onValueChangeFinished = {
                dragFrac?.let { onSeekFraction(it) }
                dragFrac = null
            },
            enabled = enabled,
            colors = colors,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .rotateVertical()
                // M3's own slider semantics describe the value as a percentage; audiobook users
                // want a time. Overriding stateDescription keeps the setProgress action and the
                // adjust gestures M3 already wires up, and only replaces the readout.
                .semantics {
                    contentDescription = "Playback position"
                    stateDescription =
                        "${formatDurationHuman(baseMs + displayMs)} of " +
                        formatDurationHuman(baseMs + durationMs)
                }
        )
        Text(
            formatDuration(displayMs),
            style = MaterialTheme.typography.labelMedium,
            color = labelColor
        )
    }
}

/**
 * Turns a normal horizontal M3 [Slider] into a vertical one. Measures the child with its width
 * and height constraints SWAPPED (so its natural "length" is bounded by the available height, and
 * its "thickness" by the available width), reports the rotated (thickness x length) footprint to
 * the parent, and rotates the unrotated child 90° about its own center via
 * [androidx.compose.ui.layout.Placeable.PlacementScope.placeWithLayer] — rotating about the
 * center lines the rotated box up with the reported footprint with no separate offset needed.
 *
 * Direction — the rotation must be **-90°, not +90°**. Compose's `rotationZ` is positive-clockwise
 * in a y-down coordinate system, i.e. a point (x, y) about the center maps to (x·cosθ - y·sinθ,
 * x·sinθ + y·cosθ). At θ = +90° the slider's LEFT edge (value = 0, local x = -length/2) maps to
 * (0, -length/2) — the TOP of the rotated box — giving a bar that fills downwards from the top.
 * At θ = -90° that same left edge maps to (0, +length/2), the BOTTOM, so bottom = 0 and top = 1:
 * a normal vertical bar that fills upwards, with no `reverseDirection`-equivalent flag needed on
 * the (rotation-unaware) [Slider] itself.
 */
private fun Modifier.rotateVertical(): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(
        Constraints(
            minWidth = constraints.minHeight, maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth, maxHeight = constraints.maxWidth
        )
    )
    layout(p.height, p.width) {
        val px = (p.height - p.width) / 2
        val py = (p.width - p.height) / 2
        p.placeWithLayer(px, py) { rotationZ = -90f }
    }
}
