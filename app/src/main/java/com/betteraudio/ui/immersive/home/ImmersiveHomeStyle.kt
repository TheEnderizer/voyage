package com.betteraudio.ui.immersive.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.data.model.HomeGridBook
import com.betteraudio.ui.home.HomeStyle
import com.betteraudio.ui.immersive.ImmersiveStyle
import java.io.File

/** Home's Immersive style knobs — see HomeStyle for what these mean. */
object ImmersiveHomeStyle : HomeStyle {
    @Composable override fun dialogContainerColor(): Color = ImmersiveStyle.dialogColor()
    @Composable override fun menuContainerColor(): Color = ImmersiveStyle.menuColor()
    @Composable override fun headerIconButtonBackground(): Color =
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.38f)

    @Composable override fun filterChipColors(): SelectableChipColors =
        FilterChipDefaults.filterChipColors(containerColor = ImmersiveStyle.cardColor())
    @Composable override fun filterChipBorder(selected: Boolean): BorderStroke? = null

    @Composable override fun selectionHeaderColor(): Color =
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
    @Composable override fun selectionHeaderContentColor(): Color = MaterialTheme.colorScheme.onSurface

    override val cardCornerRadius: Dp = 28.dp
    @Composable override fun cardBackgroundColor(): Color =
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.38f) // frosted placeholder behind cover art
    @Composable override fun scrimBase(): Color = lerp(Color.Black, MaterialTheme.colorScheme.primary, 0.10f)
    @Composable override fun emptyIconBackground(): Color = ImmersiveStyle.cardHighColor()
    @Composable override fun scrimText(muted: Boolean): Color = ImmersiveStyle.scrimText(muted)

    override fun bookCoverModel(context: Context, book: HomeGridBook): Any? =
        book.coverArtPath?.let { File(it) }
}
