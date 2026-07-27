package com.betteraudio.ui.material.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.data.model.HomeGridBook
import com.betteraudio.ui.home.HomeStyle
import com.betteraudio.ui.material.MaterialStyle
import java.io.File

/** Home's Material You style knobs — see HomeStyle for what these mean. */
object MaterialHomeStyle : HomeStyle {
    @Composable override fun dialogContainerColor(): Color = AlertDialogDefaults.containerColor
    @Composable override fun menuContainerColor(): Color = MenuDefaults.containerColor
    @Composable override fun headerIconButtonBackground(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

    @Composable override fun filterChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors()
    @Composable override fun filterChipBorder(selected: Boolean): BorderStroke? =
        FilterChipDefaults.filterChipBorder(enabled = true, selected = selected)

    @Composable override fun selectionHeaderColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh
    @Composable override fun selectionHeaderContentColor(): Color =
        MaterialTheme.colorScheme.contentColorFor(selectionHeaderColor())

    override val cardCornerRadius: Dp = 24.dp
    @Composable override fun cardBackgroundColor(): Color = MaterialTheme.colorScheme.surfaceContainer
    @Composable override fun scrimBase(): Color = Color.Black
    @Composable override fun emptyIconBackground(): Color = MaterialTheme.colorScheme.surfaceContainerHigh
    @Composable override fun scrimText(muted: Boolean): Color = MaterialStyle.scrimText(muted)

    override fun bookCoverModel(context: Context, book: HomeGridBook): Any? =
        book.coverArtPath?.let {
            coil3.request.ImageRequest.Builder(context)
                .data(File(it))
                .memoryCacheKey("cover-${book.id}")
                .build()
        }
}
