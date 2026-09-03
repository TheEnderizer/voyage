package com.betteraudio.ui.material.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import com.betteraudio.playback.ChapterMark
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.material.coverCropMorph
import com.betteraudio.ui.player.PlayerExpandTransition
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.haptics.*

/**
 * The Material You player's landscape layout. Landscape is short and wide, so **nothing here owns a
 * full-width row** — every band of controls portrait stacks vertically is turned on its side and
 * given its own narrow column, leaving the height for the cover:
 *
 * ```
 *  ┌────┬──────────────────┬────────┬────────┬──────┬───────┐
 *  │ ↓  │                  │        │        │  ⏩  │  ⏭    │
 *  │ ⋮  │   [ cover ]      │Chapter │ Book   │  🎚  │  +30  │
 *  │    │   chapter        │ rail   │ rail   │  🔖  │  ▶    │
 *  │    │   title / author │        │        │  🌙  │  -30  │
 *  └────┴──────────────────┴────────┴────────┴──────┴───────┘
 *   rail        centre        seek rails      2nd    transport
 * ```
 *
 * Called from `PlayerScreen.kt`'s `PlayerContent`, inside the SAME `Box` the portrait Column
 * lives in — not a separate nav destination — so [LocalPlayerExpand] resolves to the instance
 * `PlayerSheet` already provides and the mini-bar → full-player morph keeps working unmodified.
 * `PlayerContent` still owns every piece of state here (all `remember`ed flags, the ViewModel,
 * the Scaffold, and every sheet/overlay); this function only lays out already-derived values and
 * forwards already-built callbacks, mirroring the plain parameter style `BookInfoPanel` already
 * uses elsewhere in this codebase rather than introducing a wrapper class for it.
 *
 * Three things keep the shared-element morph working with the cover moved out of a plain column
 * (see `ui/player/PlayerMorph.kt` and `ui/material/MaterialMotion.kt` for the mechanics this
 * relies on):
 * 1. The cover's parent Box is sized with `Modifier.size(coverSide)` — a concrete Dp derived from
 *    the window's own constraints — NEVER `weight()`. `coverCropMorph` requires a STABLE parent
 *    rect that doesn't change size as the cover itself animates; portrait gets that from
 *    `weight(1f).fillMaxWidth()`, landscape gets it from this concrete size instead.
 * 2. `contentAlignment = Alignment.TopStart` on that Box is mandatory, not cosmetic —
 *    `coverCropMorph` places the image with an offset computed from the Box's own top-left, so
 *    any implicit centering would double-apply.
 * 3. Bounds are still published via `onGloballyPositioned`, never `onPlaced` (see
 *    `PlayerMorph.kt`'s `morphFrom` doc for why that swap was tried and reverted).
 */
@Composable
internal fun PlayerLandscapeBody(
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
    sleepTimerMinutes: Int,
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
    onOpenCompanion: () -> Unit,
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

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // The cover is a SQUARE sized from the window's own constraints, never from its own
        // content or a weight() — see this file's doc, point 1. The height budget subtracts the
        // identity block (chapter chip + title + author) that now sits UNDER the cover.
        val coverSide = minOf(maxHeight - 124.dp, maxWidth * 0.28f).coerceAtLeast(96.dp)

        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {

            // ── LEFT RAIL: collapse + overflow, stacked ────────────────────────────────
            // Portrait spends a whole row on these two; landscape can't spare the height, so
            // they go in a narrow column pinned to the top-left. Same composables, same menu.
            Column(
                Modifier.fillMaxHeight().padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.expandReveal(expandProgress)
                ) {
                    ScrimButton(Icons.Default.KeyboardArrowDown, "Back", tonal = true, onClick = onBack)
                    PlayerOverflowMenu(
                        inSeries = inSeries,
                        showSeriesCover = showSeriesCover,
                        hasEbook = book?.ebookPath != null,
                        onBookOptions = onBookOptions,
                        onAddBookmark = onAddBookmark,
                        onToggleSeriesCover = onToggleSeriesCover,
                        onHistory = onHistory,
                        onReadFromHere = onReadFromHere,
                        onRefreshCoverEffect = onRefreshCoverEffect,
                        onLock = onLock
                    )
                }
            }

            // ── CENTRE: cover with the identity block under it ─────────────────────────
            // weight(1f) centres the cover in whatever the rails leave over, which is what
            // "move the cover towards the centre" means once the rails are this narrow.
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp),
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
                    contentAlignment = Alignment.TopStart // mandatory — see file doc, point 2
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

                Spacer(Modifier.height(12.dp))

                // Identity block, directly under the cover and no wider than it plus a little
                // slack, so long titles wrap instead of crowding the rails either side.
                Column(
                    Modifier.widthIn(max = coverSide + 96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The series label portrait shows in its top bar — that bar is gone here, so
                    // it rides above the title instead of being dropped.
                    book?.seriesName?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = onScrimMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.expandReveal(expandProgress)
                        )
                        Spacer(Modifier.height(2.dp))
                    }

                    if (!isLocked && hasMultipleChapters && cur != null) {
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
                        Spacer(Modifier.height(4.dp))
                    }

                    Text(
                        book?.displayTitle ?: "",
                        style = MaterialTheme.typography.titleLarge,
                        color = onScrim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
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
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().expandReveal(expandProgress)
                        )
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
                }
            }

            // ── RIGHT: two vertical seek rails, then the secondary + transport columns ──
            Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {

                var scrubStart by remember { mutableStateOf(-1L) }

                // CHAPTER rail — only when there actually are chapters, and hidden while locked
                // (portrait drops its chapter slider on lock for the same reason).
                AnimatedVisibility(
                    visible = !isLocked && hasMultipleChapters && cur != null,
                    enter = fadeIn() + expandHorizontally(expandFrom = Alignment.End),
                    exit = fadeOut() + shrinkHorizontally(shrinkTowards = Alignment.End)
                ) {
                    // Lock is what normally drives this in/out, and `cur` stays non-null across a
                    // lock toggle, so the exit animation has a chapter to draw with.
                    cur?.let { ch ->
                        val chDur = (ch.endMs - ch.startMs).coerceAtLeast(1L)
                        VerticalSeekTrack(
                            fraction = ((bookPos - ch.startMs).toFloat() / chDur).coerceIn(0f, 1f),
                            durationMs = chDur,
                            baseMs = ch.startMs,
                            label = "Chapter",
                            onScrubStart = { scrubStart = bookPos },
                            onSeekFraction = { f ->
                                val target = ch.startMs + (f * chDur).toLong()
                                onSeekBook(target)
                                if (scrubStart >= 0L) onScrubSeek(scrubStart, target)
                                scrubStart = -1L
                            },
                            colors = sliderColors,
                            labelColor = onScrimMuted,
                            modifier = Modifier.width(56.dp).fillMaxHeight()
                                .padding(vertical = 4.dp).expandReveal(expandProgress)
                        )
                    }
                }

                // BOOK rail — always present, and the ONLY rail for a single-chapter book. Goes
                // read-only rather than disappearing when locked, matching portrait's locked
                // book progress.
                VerticalSeekTrack(
                    fraction = if (bookTotal > 0) (bookPos.toFloat() / bookTotal).coerceIn(0f, 1f) else 0f,
                    durationMs = bookTotal,
                    baseMs = 0L,
                    label = "Book",
                    enabled = !isLocked,
                    onScrubStart = { scrubStart = bookPos },
                    onSeekFraction = { f ->
                        val target = (f * bookTotal).toLong()
                        onSeekBook(target)
                        if (scrubStart >= 0L) onScrubSeek(scrubStart, target)
                        scrubStart = -1L
                    },
                    colors = sliderColors,
                    labelColor = onScrimMuted,
                    modifier = Modifier.width(56.dp).fillMaxHeight()
                        .padding(vertical = 4.dp).expandReveal(expandProgress)
                )

                // Secondary actions + transport slide out to the RIGHT on lock — the landscape
                // analogue of portrait's slide-down (see PlayerScreen.kt's AnimatedVisibility
                // around the transport group).
                AnimatedVisibility(
                    visible = !isLocked,
                    enter = fadeIn() + slideInHorizontally { it },
                    exit = fadeOut() + slideOutHorizontally { it }
                ) {
                    Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {

                        PlayerSecondaryActionsColumn(
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
                            onOpenCompanion = onOpenCompanion,
                            onSleepTap = onSleepTap,
                            onSleepLongPress = onSleepLongPress,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        Spacer(Modifier.width(6.dp))

                        // Transport, ordered to AGREE with the rails' direction: up = forward.
                        // See VerticalSeekTrack's doc for the rotation-matrix derivation.
                        Column(
                            Modifier.fillMaxHeight().width(72.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
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
                            Box(Modifier.expandReveal(expandProgress)) {
                                SkipButton(
                                    seconds = (skipForwardMs / 1000).toInt(), forward = true, tint = onScrim,
                                    onLongPress = { onEditSkip(true) }, onClick = onSkipForward
                                )
                            }
                            // THE morph anchor — same 72dp accent pill as portrait, so it grows
                            // out of the mini bar's 44dp play button identically.
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
                                    seconds = (skipBackMs / 1000).toInt(), forward = false, tint = onScrim,
                                    onLongPress = { onEditSkip(false) }, onClick = onSkipBack
                                )
                            }
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
                        }
                    }
                }
            }
        }
    }
}
