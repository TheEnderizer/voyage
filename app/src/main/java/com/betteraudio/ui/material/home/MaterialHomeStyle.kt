package com.betteraudio.ui.material.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.data.model.HomeGridBook
import com.betteraudio.ui.home.HomeHeaderData
import com.betteraudio.ui.home.HomeSection
import com.betteraudio.ui.home.HomeStyle
import com.betteraudio.ui.home.HomeViewMode
import com.betteraudio.ui.home.LibraryTab
import com.betteraudio.ui.home.NowPlayingSignal
import com.betteraudio.ui.material.MaterialAdaptive
import com.betteraudio.ui.material.MaterialStyle
import java.io.File
import com.betteraudio.ui.haptics.*

/** Home's Material You style knobs — see HomeStyle for what these mean. */
object MaterialHomeStyle : HomeStyle {
    // ── Cover-first-pass slots ────────────────────────────────────────────────────────────
    // These four moved out of HomeScreenContent when Immersive's Home diverged; the bodies below
    // are the previous shared implementations verbatim, so Material You renders exactly as before.

    /** Material You keeps its "Library" headline — [HomeHeaderData.hero] and the grid scroll
     *  state are Immersive's business and deliberately unused here. */
    @Composable override fun Header(data: HomeHeaderData, gridState: LazyGridState, onSort: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Library",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                val noun = when (data.viewMode) {
                    HomeViewMode.BOOKS -> "book"
                    HomeViewMode.SERIES -> "title"
                    HomeViewMode.AUTHORS -> "author"
                }
                Text(
                    if (data.scanning) "Scanning library…"
                    else "${data.itemCount} $noun${if (data.itemCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (data.scanning) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
            }
            // Search + Settings live on the floating nav pill; only Sort stays contextual here.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(headerIconButtonBackground())
                    .clickable(onClick = onSort),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort, "Sort & filter",
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    @Composable override fun StatusTabs(
        selected: LibraryTab,
        counts: Map<LibraryTab, Int>,
        onSelect: (LibraryTab) -> Unit
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LibraryTab.entries.forEach { tab ->
                val count = counts[tab] ?: 0
                HapticFilterChip(
                    selected = selected == tab,
                    onClick = { onSelect(tab) },
                    label = { Text(if (count > 0) "${tab.label} · $count" else tab.label) },
                    colors = filterChipColors(),
                    border = filterChipBorder(selected == tab)
                )
            }
        }
    }

    @Composable override fun cardScrim(): Brush =
        Brush.verticalGradient(listOf(Color.Transparent, scrimBase().copy(alpha = 0.82f)))

    override val cardScrimFillFraction: Float? = null   // hugs its own content, as before

    @Composable override fun CardPlayButton(
        isPlaying: Boolean,
        contentDescription: String,
        onClick: () -> Unit
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }
    }

    override val nowPlayingSignal: NowPlayingSignal = NowPlayingSignal.BorderAndBadge

    // ── Colour knobs ──────────────────────────────────────────────────────────────────────

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
    @Composable override fun gridColumns(): Int = MaterialAdaptive.homeGridColumns()

    override fun bookCoverModel(context: Context, book: HomeGridBook): Any? =
        book.coverArtPath?.let {
            coil3.request.ImageRequest.Builder(context)
                .data(File(it))
                .memoryCacheKey("cover-${book.id}")
                .build()
        }
}
