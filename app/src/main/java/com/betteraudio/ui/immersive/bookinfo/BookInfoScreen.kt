package com.betteraudio.ui.immersive.bookinfo

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.betteraudio.ui.bookinfo.BookInfoViewModel
import com.betteraudio.ui.components.BookInfoPanel
import com.betteraudio.ui.components.ReflectedProgressiveBlurCover
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.immersive.ImmersiveStyle
import com.betteraudio.ui.player.LocalCoverBoundsRegistry
import com.betteraudio.ui.player.containerReveal
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Book Info page — Immersive variant. Same structure as the Material You version (see its doc),
 * styled with [ImmersiveStyle]'s scrim text tones instead of opaque tonal M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInfoScreen(
    onBack: () -> Unit,
    onResume: (bookId: Long) -> Unit,
    viewModel: BookInfoViewModel
) {
    val bwp by viewModel.bookWithProgress.collectAsStateWithLifecycle()
    val synopsisGenerating by viewModel.synopsisGenerating.collectAsStateWithLifecycle()
    val book = bwp?.book

    var showOverflow by remember { mutableStateOf(false) }
    var showBookOptions by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val coverBoundsRegistry = LocalCoverBoundsRegistry.current
    val coverOpenAnim = remember { Animatable(0f) }
    val coverOpenProgress = remember { derivedStateOf { coverOpenAnim.value } }
    LaunchedEffect(Unit) { coverOpenAnim.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 380f)) }
    LaunchedEffect(viewModel.bookId) {
        coverBoundsRegistry.setActiveMorph(viewModel.bookId, coverOpenProgress)
    }
    DisposableEffect(Unit) { onDispose { coverBoundsRegistry.setActiveMorph(-1L, null) } }
    val coverSource = remember(viewModel.bookId) { coverBoundsRegistry.boundsState(viewModel.bookId) }
    val coverSourceRadius = coverBoundsRegistry.radiusFor(viewModel.bookId)

    // One coroutine owns the whole back gesture — see the Material You variant for why splitting
    // the collect and the commit across two writers of `coverOpenAnim` stopped back from closing
    // the page at all.
    PredictiveBackHandler(enabled = true) { events ->
        var committed = false
        try {
            events.collect { event -> coverOpenAnim.snapTo(1f - event.progress) }
            committed = true
        } catch (_: CancellationException) {
        }
        if (committed) {
            scope.launch {
                try {
                    coverOpenAnim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 400f))
                } finally {
                    onBack()
                }
            }
        } else {
            scope.launch { coverOpenAnim.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 400f)) }
        }
    }
    fun closeWithMorph() = scope.launch {
        coverOpenAnim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 400f))
        onBack()
    }
    fun resumeWithMorph() = scope.launch {
        coverOpenAnim.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 400f))
        onResume(viewModel.bookId)
    }

    val onScrimMuted = ImmersiveStyle.scrimText(muted = true)

    // Same unfold-out-of-the-card treatment as the Material You variant (see its comment): the
    // whole page is clipped to a window that starts as exactly the tapped card's cover and grows
    // to full-bleed, so the black backdrop, the blurred cover and the info panel all expand out of
    // the image instead of appearing behind it.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .containerReveal(coverSource, coverOpenProgress, sourceRadius = coverSourceRadius)
    ) {
        // Faded child, not a background on the clipped root — see the Material You variant: an
        // alpha on the root would take the morphing cover with it, and the cover has to stay
        // fully visible for the whole transition.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ((coverOpenProgress.value - 0.05f) / 0.45f).coerceIn(0f, 1f) }
                .background(Color.Black)
        )
        val coverPath = book?.coverArtPath
        val context = LocalContext.current
        val coverModel = remember(coverPath, book?.id) {
            coverPath?.let {
                coil3.request.ImageRequest.Builder(context)
                    .data(File(it))
                    .memoryCacheKey(book?.id?.let { id -> "cover-$id" })
                    .build()
            }
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            ReflectedProgressiveBlurCover(
                coverPath = coverPath,
                bakedPath = book?.coverFxPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .morphFrom(
                        coverSource, coverOpenProgress,
                        anchorTopLeft = true, byWidth = true, fadeIn = true,
                        sourceRadius = coverSourceRadius, destRadius = 0.dp
                    )
            )
        }
        AsyncImage(
            model = coverModel,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .morphFrom(
                    coverSource, coverOpenProgress,
                    anchorTopLeft = true, byWidth = true,
                    sourceRadius = coverSourceRadius, destRadius = 0.dp
                )
                .graphicsLayer {
                    val p = coverOpenProgress.value
                    alpha = 1f - ((p - 0.55f) / 0.35f).coerceIn(0f, 1f)
                }
        )
        // Ramped in rather than drawn at full strength from the first frame: at progress 0 the
        // reveal window sits exactly over the grid card, and this gradient is a full-SCREEN one —
        // a card low on the page would fall in its ~0.86-alpha band and the cover would visibly
        // darken the instant it was tapped, breaking the "same image, still there" handoff.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = ((coverOpenProgress.value - 0.15f) / 0.45f).coerceIn(0f, 1f)
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
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).expandReveal(coverOpenProgress),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", onClick = { closeWithMorph() })
                Spacer(Modifier.weight(1f))
                Text(
                    "BOOK",
                    style = MaterialTheme.typography.labelSmall,
                    color = onScrimMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Box {
                    ScrimButton(Icons.Default.MoreVert, "More") { showOverflow = true }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }, containerColor = ImmersiveStyle.menuColor()) {
                        DropdownMenuItem(
                            text = { Text("Book options") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showOverflow = false; showBookOptions = true }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            val seriesLabel = book?.seriesName?.takeIf { it.isNotBlank() }?.let { series ->
                if (book.seriesOrder != null) "$series · #${book.seriesOrder}" else series
            }
            BookInfoPanel(
                title            = book?.displayTitle ?: "",
                author           = book?.displayAuthor,
                narrator         = book?.narrator,
                seriesLabel      = seriesLabel,
                status           = book?.status,
                progressFraction = bwp?.progressFraction ?: 0f,
                totalMs          = book?.totalDurationMs ?: 0L,
                synopsis         = book?.synopsis?.takeIf { it.isNotBlank() }
                                   ?: book?.description?.takeIf { it.isNotBlank() }
                                   ?: if (synopsisGenerating) "Generating synopsis…" else null,
                onResume         = { resumeWithMorph() },
                modifier         = Modifier.expandReveal(coverOpenProgress)
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    if (showBookOptions && bwp != null) {
        BookOptionsSheet(
            bwp = bwp,
            onDismiss = { showBookOptions = false },
            onUpdateMetadata = { title, author -> viewModel.updateMetadata(title, author) },
            onUpdateSeries = { name, order -> viewModel.updateSeriesInfo(name, order) },
            onUpdateStatus = { viewModel.updateStatus(it) }
        )
    }
}
