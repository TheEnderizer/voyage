package com.betteraudio.ui.haptics

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MenuItemColors
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SelectableChipElevation
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import kotlin.math.roundToInt

/**
 * Voyage's own copies of the Material controls, each carrying the [Feel] its interaction deserves.
 *
 * These exist because the Material components build their own `ripple()` rather than resolving
 * `LocalIndication`, so the app-wide press hook in [VoyageIndication] never reaches them. Rather
 * than sprinkle `haptics.play(...)` through several hundred call sites, the call sites use these
 * and the decision about how a button should feel lives in exactly one file.
 *
 * Every one of them shuts the generic press hook off for its own subtree
 * ([LocalHapticPressEnabled]), so a control that plays a considered feel can never also fire the
 * default tap underneath it.
 *
 * The signatures cover what this app actually passes, not the whole Material surface — a call site
 * needing another parameter will fail to compile, which is the right place to notice.
 */

@Composable
private fun Muted(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPressFeel provides null, content = content)
}

// ── Buttons ──────────────────────────────────────────────────────────────────

@Composable
fun HapticButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    feel: Feel = Feel.Tap,
    content: @Composable RowScope.() -> Unit
) {
    val haptics = LocalHaptics.current
    Muted {
        Button(
            onClick = { haptics.play(feel); onClick() },
            modifier = modifier, enabled = enabled, shape = shape, colors = colors,
            elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = interactionSource, content = content
        )
    }
}

@Composable
fun HapticTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null,
    feel: Feel = Feel.Tap,
    content: @Composable RowScope.() -> Unit
) {
    val haptics = LocalHaptics.current
    Muted {
        TextButton(
            onClick = { haptics.play(feel); onClick() },
            modifier = modifier, enabled = enabled, shape = shape, colors = colors,
            elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = interactionSource, content = content
        )
    }
}

@Composable
fun HapticOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    feel: Feel = Feel.Tap,
    content: @Composable RowScope.() -> Unit
) {
    val haptics = LocalHaptics.current
    Muted {
        OutlinedButton(
            onClick = { haptics.play(feel); onClick() },
            modifier = modifier, enabled = enabled, shape = shape, colors = colors,
            elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = interactionSource, content = content
        )
    }
}

@Composable
fun HapticFilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(),
    border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: MutableInteractionSource? = null,
    feel: Feel = Feel.Tap,
    content: @Composable RowScope.() -> Unit
) {
    val haptics = LocalHaptics.current
    Muted {
        FilledTonalButton(
            onClick = { haptics.play(feel); onClick() },
            modifier = modifier, enabled = enabled, shape = shape, colors = colors,
            elevation = elevation, border = border, contentPadding = contentPadding,
            interactionSource = interactionSource, content = content
        )
    }
}

@Composable
fun HapticIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    interactionSource: MutableInteractionSource? = null,
    feel: Feel = Feel.Tap,
    content: @Composable () -> Unit
) {
    val haptics = LocalHaptics.current
    Muted {
        IconButton(
            onClick = { haptics.play(feel); onClick() },
            modifier = modifier, enabled = enabled, colors = colors,
            interactionSource = interactionSource, content = content
        )
    }
}

// ── Choices ──────────────────────────────────────────────────────────────────

@Composable
fun HapticSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) {
    val haptics = LocalHaptics.current
    Muted {
        Switch(
            checked = checked,
            // Asymmetric on purpose: on and off feel different, so the state is legible through
            // the fingertip alone.
            onCheckedChange = onCheckedChange?.let { cb -> { next -> haptics.toggle(next); cb(next) } },
            modifier = modifier, thumbContent = thumbContent, enabled = enabled,
            colors = colors, interactionSource = interactionSource
        )
    }
}

@Composable
fun HapticCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) {
    val haptics = LocalHaptics.current
    Muted {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange?.let { cb -> { next -> haptics.toggle(next); cb(next) } },
            modifier = modifier, enabled = enabled, colors = colors,
            interactionSource = interactionSource
        )
    }
}

@Composable
fun HapticRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: RadioButtonColors = RadioButtonDefaults.colors(),
    interactionSource: MutableInteractionSource? = null
) {
    val haptics = LocalHaptics.current
    Muted {
        RadioButton(
            selected = selected,
            // Re-picking what is already picked changes nothing, so it says nothing.
            onClick = onClick?.let { cb -> { if (!selected) haptics.select(); cb() } },
            modifier = modifier, enabled = enabled, colors = colors,
            interactionSource = interactionSource
        )
    }
}

@Composable
fun HapticFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = androidx.compose.material3.FilterChipDefaults.shape,
    colors: SelectableChipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(),
    elevation: SelectableChipElevation? = androidx.compose.material3.FilterChipDefaults.filterChipElevation(),
    border: BorderStroke? = androidx.compose.material3.FilterChipDefaults.filterChipBorder(enabled, selected),
    interactionSource: MutableInteractionSource? = null
) {
    val haptics = LocalHaptics.current
    Muted {
        FilterChip(
            selected = selected,
            onClick = { haptics.select(); onClick() },
            label = label, modifier = modifier, enabled = enabled,
            leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            shape = shape, colors = colors, elevation = elevation, border = border,
            interactionSource = interactionSource
        )
    }
}

@Composable
fun HapticDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MenuItemColors = MenuDefaults.itemColors(),
    contentPadding: PaddingValues = MenuDefaults.DropdownMenuItemContentPadding,
    interactionSource: MutableInteractionSource? = null,
    feel: Feel = Feel.Tap
) {
    val haptics = LocalHaptics.current
    Muted {
        DropdownMenuItem(
            text = text,
            onClick = { haptics.play(feel); onClick() },
            modifier = modifier, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            enabled = enabled, colors = colors, contentPadding = contentPadding,
            interactionSource = interactionSource
        )
    }
}

// ── Continuous ───────────────────────────────────────────────────────────────

/**
 * A slider that behaves like a physical one: it takes hold when you touch it, ticks as it passes a
 * detent, thumps when it reaches either end, and lets go when you do.
 *
 * Detents come from [steps] where the caller declared them; a continuous slider is quantised into
 * [continuousDetents] notches purely for the feel, so dragging still has grain without the value
 * being rounded. The tick is emitted from the value change itself rather than from a gesture
 * callback, which is what keeps it in step with what the thumb is actually doing.
 */
@Composable
fun HapticSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
    continuousDetents: Int = 24
) {
    val haptics = LocalHaptics.current
    // Held outside composition: the value callback fires far more often than a recomposition, and
    // the comparison has to be against the immediately preceding notch, not a frame-old snapshot.
    val state = remember { SliderHapticState() }
    // Material's Slider wants a non-null source; hoisted unconditionally so the remember is never
    // skipped when a caller starts or stops supplying its own.
    val fallbackSource = remember { MutableInteractionSource() }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val notches = if (steps > 0) steps + 1 else continuousDetents

    Muted {
        Slider(
            value = value,
            onValueChange = { next ->
                val notch = (((next - valueRange.start) / span) * notches).roundToInt()
                if (!state.dragging) {
                    state.dragging = true
                    state.notch = notch
                    haptics.grab()
                } else if (notch != state.notch) {
                    state.notch = notch
                    val atEnd = next <= valueRange.start + 1e-4f || next >= valueRange.endInclusive - 1e-4f
                    if (atEnd) haptics.boundary() else haptics.step()
                }
                onValueChange(next)
            },
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = {
                if (state.dragging) { state.dragging = false; haptics.release() }
                onValueChangeFinished?.invoke()
            },
            colors = colors,
            interactionSource = interactionSource ?: fallbackSource
        )
    }
}

private class SliderHapticState {
    var dragging = false
    var notch = Int.MIN_VALUE
}
