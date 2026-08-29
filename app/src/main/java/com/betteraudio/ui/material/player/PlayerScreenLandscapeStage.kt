package com.betteraudio.ui.material.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.playback.ChapterMark
import com.betteraudio.ui.material.coverCropMorph
import com.betteraudio.ui.player.PlayerExpandTransition
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.haptics.*

/**
 * [LandscapePlayerStyle.STAGE] — the Material You player's two-pane landscape layout, and the
 * alternative to [PlayerLandscapeBody]'s rails.
 *
 * ```
 *  ┌──────────────────────────────────────────────────────────┐
 *  │  ↓                    SERIES NAME                     ⋮  │
 *  ├──────────────┬───────────────────────────────────────────┤
 *  │              │  Book Title                               │
 *  │              │  Author                                   │
 *  │   [ cover ]  │  ≡ Chapter 3 · The Crossing               │
 *  │              │  ──────●─────────────────────             │
 *  │              │  4:12                            -18:03   │
 *  │  ⏩̶ 🎚 🔖 🌙   │  ⏮    ⟲30    ( ▶ )    ⟳30    ⏭            │
 *  │              │  Book ▓▓▓▓░░░░░░  42%                     │
 *  └──────────────┴───────────────────────────────────────────┘
 * ```
 *
 * Where the rails layout turns every band on its side to buy cover height, this one keeps the
 * portrait player's own vocabulary — a left-to-right scrubber, a horizontal transport row with
 * full-size targets — and simply splits it into two panes so the width does the work. Nothing is
 * rotated and nothing is icon-only-by-necessity, which makes it the easier of the two to hit
 * without looking, at the cost of a smaller cover.
 *
 * The same three morph rules [PlayerLandscapeBody] documents apply verbatim, for the same reasons:
 * the cover's parent Box is a concrete `size(coverSide)` (never `weight()`) so `coverCropMorph`
 * sees a stable parent rect, `contentAlignment = TopStart` is mandatory rather than cosmetic, and
 * bounds are published via `onGloballyPositioned` and not `onPlaced`.
 *
 * Like its sibling this is called from `PlayerScreen.kt`'s `PlayerContent`, inside the SAME `Box`
 * the portrait Column lives in, and owns no state of its own beyond the local scrub bookkeeping.
 */
@Composable
internal fun PlayerLandscapeStageBody(
    padding: PaddingValues,
    book: Book?,
    author: String?,
    inSeries: Boolean,
    showSeriesCover: Boolean,
    isPlaying: Boolean,
    serviceHasBook: Boolean,
    bookPos: Long,
    bookTotal: Long,
    cur: ChapterMark?,
    hasMultipleChapters: Boolean,
    chapterNavCount: Int,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    positionStack: List<Long>,
    hasJumpRestore: Boolean,
    skipForwardMs: Long,
    skipBackMs: Long,
    sleepTimerRemainingMs: Long,
    isLocked: Boolean,
    coverModel: Any?,
    expand: PlayerExpandTransition,
    coverParentBounds: MutableState<Rect>,
    lockAnim: State<Float>,
    sliderColors: SliderColors,
    accent: Color,
    onScrim: Color,
    onScrimMuted: Color,
    trackColor: Color,
    onBack: () -> Unit,
    onBookOptions: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleSeriesCover: () -> Unit,
    onHistory: () -> Unit,
    onReadFromHere: () -> Unit,
    onRefreshCoverEffect: () -> Unit,
    onLock: () -> Unit,
    onOpenChapters: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onSkipBack: () -> Unit,
    onEditSkip: (forward: Boolean) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekBook: (Long) -> Unit,
    onScrubSeek: (from: Long, to: Long) -> Unit,
    onReturnJump: () -> Unit,
    onReturnToIndex: (Int) -> Unit,
    onConfirmPosition: () -> Unit,
    onRestoreJump: () -> Unit,
    onDismissJumpRestore: () -> Unit,
    onToggleSkipSilence: () -> Unit,
    onSkipSilenceLongPress: () -> Unit,
    onAudioSettings: () -> Unit,
    onBookmarksClick: () -> Unit,
    onSleepTap: () -> Unit,
    onSleepLongPress: () -> Unit,
) {
    val expandProgress = expand.progress

    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {

        // Slim full-width top bar — the SAME composable as portrait, so the overflow menu exists
        // exactly once across all three layouts.
        PlayerTopBar(
            seriesLabel = book?.seriesName?.takeIf { it.isNotBlank() },
            inSeries = inSeries,
            showSeriesCover = showSeriesCover,
            hasEbook = book?.ebookPath != null,
            onScrimMuted = onScrimMuted,
            expandProgress = expandProgress,
            onBack = onBack,
            onBookOptions = onBookOptions,
            onAddBookmark = onAddBookmark,
            onToggleSeriesCover = onToggleSeriesCover,
            onHistory = onHistory,
            onReadFromHere = onReadFromHere,
            onRefreshCoverEffect = onRefreshCoverEffect,
            onLock = onLock
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Concrete Dp from the window's own constraints, never a weight() — see the file doc.
            // The reserve leaves room for the secondary actions row tucked under the cover.
            val coverSide = minOf(maxHeight - 64.dp, maxWidth * 0.34f).coerceAtLeast(96.dp)

            Row(Modifier.fillMaxSize().padding(bottom = 8.dp)) {

                // ── LEFT PANE: cover, with the secondary actions tucked beneath it ─────
                Column(
                    Modifier.fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(coverSide)
                            .onGloballyPositioned { coverParentBounds.value = it.boundsInRoot() }
                            .graphicsLayer {
                                val s = 1f + 0.05f * lockAnim.value
                                scaleX = s
                                scaleY = s
                            },
                        contentAlignment = Alignment.TopStart // mandatory — see file doc
                    ) {
                        if (expand.sourceIsGridCard) {
                            AsyncImage(
                                model = coverModel,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .coverCropMorph(
                                        parentBounds = coverParentBounds,
                                        source = expand.miniCover,
                                        progress = expandProgress,
                                        sourceRadius = expand.coverSourceRadius,
                                        destRadius = 28.dp
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        } else {
                            AsyncImage(
                                model = coverModel,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize() // the Box is already square
                                    .morphFrom(
                                        expand.miniCover, expandProgress,
                                        anchorTopLeft = true, byWidth = true,
                                        sourceRadius = expand.coverSourceRadius, destRadius = 28.dp
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = !isLocked,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        // Constrained to the cover's own width so the four actions read as
                        // belonging to this pane rather than drifting under the right one.
                        PlayerSecondaryActionsRow(
                            skipSilenceOn = book?.skipSilenceEnabled == true,
                            accent = accent,
                            onScrim = onScrim,
                            onScrimMuted = onScrimMuted,
                            sleepRemainingMs = sleepTimerRemainingMs,
                            expandProgress = expandProgress,
                            onToggleSkipSilence = onToggleSkipSilence,
                            onSkipSilenceLongPress = onSkipSilenceLongPress,
                            onAudioSettings = onAudioSettings,
                            onBookmarks = onBookmarksClick,
                            onSleepTap = onSleepTap,
                            onSleepLongPress = onSleepLongPress,
                            modifier = Modifier.width(coverSide).padding(top = 10.dp)
                        )
                    }
                }

                Spacer(Modifier.width(24.dp))

                // ── RIGHT PANE: identity, scrubber, transport, book progress ───────────
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        book?.displayTitle ?: "",
                        style = MaterialTheme.typography.headlineSmall,
                        color = onScrim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .morphFrom(expand.miniTitle, expandProgress, anchorTopLeft = true)
                    )
                    if (!author.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            author,
                            style = MaterialTheme.typography.titleSmall,
                            color = onScrimMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().expandReveal(expandProgress)
                        )
                    }

                    if (!isLocked && hasMultipleChapters && cur != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .expandReveal(expandProgress)
                                .clip(Pill)
                                .clickable(onClick = onOpenChapters)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(15.dp), tint = onScrimMuted)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Chapter ${cur.index + 1} · ${cur.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = onScrimMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (!isLocked) {
                        ReturnConfirmPills(
                            positionStack = positionStack,
                            expandProgress = expandProgress,
                            onReturn = onReturnJump,
                            onReturnToIndex = onReturnToIndex,
                            onConfirm = onConfirmPosition,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        JumpRestorePill(
                            visible = hasJumpRestore,
                            expandProgress = expandProgress,
                            onRestore = onRestoreJump,
                            onDismiss = onDismissJumpRestore,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Scrubber: chapter when there are chapters, whole-book otherwise —
                    // the same two modes portrait covers. Locked shows a read-only readout.
                    if (isLocked) {
                        TimeRow(formatDuration(bookPos), formatDuration(bookTotal), onScrimMuted)
                        Spacer(Modifier.height(6.dp))
                    } else {
                        var scrubStart by remember { mutableStateOf(-1L) }
                        if (hasMultipleChapters && cur != null) {
                            val chDur = (cur.endMs - cur.startMs).coerceAtLeast(1L)
                            HorizontalSeekBar(
                                fraction = ((bookPos - cur.startMs).toFloat() / chDur).coerceIn(0f, 1f),
                                durationMs = chDur,
                                onScrubStart = { scrubStart = bookPos },
                                onSeekFraction = { f ->
                                    val target = cur.startMs + (f * chDur).toLong()
                                    onSeekBook(target)
                                    if (scrubStart >= 0L) onScrubSeek(scrubStart, target)
                                    scrubStart = -1L
                                },
                                colors = sliderColors,
                                labelColor = onScrimMuted,
                                modifier = Modifier.fillMaxWidth().expandReveal(expandProgress)
                            )
                        } else {
                            HorizontalSeekBar(
                                fraction = if (bookTotal > 0) (bookPos.toFloat() / bookTotal).coerceIn(0f, 1f) else 0f,
                                durationMs = bookTotal,
                                onScrubStart = { scrubStart = bookPos },
                                onSeekFraction = { f ->
                                    val target = (f * bookTotal).toLong()
                                    onSeekBook(target)
                                    if (scrubStart >= 0L) onScrubSeek(scrubStart, target)
                                    scrubStart = -1L
                                },
                                colors = sliderColors,
                                labelColor = onScrimMuted,
                                modifier = Modifier.fillMaxWidth().expandReveal(expandProgress)
                            )
                        }
                    }

                    // ── Transport — slides down and off as one unit on lock, exactly as the
                    // portrait control cluster does. ──
                    AnimatedVisibility(
                        visible = !isLocked,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = slideOutVertically(targetOffsetY = { it }) +
                            shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (chapterNavCount > 1) {
                                HapticIconButton(
                                    onClick = onPrevChapter,
                                    enabled = serviceHasBook && hasPrevChapter,
                                    modifier = Modifier.expandReveal(expandProgress)
                                ) {
                                    Icon(
                                        Icons.Default.SkipPrevious, "Previous chapter", Modifier.size(26.dp),
                                        tint = if (serviceHasBook && hasPrevChapter) onScrim else onScrimMuted.copy(alpha = 0.4f)
                                    )
                                }
                            }
                            Box(Modifier.expandReveal(expandProgress)) {
                                SkipButton(
                                    seconds = (skipBackMs / 1000).toInt(), forward = false, tint = onScrim,
                                    onLongPress = { onEditSkip(false) }, onClick = onSkipBack
                                )
                            }
                            // THE morph anchor — same 72dp accent pill as portrait and rails, so
                            // it grows out of the mini bar's play button identically.
                            Box(
                                Modifier
                                    .morphFrom(expand.miniControls, expandProgress)
                                    .size(72.dp).clip(Pill).background(accent)
                                    .clickable(onClick = onPlayPause),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    if (isPlaying) "Pause" else "Play",
                                    Modifier.size(40.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Box(Modifier.expandReveal(expandProgress)) {
                                SkipButton(
                                    seconds = (skipForwardMs / 1000).toInt(), forward = true, tint = onScrim,
                                    onLongPress = { onEditSkip(true) }, onClick = onSkipForward
                                )
                            }
                            if (chapterNavCount > 1) {
                                HapticIconButton(
                                    onClick = onNextChapter,
                                    enabled = serviceHasBook && hasNextChapter,
                                    modifier = Modifier.expandReveal(expandProgress)
                                ) {
                                    Icon(
                                        Icons.Default.SkipNext, "Next chapter", Modifier.size(26.dp),
                                        tint = if (serviceHasBook && hasNextChapter) onScrim else onScrimMuted.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }

                    // Whole-book progress last, under everything — the at-a-glance readout, with
                    // the scrubber above owning chapter seek. Read-only when locked.
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().expandReveal(expandProgress)) {
                        CompactBookProgress(
                            bookPos, bookTotal, accent, onScrimMuted, trackColor, readOnly = isLocked
                        ) { target -> onScrubSeek(bookPos, target); onSeekBook(target) }
                    }
                }
            }
        }
    }
}
