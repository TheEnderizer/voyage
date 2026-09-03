package com.betteraudio.ui.home

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.betteraudio.data.model.HomeGridBook

/**
 * Everything the Home screen needs to feature one book at the top of the library. Immersive's
 * cover-first hero renders it as a sharp window into the app backdrop; Material You ignores it
 * entirely and keeps its "Library" headline. Null when there is nothing to feature (fresh
 * install, nothing ever played).
 */
data class HomeHeroData(
    val bookId: Long,
    val title: String,
    /** "Foundation · Book 2", or just the author when the book is not in a series. */
    val eyebrow: String?,
    /** Title of the chapter the listener is currently in. Null when the book has no chapter data
     *  worth showing (a single-mark timeline names the whole file, which the title already says). */
    val chapterLabel: String?,
    val coverPath: String?,
    val progressFraction: Float,
    /** "6h 12m left", or null when the duration isn't known yet. */
    val remainingLabel: String?,
    val isPlaying: Boolean,
    /** Whether this is the live session or merely the last-played book (changes the CTA wording). */
    val isLive: Boolean,
    val onResume: () -> Unit,
    val onOpen: () -> Unit
)

/** What the header above the status tabs is given. [hero] is only consumed by Immersive. */
data class HomeHeaderData(
    val itemCount: Int,
    val viewMode: HomeViewMode,
    val section: HomeSection,
    val scanning: Boolean,
    val hero: HomeHeroData?
)

/**
 * How a grid card says "this is the book that's playing".
 *
 * Material You draws all three of the classic signals (accent border, corner badge, inset
 * progress bar). Immersive collapses them into one accent hairline welded to the card's bottom
 * edge which *is* the progress bar — three pieces of chrome over the artwork became one.
 */
enum class NowPlayingSignal { BorderAndBadge, EdgeHairline }

/**
 * The handful of visual knobs that differ between the Material You and Immersive Home screens —
 * everything else lives once in HomeScreenContent.kt. MaterialHomeStyle (ui.material.home) and
 * ImmersiveHomeStyle (ui.immersive.home) are the two implementations; see CLAUDE.md's theming
 * section for the split convention.
 *
 * The cover-first pass (docs/immersive-redesign.html) made Home genuinely diverge between the
 * two themes for the first time. Rather than splitting HomeScreenContent into two 1100-line
 * copies — the exact drift trap Book Info fell into, see CLAUDE.md — the divergent pieces became
 * composable slots on this interface. Both themes still run one screen implementation.
 */
interface HomeStyle {
    /** The block above the status tabs: Material You's "Library" headline + count + sort button,
     *  or Immersive's cover-first hero window. [gridState] is supplied so a header can react to
     *  scroll (Immersive parallaxes the hero and dissolves it into the app backdrop); Material
     *  You ignores it. */
    @Composable fun Header(data: HomeHeaderData, gridState: LazyGridState, onSort: () -> Unit)

    /** Library status tabs — filled FilterChips in Material You, a tracked text row with an
     *  accent underline in Immersive (Law 02: no plates over artwork). */
    @Composable fun StatusTabs(
        selected: LibraryTab,
        counts: Map<LibraryTab, Int>,
        onSelect: (LibraryTab) -> Unit
    )

    /** The scrim laid over the bottom of a grid card's art so its title stays readable.
     *  Material You keeps today's hard two-stop ramp sized to the text it covers;
     *  Immersive uses a three-stop ramp that starts higher and stays transparent for longer, so
     *  noticeably more artwork survives under the same text. */
    @Composable fun cardScrim(): Brush

    /** Fraction of the card's height the scrim occupies, or null to hug its own content — which
     *  is what Material You does today. A fixed fraction lets Immersive's gentler ramp begin well
     *  above the title without the gradient's steepness depending on how long the title is. */
    val cardScrimFillFraction: Float?

    /** The per-card play affordance. Material You: an opaque black disc. Immersive: cover-sampled
     *  glass with a hairline edge, so it sits on the art instead of punching a hole in it. */
    @Composable fun CardPlayButton(isPlaying: Boolean, contentDescription: String, onClick: () -> Unit)

    /** See [NowPlayingSignal]. */
    val nowPlayingSignal: NowPlayingSignal

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
