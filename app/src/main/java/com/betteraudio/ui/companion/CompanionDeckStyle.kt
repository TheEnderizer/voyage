package com.betteraudio.ui.companion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.PressFeel
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme

/**
 * Everything about the companion deck that differs between the two looks, and nothing else.
 *
 * ### Why this is a token file and not two `CompanionDeckFrame.kt` files
 *
 * CLAUDE.md's split convention says a screen whose *structure* differs per theme gets a full
 * implementation each, and that two screens which are copies of each other should share the frame
 * instead. The deck is squarely the second case: the four bands, the pane contents, the landscape
 * two-column split and every piece of behaviour are identical in both looks — what differs is
 * fills, shapes, and two small leaves ([DeckSpine], [DeckDockSurface]) where the *component* really
 * is a different component. Splitting the whole deck to express that would recreate exactly the
 * drift the Book Info / Series page consolidation was written to stop: a pane added in one file and
 * forgotten in the other.
 *
 * So the deck is one implementation, and this file is its per-theme half — the same shape as
 * `ui/components/SheetStyle.kt`'s `appSheetColor()`/`appDialogColor()`, which CLAUDE.md already
 * sanctions for exactly this.
 *
 * ### The two grounds
 *
 * **Immersive** — the deck is *translucent over the player it opened from*. The player's own
 * full-bleed cover backdrop keeps showing through, so the deck reads as the same room with the
 * furniture rearranged rather than a new screen that arrived. That is also why this file uses
 * [ImmersiveStyle.scrimText] and [ImmersiveStyle.cardColor], which CLAUDE.md warns are *wrong*
 * inside a sheet: those are for content drawn straight over blurred artwork, and unlike the old
 * `CompanionSheet` — which sat on an opaque `surfaceContainerLow` and therefore needed
 * `onSurface` — this deck genuinely is drawn over the artwork.
 *
 * **Material You** — the deck is an opaque tonal surface. There is no artwork to belong to, and a
 * translucent M3 panel over a tonal player just looks like a rendering bug.
 */

// ── ground ──────────────────────────────────────────────────────────────────────────────────

/**
 * The deck's own backdrop, drawn full-bleed under everything else.
 *
 * Immersive returns a gradient in the cover's own dark rather than a flat scrim, for the same
 * reason [ImmersiveStyle.backdropVeil] does: legibility should come from a shape — lighter where
 * the eye enters, heavier where the content is dense — not from a wall. It is deliberately heavier
 * than the app backdrop's veil, because the player's *controls* are behind it and have to stop
 * being readable, while the player's *artwork* should not.
 */
@Composable
fun deckGround(): Brush {
    return if (LocalAppTheme.current == AppTheme.IMMERSIVE) {
        val ink = ImmersiveStyle.coverInk()
        Brush.verticalGradient(
            0f to ink.copy(alpha = 0.74f),
            0.42f to ink.copy(alpha = 0.86f),
            1f to ink.copy(alpha = 0.94f)
        )
    } else {
        val surface = MaterialTheme.colorScheme.surface
        Brush.verticalGradient(0f to surface, 1f to surface)
    }
}

/** Body text on the deck's ground. */
@Composable
fun deckOn(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) ImmersiveStyle.scrimText()
    else MaterialTheme.colorScheme.onSurface

/** Secondary text: labels, captions, the half of a row that is not the value. */
@Composable
fun deckOnMuted(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) ImmersiveStyle.scrimText(muted = true)
    else MaterialTheme.colorScheme.onSurfaceVariant

/** Card / row / banner fill. */
@Composable
fun deckCardColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) ImmersiveStyle.cardColor()
    else MaterialTheme.colorScheme.surfaceContainer

/** Raised fill — chips, stat pills, the dock. */
@Composable
fun deckCardHighColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) ImmersiveStyle.cardHighColor()
    else MaterialTheme.colorScheme.surfaceContainerHigh

/** Panel corner radius. Immersive is rounder throughout; M3 keeps its own scale. */
@Composable
fun deckPanelShape(): Shape =
    RoundedCornerShape(if (LocalAppTheme.current == AppTheme.IMMERSIVE) 18.dp else 12.dp)

/**
 * Chip silhouette. Immersive pills everything; Material You uses M3's small container shape, so a
 * chip reads as an assist chip rather than as a tag.
 */
@Composable
fun deckChipShape(): Shape =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) RoundedCornerShape(50)
    else RoundedCornerShape(8.dp)

// ── the spine ───────────────────────────────────────────────────────────────────────────────

/**
 * The deck's one navigation control (docs/companion-redesign.html §04).
 *
 * This is the leaf where the two themes stop being the same component: Immersive draws a glass
 * segmented pill whose selected segment is *filled* with the accent, Material You draws an M3 tab
 * row whose selected tab is *underlined*. Both take the same parameters and neither knows anything
 * about what the destinations mean.
 *
 * [vertical] is the landscape rail: the same control turned on its side, so the two-column deck
 * navigates from the same place the portrait deck does rather than growing a second idiom.
 */
@Composable
fun DeckSpine(
    destinations: List<CompanionDestination>,
    selected: CompanionDestination,
    onSelect: (CompanionDestination) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false
) {
    if (LocalAppTheme.current == AppTheme.IMMERSIVE) {
        ImmersiveSpine(destinations, selected, onSelect, modifier, vertical)
    } else {
        MaterialSpine(destinations, selected, onSelect, modifier, vertical)
    }
}

/**
 * Immersive: a glass pill with a travelling accent lozenge behind the selected segment.
 *
 * The lozenge slides rather than cross-fading because the theme's whole language is objects moving
 * over artwork — a segment that simply lights up would be the one control on the deck that changes
 * by repainting instead of by moving.
 */
@Composable
private fun ImmersiveSpine(
    destinations: List<CompanionDestination>,
    selected: CompanionDestination,
    onSelect: (CompanionDestination) -> Unit,
    modifier: Modifier,
    vertical: Boolean
) {
    val index = destinations.indexOf(selected).coerceAtLeast(0)
    val slide by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = com.betteraudio.ui.theme.MotionTokens.floatSpatial,
        label = "spineLozenge"
    )
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    val muted = deckOnMuted()

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(50))
            .background(deckCardColor())
            .padding(3.dp)
    ) {
        val n = destinations.size.coerceAtLeast(1)
        val slotW = if (vertical) maxWidth else maxWidth / n
        val slotH = if (vertical) maxHeight / n else maxHeight
        Box(
            Modifier
                .offset(
                    x = if (vertical) 0.dp else slotW * slide,
                    y = if (vertical) slotH * slide else 0.dp
                )
                .width(slotW)
                .height(slotH)
                .clip(RoundedCornerShape(50))
                .background(accent)
        )
        SpineLabels(destinations, selected, onSelect, vertical, slotW, slotH) { isSelected ->
            if (isSelected) onAccent else muted
        }
    }
}

/**
 * Material You: an M3 tab row. The indicator is a bar under (or beside) the selected tab and the
 * label takes the accent — the stock M3 idiom, so the deck reads as part of the same app as
 * Settings' section list rather than as a bespoke control.
 */
@Composable
private fun MaterialSpine(
    destinations: List<CompanionDestination>,
    selected: CompanionDestination,
    onSelect: (CompanionDestination) -> Unit,
    modifier: Modifier,
    vertical: Boolean
) {
    val index = destinations.indexOf(selected).coerceAtLeast(0)
    val slide by animateFloatAsState(
        targetValue = index.toFloat(),
        animationSpec = com.betteraudio.ui.theme.MotionTokens.floatSpatial,
        label = "spineIndicator"
    )
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    BoxWithConstraints(modifier) {
        val n = destinations.size.coerceAtLeast(1)
        val slotW = if (vertical) maxWidth else maxWidth / n
        val slotH = if (vertical) maxHeight / n else maxHeight
        SpineLabels(destinations, selected, onSelect, vertical, slotW, slotH) { isSelected ->
            if (isSelected) accent else muted
        }
        // Indicator: a 3dp bar on the edge the content sits away from — under the row in portrait,
        // along the pane-facing side of the rail in landscape.
        if (vertical) {
            Box(
                Modifier
                    .offset(y = slotH * slide)
                    .align(Alignment.TopEnd)
                    .width(3.dp)
                    .height(slotH)
                    .padding(vertical = 8.dp)
                    .background(accent, RoundedCornerShape(3.dp))
            )
        } else {
            Box(
                Modifier
                    .offset(x = slotW * slide)
                    .align(Alignment.BottomStart)
                    .width(slotW)
                    .height(3.dp)
                    .padding(horizontal = slotW * 0.22f)
                    .background(accent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            )
        }
    }
}

/** The labels themselves — identical in both looks, so they are written once. */
@Composable
private fun SpineLabels(
    destinations: List<CompanionDestination>,
    selected: CompanionDestination,
    onSelect: (CompanionDestination) -> Unit,
    vertical: Boolean,
    slotW: Dp,
    slotH: Dp,
    colorFor: @Composable (Boolean) -> Color
) {
    val content: @Composable () -> Unit = {
        for (destination in destinations) {
            val isSelected = destination == selected
            // A bare `clickable` under VoyageIndication already fires LocalPressFeel; PressFeel
            // only re-points it at Select, since the whole spine is a selection, not a tap.
            PressFeel(Feel.Select) {
                Box(
                    Modifier
                        .width(slotW)
                        .height(slotH)
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(destination) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = destination.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = colorFor(isSelected),
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    if (vertical) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Top) { content() }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { content() }
    }
}

// ── the dock's own surface ──────────────────────────────────────────────────────────────────

/**
 * The slab the transport sits on.
 *
 * Immersive floats it: inset from every edge, no rim, carried by a wide soft shadow — the same
 * borderless treatment the mini player now uses, and for the same reason (a large surface with a
 * drawn outline reads as a card, not as glass). Material You seats it: a tonal container with M3's
 * own elevation, because that is how M3 says "this is above the content".
 */
@Composable
fun DeckDockSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val immersive = LocalAppTheme.current == AppTheme.IMMERSIVE
    val shape = RoundedCornerShape(if (immersive) 24.dp else 18.dp)
    Box(
        modifier
            .shadow(if (immersive) 20.dp else 6.dp, shape, clip = false)
            .clip(shape)
            .background(if (immersive) ImmersiveStyle.cardHighColor() else MaterialTheme.colorScheme.surfaceContainerHigh)
    ) { content() }
}

/** Full-bleed ground for the deck, drawn before anything else. */
@Composable
fun DeckGround(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(deckGround()))
}
