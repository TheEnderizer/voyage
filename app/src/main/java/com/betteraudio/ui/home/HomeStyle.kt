package com.betteraudio.ui.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.betteraudio.data.model.HomeGridBook

/**
 * The handful of visual knobs that differ between the Material You and Immersive Home screens —
 * everything else lives once in HomeScreenContent.kt. MaterialHomeStyle (ui.material.home) and
 * ImmersiveHomeStyle (ui.immersive.home) are the two implementations; see CLAUDE.md's theming
 * section for the split convention.
 */
interface HomeStyle {
    @Composable fun dialogContainerColor(): Color
    @Composable fun menuContainerColor(): Color
    @Composable fun headerIconButtonBackground(): Color
    @Composable fun filterChipColors(): SelectableChipColors
    @Composable fun filterChipBorder(selected: Boolean): BorderStroke?
    @Composable fun selectionHeaderColor(): Color
    @Composable fun selectionHeaderContentColor(): Color

    /** Corner radius reported to CoverBoundsRegistry as the morph-transition start radius —
     *  independent of the actual clip shape (MaterialTheme.shapes.large), which is identical
     *  across both themes. */
    val cardCornerRadius: Dp

    @Composable fun cardBackgroundColor(): Color
    @Composable fun scrimBase(): Color
    @Composable fun emptyIconBackground(): Color
    @Composable fun scrimText(muted: Boolean = false): Color

    /** Grid column count. Immersive has no landscape layout, so it always returns 2 — today's
     *  exact value — in both orientations; Material You widens in a landscape-shaped window (see
     *  MaterialHomeStyle/MaterialAdaptive.homeGridColumns). */
    @Composable fun gridColumns(): Int

    /** Coil model for a grid card's cover art. Both themes share a "cover-<id>" cache key with
     *  their full player's cover (see MaterialHomeStyle/ImmersiveHomeStyle), so the grid → full
     *  player morph (when opened directly with nothing already playing) reuses the decoded bitmap
     *  instead of redecoding — see coverCropMorph in MaterialMotion.kt. Book Info's own cover
     *  (BookInfoScreen.kt) shares this same key too, so grid → Book Info also reuses it. */
    fun bookCoverModel(context: Context, book: HomeGridBook): Any?
}
