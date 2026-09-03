package com.betteraudio.ui.companion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where the deck's dock travels *from* (docs/companion-redesign.html §03).
 *
 * Opening the companion is meant to be one continuous move rather than a surface arriving: the
 * player's transport does not disappear and reappear somewhere else, it goes somewhere. That
 * requires the deck to know the rectangle the controls currently occupy, and the player screens
 * are the only things that know it — so they publish it here and the deck reads it.
 *
 * This is the same shape as [com.betteraudio.ui.player.CoverBoundsRegistry], and for the same
 * reason: a morph needs a source rect from a composable that is not the one doing the morphing.
 */
@Stable
class CompanionMorphAnchors {
    /**
     * The player's transport row, in root coordinates.
     *
     * One anchor, not five. The dock's play button is deliberately the *same visual* as the
     * player's (accent circle in Immersive, M3 squircle in Material You), so moving the row as one
     * object already reads as those controls travelling; anchoring each button separately buys the
     * arc paths §03 draws, at the cost of a registration point per button per layout — and the
     * per-element version has to wait until it can be done for all four player layouts at once,
     * because a morph that only works in portrait is worse than one that works everywhere.
     */
    val transport = mutableStateOf(Rect.Zero)
}

@Composable
fun rememberCompanionMorphAnchors(): CompanionMorphAnchors = remember { CompanionMorphAnchors() }

/**
 * Marks a player element as the deck's morph source: publishes its bounds, and hands off to the
 * travelling dock as the deck opens.
 *
 * The hand-off is a fade over the first 15% rather than the instant cut `MiniPlayerBar` uses. That
 * cut is right there because the travelling copy is pixel-identical to the thing it replaces; here
 * the dock is a *different object* that happens to contain the same controls, so cutting the
 * source the moment travel begins leaves a visible hole for the rest of the animation. Fifteen
 * percent is short enough that the two are never both legible and long enough that neither
 * flickers.
 */
@Composable
fun Modifier.companionMorphSource(
    anchors: CompanionMorphAnchors,
    progress: State<Float>
): Modifier = this
    .onGloballyPositioned { anchors.transport.value = it.boundsInRoot() }
    .graphicsLayer {
        alpha = 1f - (progress.value / 0.15f).coerceIn(0f, 1f)
    }
