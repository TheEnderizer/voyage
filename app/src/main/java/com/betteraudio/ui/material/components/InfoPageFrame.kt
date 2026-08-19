package com.betteraudio.ui.material.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.betteraudio.ui.components.BookInfoPanel
import com.betteraudio.ui.components.InfoPageData
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.isLandscapeWindow
import com.betteraudio.ui.player.containerReveal
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import java.io.File

/**
 * Material You variant of the shared info page frame — opaque tonal background with a rounded cover
 * card (same tonal language as the player), plus the landscape split-pane layout. Drawn for both
 * the Book Info page and the Series page; see [com.betteraudio.ui.components.InfoPageScaffold] for
 * what each caller supplies and why the two pages share one frame.
 */
@Composable
internal fun MaterialInfoPageFrame(
    data: InfoPageData,
    openProgress: State<Float>,
    coverSource: State<Rect>,
    coverSourceRadius: Dp,
    onBack: () -> Unit,
    onResume: () -> Unit,
    contentModifier: Modifier,
    overflowItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    belowPanel: @Composable ColumnScope.() -> Unit,
) {
    var showOverflow by remember { mutableStateOf(false) }
    val onScrimMuted = MaterialTheme.colorScheme.onSurfaceVariant

    val context = LocalContext.current
    val coverModel = remember(data.coverPath, data.coverCacheKey) {
        data.coverPath?.let { path ->
            ImageRequest.Builder(context)
                .data(File(path))
                .apply { data.coverCacheKey?.let { memoryCacheKey(it) } }
                .build()
        }
    }

    // The page itself unfolds out of the tapped card: `containerReveal` clips the whole screen to
    // a window that starts as exactly that card's cover (same rect, same radius) and grows to
    // full-bleed, so the background and every element appear to expand from the image rather than
    // popping in behind a flying cover. The morphing cover below travels inside that window.
    Box(
        Modifier
            .fillMaxSize()
            .containerReveal(coverSource, openProgress, sourceRadius = coverSourceRadius)
    ) {
        // The page surface is a faded child rather than a background on the clipped root, so the
        // sheet materialises around the cover as the window grows (and dissolves again as it
        // shrinks back onto the card) instead of the cover riding on an already-solid panel.
        // Deliberately NOT an alpha on the root: that would fade the morphing cover too, and the
        // cover must stay fully visible end to end.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ((openProgress.value - 0.05f) / 0.45f).coerceIn(0f, 1f) }
                .background(MaterialTheme.colorScheme.background)
        )

        // Top bar — identical content in both layouts, just placed differently (self-contained:
        // its own Row provides the RowScope its Spacer(weight()) calls need), so it's a small local
        // closure rather than a second definition.
        val topBar: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).expandReveal(openProgress),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", tonal = true, onClick = onBack)
                Spacer(Modifier.weight(1f))
                Text(
                    data.kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = onScrimMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Box {
                    ScrimButton(Icons.Default.MoreVert, "More", tonal = true) { showOverflow = true }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        overflowItems { showOverflow = false }
                    }
                }
            }
        }

        val infoPanel: @Composable () -> Unit = {
            BookInfoPanel(
                title            = data.title,
                author           = data.author,
                narrator         = data.narrator,
                seriesLabel      = data.seriesLabel,
                status           = data.status,
                progressFraction = data.progressFraction,
                totalMs          = data.totalMs,
                synopsis         = data.synopsis,
                onResume         = onResume,
                modifier         = Modifier.expandReveal(openProgress)
            )
        }

        if (isLandscapeWindow()) {
            Row(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .then(contentModifier)
            ) {
                // LEFT: cover. Uses morphFrom (not coverCropMorph), so — unlike the landscape
                // player's cover — there's no stable-parent-rect requirement and no mandatory
                // TopStart; this box can size itself from height the ordinary way.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .padding(vertical = 12.dp)
                        .aspectRatio(0.72f, matchHeightConstraintsFirst = true),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .morphFrom(
                                coverSource, openProgress,
                                anchorTopLeft = true, byWidth = true,
                                sourceRadius = coverSourceRadius, destRadius = 28.dp
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                }

                Spacer(Modifier.width(20.dp))

                // RIGHT: top bar + info panel. Scrollable as a whole — the panel's synopsis has
                // its own internal scroll box for a long synopsis, but the surrounding chrome
                // (title, buttons, progress) is fixed-height content that can still exceed a
                // short landscape window on its own. Because this whole pane scrolls, a swipe-up
                // gesture starting over it is captured by the scroll rather than by anything the
                // caller hung on `contentModifier` (the Series page's panel-reveal drag) — its
                // "Swipe up for books" pill is the gesture-independent way in.
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                ) {
                    topBar()
                    infoPanel()
                    belowPanel()
                    Spacer(Modifier.height(10.dp))
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .then(contentModifier)
            ) {
                topBar()

                // Rounded cover card in the leftover space — same tonal treatment as the
                // player's own cover (see ui/material/player/PlayerScreen.kt), instead of the
                // old full-bleed blurred backdrop, which was expensive to recompose during the
                // grid-card morph and fought with it visually.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(0.72f)
                            .morphFrom(
                                coverSource, openProgress,
                                anchorTopLeft = true, byWidth = true,
                                sourceRadius = coverSourceRadius, destRadius = 28.dp
                            )
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                }

                infoPanel()
                belowPanel()
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
