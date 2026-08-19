package com.betteraudio.ui.immersive.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.grid.LazyGridState
import coil3.compose.AsyncImage
import com.betteraudio.ui.home.HomeHeaderData
import com.betteraudio.ui.home.HomeSection
import com.betteraudio.ui.home.HomeViewMode
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import java.io.File

/** How tall the sharp cover window is before it starts scrolling away. */
private val HERO_HEIGHT = 250.dp

/**
 * Immersive Home's header: a sharp window into the app backdrop.
 *
 * The blurred cover behind every Immersive screen ([com.betteraudio.ui.components.AppBlurredBackdrop])
 * is already the now-playing book's artwork. So rather than stacking a separate "Continue" card
 * on top of it, this simply shows the *same image in focus* at the top of the library, and lets
 * it dissolve back into its own blur as the grid scrolls over it — the wallpaper coming into
 * focus and going out again. See `docs/immersive-redesign.html` (decision 01, option A).
 *
 * Both the parallax and the dissolve read [gridState] **inside deferred lambdas only**
 * (`graphicsLayer`), never in composition: the offset changes every frame of a scroll, and
 * reading it in the composable body would recompose the header — and therefore re-lay-out the
 * first row of the grid — on every one of those frames.
 */
@Composable
fun ImmersiveHomeHeader(
    data: HomeHeaderData,
    gridState: LazyGridState,
    onSort: () -> Unit
) {
    val hero = data.hero
    if (hero == null) {
        // Nothing has ever played: no artwork to bring into focus, so fall back to a quiet
        // library label rather than a 250dp hole. (The backdrop's own gradient wash is what
        // fills this case — see AppBlurredBackdrop's `hasArt` branch.)
        EmptyHeroHeader(data, onSort)
        return
    }

    // Scroll fraction 0..1 across the hero's own height, computed inside the draw lambdas below.
    val heroPx = with(androidx.compose.ui.platform.LocalDensity.current) { HERO_HEIGHT.toPx() }
    fun scrolledFraction(): Float {
        // Only item 0 (this header) matters; once the grid has scrolled past it the hero is gone
        // anyway and the fraction pins at 1.
        return if (gridState.firstVisibleItemIndex == 0)
            (gridState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
        else 1f
    }

    Box(
        Modifier
            .bleedHorizontally(16.dp)   // out past the grid's contentPadding, so the art is full-bleed
            .fillMaxWidth()
            .height(HERO_HEIGHT)
    ) {
        val context = LocalContext.current
        val coverModel = remember(hero.coverPath, hero.bookId) {
            hero.coverPath?.let {
                coil3.request.ImageRequest.Builder(context)
                    .data(File(it))
                    // Same "cover-<id>" key the grid cards and the full player use, so featuring a
                    // book here costs no extra decode.
                    .memoryCacheKey("cover-${hero.bookId}")
                    .build()
            }
        }
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val f = scrolledFraction()
                    // Half-speed: the art lags the grid, so it reads as a layer behind rather
                    // than a card travelling with the content.
                    translationY = f * heroPx * 0.5f
                    // Dissolve into the blurred backdrop it was cut from. Squared so it stays
                    // sharp for the first part of the scroll and then goes quickly.
                    alpha = 1f - (f * f)
                }
        )

        // Ramp the art down into the backdrop so there is no hard bottom edge to the window.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.36f to Color.Transparent,
                        0.74f to ImmersiveStyle.coverInk().copy(alpha = 0.58f),
                        1f to ImmersiveStyle.coverInk().copy(alpha = 0.94f)
                    )
                )
                .graphicsLayer { alpha = 1f - (scrolledFraction() * scrolledFraction()) }
        )

        // Sort — unfilled, so it sits on the art instead of punching a disc through it (Law 02).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onSort)
                .graphicsLayer { alpha = 1f - scrolledFraction() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort, "Sort & filter",
                Modifier.size(20.dp), tint = ImmersiveStyle.scrimText()
            )
        }

        if (data.scanning) {
            Row(
                Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    Modifier.size(14.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Scanning library…",
                    style = MaterialTheme.typography.labelSmall,
                    color = ImmersiveStyle.scrimText(muted = true)
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp)
                // The copy leaves faster than the art — by the time the first row of covers
                // reaches it, the title has already gone.
                .graphicsLayer { alpha = 1f - (scrolledFraction() * 1.6f).coerceIn(0f, 1f) }
        ) {
            hero.eyebrow?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = ImmersiveStyle.scrimText(muted = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
            }
            Text(
                hero.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveStyle.scrimText(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = hero.onOpen
                )
            )

            if (hero.progressFraction > 0f) {
                Spacer(Modifier.height(9.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(Pill)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(hero.progressFraction)
                            .height(2.dp)
                            .clip(Pill)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            Spacer(Modifier.height(9.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier
                        .clip(Pill)
                        .background(MaterialTheme.colorScheme.primary)
                        .pressScale()
                        .clickable(onClick = hero.onResume)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (hero.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (hero.isPlaying) "Playing" else if (hero.isLive) "Resume" else "Continue",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                hero.remainingLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = ImmersiveStyle.scrimText(muted = true)
                    )
                }
            }
        }
    }
}

/** Fresh install / nothing ever played: a plain label where the hero would be. */
@Composable
private fun EmptyHeroHeader(data: HomeHeaderData, onSort: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (data.section == HomeSection.EBOOKS) "Ebooks" else "Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveStyle.scrimText()
            )
            val noun = if (data.section == HomeSection.EBOOKS) "ebook" else when (data.viewMode) {
                HomeViewMode.BOOKS -> "book"
                HomeViewMode.SERIES -> "title"
                HomeViewMode.AUTHORS -> "author"
            }
            Text(
                if (data.scanning) "Scanning library…"
                else "${data.itemCount} $noun${if (data.itemCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = ImmersiveStyle.scrimText(muted = true)
            )
        }
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSort),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort, "Sort & filter",
                Modifier.size(20.dp), tint = ImmersiveStyle.scrimText()
            )
        }
    }
}

/**
 * Lets a lazy-grid item draw past the grid's horizontal `contentPadding`, so the hero's artwork
 * can be full-bleed while every other item stays inset. The item still *reports* the constrained
 * width, so the grid's own layout is unaffected — only the drawn content is wider and shifted
 * left by [inset].
 */
private fun Modifier.bleedHorizontally(inset: Dp) = this.layout { measurable, constraints ->
    val extra = inset.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = constraints.maxWidth + extra
        )
    )
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-inset.roundToPx(), 0)
    }
}
