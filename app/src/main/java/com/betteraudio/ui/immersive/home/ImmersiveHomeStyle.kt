package com.betteraudio.ui.immersive.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.betteraudio.data.model.HomeGridBook
import com.betteraudio.ui.home.HomeHeaderData
import com.betteraudio.ui.home.HomeStyle
import com.betteraudio.ui.home.LibraryTab
import com.betteraudio.ui.home.NowPlayingSignal
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.immersive.LocalHeroWindow
import com.betteraudio.ui.theme.Pill
import java.io.File

/** Home's Immersive style knobs — see HomeStyle for what these mean. */
object ImmersiveHomeStyle : HomeStyle {
    // ── Cover-first-pass slots (docs/immersive-redesign.html) ─────────────────────────────

    @Composable override fun Header(data: HomeHeaderData, gridState: LazyGridState, onSort: () -> Unit) =
        ImmersiveHomeHeader(data, gridState, onSort)

    /** Four filled chips laid across the artwork became four tracked labels with an accent
     *  underline on the active one — Law 02, no plates over the cover. Counts ride along as a
     *  dim superscript-ish suffix rather than widening each label into a pill. */
    @Composable override fun StatusTabs(
        selected: LibraryTab,
        counts: Map<LibraryTab, Int>,
        onSelect: (LibraryTab) -> Unit
    ) {
        val hero = LocalHeroWindow.current
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 10.dp, bottom = 8.dp)
                // The hero darkening ends just below this row and fades out rather than stopping
                // on a line. Publishing the row's live bottom is what makes the band scroll with
                // the content it darkens instead of sticking to a fixed screen position — it is
                // written on every scroll frame and read only inside the backdrop's draw lambda.
                .onGloballyPositioned { hero.scrimBottomPx.floatValue = it.boundsInRoot().bottom },
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            LibraryTab.entries.forEach { tab ->
                val isOn = selected == tab
                val count = counts[tab] ?: 0
                Column(
                    Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(tab) }
                        )
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            tab.label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 0.09.em,
                            color = if (isOn) ImmersiveStyle.scrimText()
                                    else ImmersiveStyle.scrimText(muted = true)
                        )
                        if (count > 0) {
                            Spacer(Modifier.width(3.dp))
                            Text(
                                count.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 8.sp,
                                color = ImmersiveStyle.scrimText(muted = true)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.5.dp)
                            .background(
                                if (isOn) MaterialTheme.colorScheme.primary else Color.Transparent,
                                Pill
                            )
                    )
                }
            }
        }
    }

    /** Starts higher up the card and stays transparent for longer than Material's hard band, so
     *  meaningfully more artwork survives under the same title. Darkens toward the cover's own
     *  ink rather than black (Law 03). */
    @Composable override fun cardScrim(): Brush {
        val base = scrimBase()
        return Brush.verticalGradient(
            0f to Color.Transparent,
            0.40f to base.copy(alpha = 0.30f),
            1f to base.copy(alpha = 0.90f)
        )
    }

    override val cardScrimFillFraction: Float? = 0.56f

    /** Cover-sampled glass with a hairline edge instead of an opaque black disc — it sits on the
     *  art rather than punching a hole through it. */
    @Composable override fun CardPlayButton(
        isPlaying: Boolean,
        contentDescription: String,
        onClick: () -> Unit
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = Color.White
            )
        }
    }

    override val nowPlayingSignal: NowPlayingSignal = NowPlayingSignal.EdgeHairline

    // ── Colour knobs ──────────────────────────────────────────────────────────────────────

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

    // Must match the card's ACTUAL clip shape (MaterialTheme.shapes.large = 24dp), which is what
    // Material's 24dp here has always done. Immersive said 28dp, so every cover morph out of a
    // grid card — Book Info, Series, and the full player when opened with nothing playing — began
    // 4dp rounder than the card it was growing out of, and the first frame visibly popped.
    override val cardCornerRadius: Dp = 24.dp

    // Near-invisible: the artwork IS the card now, so this is only a placeholder for the moment
    // before a cover decodes (and for books that have none). It used to be a 0.38-alpha plate
    // that stayed visible behind every cover's transparent edges.
    @Composable override fun cardBackgroundColor(): Color =
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.14f)
    // Was its own lerp(Black, primary, 0.10) — now the theme-wide token, so card scrims, glass
    // pills and the app backdrop all darken toward exactly the same cover-derived ink.
    @Composable override fun scrimBase(): Color = ImmersiveStyle.coverInk()
    @Composable override fun emptyIconBackground(): Color = ImmersiveStyle.cardHighColor()
    @Composable override fun scrimText(muted: Boolean): Color = ImmersiveStyle.scrimText(muted)
    // Immersive has no landscape layout — always today's 2.
    @Composable override fun gridColumns(): Int = 2

    override fun bookCoverModel(context: Context, book: HomeGridBook): Any? =
        book.coverArtPath?.let {
            coil3.request.ImageRequest.Builder(context)
                .data(File(it))
                .memoryCacheKey("cover-${book.id}")
                .build()
        }
}
