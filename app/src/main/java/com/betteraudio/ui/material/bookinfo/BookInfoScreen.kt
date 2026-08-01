package com.betteraudio.ui.material.bookinfo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.betteraudio.ui.bookinfo.BookInfoViewModel
import com.betteraudio.ui.components.BookInfoPanel
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.home.BookOptionsSheet
import com.betteraudio.ui.material.motion.LocalVoyageMotion
import com.betteraudio.ui.player.LocalCoverBoundsRegistry
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.theme.rememberPredictiveBackProgress
import java.io.File
import kotlinx.coroutines.launch

/**
 * Book Info page — opaque Material You background with a rounded cover card (same tonal
 * language as the player), and the info block with AI synopsis and a resume button. Its own
 * overlay + cover morph (see [com.betteraudio.ui.bookinfo.BookInfoOverlay]), structurally
 * identical to the Series page's info half, just without the books panel.
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
    val motion = LocalVoyageMotion.current

    // Cover morph from/to the tapped grid card — same treatment as the Series page's grid → info
    // morph, using the book (not series) maps of CoverBoundsRegistry.
    val coverBoundsRegistry = LocalCoverBoundsRegistry.current
    val coverOpenAnim = remember { Animatable(0f) }
    val coverOpenProgress = remember { derivedStateOf { coverOpenAnim.value } }
    LaunchedEffect(Unit) { coverOpenAnim.animateTo(1f, motion.spatialDefault) }
    LaunchedEffect(viewModel.bookId) {
        coverBoundsRegistry.setActiveMorph(viewModel.bookId, coverOpenProgress)
    }
    DisposableEffect(Unit) { onDispose { coverBoundsRegistry.setActiveMorph(-1L, null) } }
    val coverSource = remember(viewModel.bookId) { coverBoundsRegistry.boundsState(viewModel.bookId) }
    val coverSourceRadius = coverBoundsRegistry.radiusFor(viewModel.bookId)

    val closeBackProgress = rememberPredictiveBackProgress(enabled = true) {
        scope.launch {
            coverOpenAnim.animateTo(0f, motion.spatialDefault)
            onBack()
        }
    }
    LaunchedEffect(closeBackProgress.value) {
        coverOpenAnim.snapTo(1f - closeBackProgress.value)
    }
    fun closeWithMorph() = scope.launch {
        coverOpenAnim.animateTo(0f, motion.spatialDefault)
        onBack()
    }
    fun resumeWithMorph() = scope.launch {
        coverOpenAnim.animateTo(0f, motion.spatialDefault)
        onResume(viewModel.bookId)
    }

    val onScrimMuted = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrimButton(Icons.Default.KeyboardArrowDown, "Back", tonal = true, onClick = { closeWithMorph() })
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
                    ScrimButton(Icons.Default.MoreVert, "More", tonal = true) { showOverflow = true }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        DropdownMenuItem(
                            text = { Text("Book options") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { showOverflow = false; showBookOptions = true }
                        )
                    }
                }
            }

            // Rounded cover card in the leftover space — same tonal treatment as the player's
            // own cover (see ui/material/player/PlayerScreen.kt), instead of the old full-bleed
            // blurred backdrop, which was expensive to recompose during the grid-card morph and
            // fought with it visually.
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
                            coverSource, coverOpenProgress,
                            anchorTopLeft = true, byWidth = true,
                            sourceRadius = coverSourceRadius, destRadius = 28.dp
                        )
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                )
            }

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
                onResume         = { resumeWithMorph() }
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
