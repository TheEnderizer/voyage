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

    /** Coil model for a grid card's cover art — Material shares a cache key with the Book Info
     *  cover so the grid → Book Info morph reuses the decoded bitmap; Immersive loads plain. */
    fun bookCoverModel(context: Context, book: HomeGridBook): Any?
}
