package com.betteraudio.ui.immersive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.betteraudio.ui.components.ReflectedProgressiveBlurCover
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.player.containerReveal
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import java.io.File

/**
 * Immersive variant of the shared info page frame — full-bleed blurred cover backdrop, progressive
 * black scrim, accent-tinted scrim text. Drawn for both the Book Info page and the Series page; see
 * [com.betteraudio.ui.components.InfoPageScaffold] for what each caller supplies and why the two
 * pages share one frame.
 */
@Composable
internal fun ImmersiveInfoPageFrame(
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
    val onScrimMuted = ImmersiveStyle.scrimText(muted = true)

    val context = LocalContext.current
    val coverModel = remember(data.coverPath, data.coverCacheKey) {
        data.coverPath?.let { path ->
            ImageRequest.Builder(context)
                .data(File(path))
                .apply { data.coverCacheKey?.let { memoryCacheKey(it) } }
                .build()
        }
    }

    // The whole page unfolds out of the tapped card: `containerReveal` clips it to a window that
    // starts as exactly that card's cover and grows to full-bleed, so the black backdrop, the
    // blurred cover and the info panel all expand out of the image instead of appearing behind it.
    Box(
        Modifier
            .fillMaxSize()
            .containerReveal(coverSource, openProgress, sourceRadius = coverSourceRadius)
    ) {
        // Faded child, not a background on the clipped root: an alpha on the root would take the
        // morphing cover with it, and the cover has to stay fully visible for the whole transition.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ((openProgress.value - 0.05f) / 0.45f).coerceIn(0f, 1f) }
                .background(Color.Black)
        )
        Box(Modifier.fillMaxSize().clipToBounds()) {
            ReflectedProgressiveBlurCover(
                coverPath = data.coverPath,
                bakedPath = data.bakedCoverPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .morphFrom(
                        coverSource, openProgress,
                        anchorTopLeft = true, byWidth = true, fadeIn = true,
                        sourceRadius = coverSourceRadius, destRadius = 0.dp
                    )
            )
        }
        // Sharp copy of the cover travelling from the card's exact position/size, dissolving once
        // the blurred backdrop above has taken over.
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .morphFrom(
                    coverSource, openProgress,
                    anchorTopLeft = true, byWidth = true,
                    sourceRadius = coverSourceRadius, destRadius = 0.dp
                )
                .graphicsLayer {
                    val p = openProgress.value
                    alpha = 1f - ((p - 0.55f) / 0.35f).coerceIn(0f, 1f)
                }
        )
        // Ramped in rather than drawn at full strength from the first frame: at progress 0 the
        // reveal window sits exactly over the grid card, and this gradient is a full-SCREEN one —
        // a card low on the page would fall in its ~0.86-alpha band and the cover would visibly
        // darken the instant it was tapped, breaking the "same image, still there" handoff.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = ((openProgress.value - 0.15f) / 0.45f).coerceIn(0f, 1f)
            }.background(
                Brush.verticalGradient(
                    0f    to Color.Black.copy(alpha = 0.15f),
                    0.38f to Color.Black.copy(alpha = 0.04f),
                    0.54f to Color.Black.copy(alpha = 0.52f),
                    0.75f to Color.Black.copy(alpha = 0.86f),
                    1f    to Color.Black.copy(alpha = 0.97f)
                )
            )
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .then(contentModifier)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).expandReveal(openProgress),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", onClick = onBack)
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
                    ScrimButton(Icons.Default.MoreVert, "More") { showOverflow = true }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                        containerColor = ImmersiveStyle.menuColor()
                    ) {
                        overflowItems { showOverflow = false }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

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

            belowPanel()
            Spacer(Modifier.height(10.dp))
        }
    }
}
