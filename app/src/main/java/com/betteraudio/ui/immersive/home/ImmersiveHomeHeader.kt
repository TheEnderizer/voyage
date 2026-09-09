package com.betteraudio.ui.immersive.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.home.HomeHeaderData
import com.betteraudio.ui.home.HomeSection
import com.betteraudio.ui.home.HomeViewMode
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.immersive.LocalHeroWindow
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale

/** How tall the sharp region of the backdrop is before the library grid begins. */
private val HERO_HEIGHT = 250.dp

/**
 * Immersive Home's header.
 *
 * It draws **no artwork of its own**. The cover the user sees at the top of the library is the
 * app-wide backdrop itself, rendered sharp in this region and blurred everywhere else — see
 * [com.betteraudio.ui.immersive.HeroWindowState] for why the window is driven from the backdrop
 * rather than drawn here. This composable contributes only the copy that sits on it (series
 * eyebrow, title, progress, resume) and the measurements the backdrop needs.
 *
 * Scrolling ramps the window's blur up to the backdrop's own, so the sharp region dissolves *by
 * becoming the background* — it never slides, and there is no second image to fade out.
 */
@Composable
fun ImmersiveHomeHeader(
    data: HomeHeaderData,
    gridState: LazyGridState,
    onSort: () -> Unit
) {
    val hero = LocalHeroWindow.current
    val heroData = data.hero

    // Only says whether there is a book to feature. Whether the window is SHOWN is ANDed with
    // the route in MainActivity, so leaving Home kills it at the moment navigation starts rather
    // than when Home finally leaves composition at the end of the exit animation.
    DisposableEffect(heroData != null) {
        hero.hasHero = heroData != null
        onDispose { hero.reset() }
    }

    if (heroData == null) {
        EmptyHeroHeader(data, onSort)
        return
    }

    val density = LocalDensity.current
    val heroPx = with(density) { HERO_HEIGHT.toPx() }

    // snapshotFlow, not a read in composition: the offset changes every frame of a scroll, and
    // this needs to reach the backdrop's draw lambdas without recomposing the header (and with it
    // the whole first row of the grid) on each one.
    LaunchedEffect(gridState, heroPx) {
        snapshotFlow {
            if (gridState.firstVisibleItemIndex == 0)
                (gridState.firstVisibleItemScrollOffset / heroPx).coerceIn(0f, 1f)
            else 1f
        }.collect { hero.scrolledFraction.floatValue = it }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(HERO_HEIGHT)
            .onGloballyPositioned {
                // Captured only at rest, so the sharp region blurs in place rather than also
                // retracting upward as the header scrolls away.
                if (gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0) {
                    hero.windowBottomPx.floatValue = it.boundsInRoot().bottom
                }
            }
    ) {
        // Sort — unfilled, so it sits on the artwork instead of punching a disc through it.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onSort)
                .graphicsLayer { alpha = 1f - hero.scrolledFraction.floatValue },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort, "Sort & filter",
                Modifier.size(20.dp), tint = ImmersiveStyle.scrimText()
            )
        }

        if (data.scanning) {
            Row(
                Modifier.align(Alignment.TopStart).padding(top = 12.dp),
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
                .padding(bottom = 10.dp)
                // The copy leaves faster than the window blurs — by the time the first row of
                // covers reaches this band, the title has already gone.
                .graphicsLayer {
                    alpha = 1f - (hero.scrolledFraction.floatValue * 1.6f).coerceIn(0f, 1f)
                }
        ) {
            heroData.eyebrow?.let {
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
                heroData.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveStyle.scrimText(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = heroData.onOpen
                )
            )

            // Where you are, under what you are in. Sits between the title and the progress bar
            // on purpose: it reads as a continuation of the title rather than as a second caption
            // competing with the eyebrow above it, and it is the line that changes as you listen.
            heroData.chapterLabel?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = ImmersiveStyle.scrimText(muted = true),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (heroData.progressFraction > 0f) {
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
                            .fillMaxWidth(heroData.progressFraction)
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
                        .clickable(onClick = heroData.onResume)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (heroData.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (heroData.isPlaying) "Playing"
                        else if (heroData.isLive) "Resume" else "Continue",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                heroData.remainingLabel?.let {
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

/** Fresh install / nothing ever played: no artwork to bring into focus, so a plain label. */
@Composable
private fun EmptyHeroHeader(data: HomeHeaderData, onSort: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Library",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = ImmersiveStyle.scrimText()
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
