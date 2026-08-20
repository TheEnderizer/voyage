package com.betteraudio.ui.haptics

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import kotlinx.coroutines.launch

/**
 * The app's press indication: the standard Material ripple, plus a haptic tap.
 *
 * Provided once as `LocalIndication`, this covers every bare `Modifier.clickable` in the app —
 * cards, rows, pills, swatches, the lot — without a single call site knowing about haptics. The
 * Material components do NOT resolve their indication from this local (they construct `ripple()`
 * directly), which is why they get explicit wrappers in `HapticControls.kt` instead.
 *
 * It fires on [PressInteraction.Release], not on press-down: a finger that lands on a card and
 * then drags into a scroll emits Press followed by Cancel, and buzzing for that would mean the app
 * twitches every time you start scrolling the library.
 */
object VoyageIndication : IndicationNodeFactory {
    private val ripple = ripple()

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressHapticNode(interactionSource, ripple.create(interactionSource))

    // Identity equality: a single object, so an indication comparison never re-creates the node.
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}

private class PressHapticNode(
    private val interactionSource: InteractionSource,
    rippleNode: DelegatableNode
) : DelegatingNode(), CompositionLocalConsumerModifierNode {

    init {
        delegate(rippleNode)
    }

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    currentValueOf(LocalPressFeel)?.let { currentValueOf(LocalHaptics).play(it) }
                }
            }
        }
    }
}
