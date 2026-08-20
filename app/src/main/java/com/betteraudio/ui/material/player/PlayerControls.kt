package com.betteraudio.ui.material.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.R
import com.betteraudio.ui.components.ScrimButton
import com.betteraudio.ui.components.ScrimPill
import com.betteraudio.ui.player.ElementMotion
import com.betteraudio.ui.player.elementMotion
import com.betteraudio.ui.player.expandReveal
import com.betteraudio.ui.theme.Pill
import java.util.concurrent.TimeUnit
import com.betteraudio.ui.haptics.*

/**
 * Leaf composables/formatters shared by the Material You player's portrait and landscape bodies —
 * moved out of `PlayerScreen.kt` so neither body has to duplicate them. `internal`: consumed only
 * from within `ui.material.player`.
 */

/** Slim whole-book progress, tappable to reveal a full book scrubber. Styled for the dark scrim.
 *  [readOnly] (used for the locked player) disables the tap-to-expand/drag entirely and hides the
 *  expand chevron, leaving a purely passive progress display. */
@Composable
internal fun CompactBookProgress(
    positionMs: Long,
    totalMs: Long,
    accent: Color,
    muted: Color,
    trackColor: Color,
    readOnly: Boolean = false,
    onSeek: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val frac = if (totalMs > 0) (positionMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .let { if (readOnly) it else it.clip(Pill).clickable { expanded = !expanded } }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Book", style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.weight(1f).height(4.dp).clip(Pill),
                color = accent,
                trackColor = trackColor
            )
            Spacer(Modifier.width(10.dp))
            Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = muted)
            if (!readOnly) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse" else "Expand book progress",
                    Modifier.size(18.dp),
                    tint = muted
                )
            }
        }
        if (!readOnly && expanded) {
            var dragFrac by remember { mutableStateOf<Float?>(null) }
            val displayFrac = dragFrac ?: frac
            HapticSlider(
                value = displayFrac,
                onValueChange = { dragFrac = it },
                onValueChangeFinished = { dragFrac?.let { onSeek((it * totalMs).toLong()) }; dragFrac = null },
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = trackColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * A horizontal seek slider with elapsed on the left and remaining on the right — the same
 * drag-then-seek-on-release contract [VerticalSeekTrack] uses, and the same one the portrait
 * player's inline chapter/book sliders implement: while dragging, the thumb follows the finger but
 * playback keeps running from the original spot, and the seek (plus its jump-history record) fires
 * once, on release. A tap counts as a gesture too, so a tap-seek's scrub origin is never dropped.
 *
 * Serves both modes: pass the chapter's duration for chapter seek, or the book's total for
 * whole-book seek. The caller keeps doing the ms math; this only speaks in fractions.
 */
@Composable
internal fun HorizontalSeekBar(
    /** Live position as 0..1 of [durationMs]. Ignored while the user is dragging. */
    fraction: Float,
    durationMs: Long,
    /** Fired once when a scrub gesture begins — the caller stashes the pre-scrub position. */
    onScrubStart: () -> Unit,
    /** The released fraction; the caller turns it into an absolute ms target. */
    onSeekFraction: (Float) -> Unit,
    colors: SliderColors,
    labelColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val displayFrac = dragFrac ?: fraction.coerceIn(0f, 1f)
    val displayMs = (displayFrac * durationMs).toLong()
    Column(modifier) {
        HapticSlider(
            value = displayFrac,
            onValueChange = { f ->
                if (dragFrac == null) onScrubStart()
                dragFrac = f
            },
            onValueChangeFinished = {
                dragFrac?.let { onSeekFraction(it) }
                dragFrac = null
            },
            enabled = enabled,
            colors = colors,
            modifier = Modifier.fillMaxWidth()
        )
        TimeRow(
            formatDuration(displayMs),
            "-${formatDuration((durationMs - displayMs).coerceAtLeast(0L))}",
            labelColor
        )
    }
}

@Composable
internal fun TimeRow(left: String, right: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = MaterialTheme.typography.labelMedium, color = color)
        Text(right, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** A circular skip control that shows the configured seconds in its centre. Long-press to change
 *  the skip amount. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SkipButton(
    seconds: Int,
    forward: Boolean,
    tint: Color,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit
) {
    Box(
        Modifier.size(56.dp).clip(Pill)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Replay,
            if (forward) "Skip forward $seconds seconds" else "Skip back $seconds seconds",
            Modifier.size(38.dp).graphicsLayer { if (forward) scaleX = -1f },
            tint = tint
        )
        // The Replay glyph's open loop sits slightly low-left of the box centre, so the centred
        // number reads as off. Nudge it into the loop's optical centre (the icon is mirrored for
        // the forward button, but the text is a separate child so it isn't flipped).
        Text(
            "$seconds",
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.offset(x = 0.5.dp, y = 1.5.dp)
        )
    }
}

/** Stepper dialog to change a skip interval (opened by long-pressing a skip button). */
@Composable
internal fun SkipValueDialog(
    forward: Boolean,
    currentSeconds: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var secs by remember { mutableStateOf(currentSeconds.coerceIn(5, 300)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forward) "Skip forward" else "Skip back") },
        text = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(onClick = { secs = (secs - 5).coerceAtLeast(5) }) {
                    Icon(Icons.Default.Remove, "Less")
                }
                Text("$secs s", style = MaterialTheme.typography.headlineSmall)
                FilledTonalIconButton(onClick = { secs = (secs + 5).coerceAtMost(300) }) {
                    Icon(Icons.Default.Add, "More")
                }
            }
        },
        confirmButton = { HapticTextButton(onClick = { onConfirm(secs) }) { Text("Save") } },
        dismissButton = { HapticTextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun SecondaryIcon(icon: ImageVector, cd: String, tint: Color, onClick: () -> Unit) {
    HapticIconButton(onClick = onClick) { Icon(icon, cd, Modifier.size(22.dp), tint = tint) }
}

internal fun formatDurationHuman(ms: Long): String {
    val hours   = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return when {
        hours > 0   -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else        -> "<1m"
    }
}

internal fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * The player's overflow (⋮) button and its [DropdownMenu] — the single definition of those ~15
 * items, so portrait's [PlayerTopBar] and the landscape body's vertical left rail share one menu
 * instead of each carrying a copy. `showOverflow` is owned locally: only one call site is ever
 * composed at a time, so there is no state to hoist across them.
 */
@Composable
internal fun PlayerOverflowMenu(
    inSeries: Boolean,
    showSeriesCover: Boolean,
    hasEbook: Boolean,
    onBookOptions: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleSeriesCover: () -> Unit,
    onHistory: () -> Unit,
    onReadFromHere: () -> Unit,
    onRefreshCoverEffect: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOverflow by remember { mutableStateOf(false) }
    Box(modifier) {
        ScrimButton(Icons.Default.MoreVert, "More", tonal = true) { showOverflow = true }
        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
            HapticDropdownMenuItem(
                text = { Text("Book options") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { showOverflow = false; onBookOptions() }
            )
            HapticDropdownMenuItem(
                text = { Text("Add bookmark") },
                leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                onClick = { showOverflow = false; onAddBookmark() }
            )
            if (inSeries) {
                HapticDropdownMenuItem(
                    text = { Text(if (showSeriesCover) "Show book cover" else "Show series cover") },
                    leadingIcon = { Icon(Icons.Default.Image, null) },
                    onClick = { showOverflow = false; onToggleSeriesCover() }
                )
            }
            HapticDropdownMenuItem(
                text = { Text("Listening history") },
                leadingIcon = { Icon(Icons.Default.History, null) },
                onClick = { showOverflow = false; onHistory() }
            )
            if (hasEbook && com.betteraudio.util.FeatureFlags.EBOOKS_UI) {
                HapticDropdownMenuItem(
                    text = { Text("Read from here") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                    onClick = { showOverflow = false; onReadFromHere() }
                )
            }
            HapticDropdownMenuItem(
                text = { Text("Refresh cover effect") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = { showOverflow = false; onRefreshCoverEffect() }
            )
            HapticDropdownMenuItem(
                text = { Text("Lock screen") },
                leadingIcon = { Icon(Icons.Default.Lock, null) },
                onClick = { showOverflow = false; onLock() }
            )
        }
    }
}

/**
 * The portrait player's top bar (back | series label | overflow). Landscape does NOT use this — it
 * stacks the same two buttons into a vertical left rail — but both share [PlayerOverflowMenu].
 */
@Composable
internal fun PlayerTopBar(
    seriesLabel: String?,
    inSeries: Boolean,
    showSeriesCover: Boolean,
    hasEbook: Boolean,
    onScrimMuted: Color,
    expandProgress: State<Float>,
    onBack: () -> Unit,
    onBookOptions: () -> Unit,
    onAddBookmark: () -> Unit,
    onToggleSeriesCover: () -> Unit,
    onHistory: () -> Unit,
    onReadFromHere: () -> Unit,
    onRefreshCoverEffect: () -> Unit,
    onLock: () -> Unit,
    modifier: Modifier = Modifier,
    /** Material You portrait passes its choreographed slice here (see PlayerChoreography);
     *  landscape leaves it null and keeps the original shared fade. */
    motion: ElementMotion? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp).then(
            if (motion != null) Modifier.elementMotion(motion, expandProgress)
            else Modifier.expandReveal(expandProgress)
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScrimButton(Icons.Default.KeyboardArrowDown, "Back", tonal = true, onClick = onBack)
        Spacer(Modifier.weight(1f))
        seriesLabel?.let {
            Text(
                it.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = onScrimMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        PlayerOverflowMenu(
            inSeries = inSeries,
            showSeriesCover = showSeriesCover,
            hasEbook = hasEbook,
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

/** Return/confirm jump-history pills — [FlowRow] (not [Row]) so they wrap instead of clipping in
 *  the landscape player's narrower centre column; behaves exactly like a [Row] whenever both pills
 *  fit on one line, which is every portrait case. */
@Composable
internal fun ReturnConfirmPills(
    positionStack: List<Long>,
    expandProgress: State<Float>,
    onReturn: () -> Unit,
    onReturnToIndex: (Int) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (positionStack.isEmpty()) return
    var showReturnMenu by remember { mutableStateOf(false) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.expandReveal(expandProgress)
    ) {
        Box {
            ScrimPill(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = "Return ${formatDuration(positionStack.last())}",
                trailing = if (positionStack.size > 1) Icons.Default.ArrowDropDown else null,
                onClick = {
                    if (positionStack.size > 1) showReturnMenu = true
                    else onReturn()
                }
            )
            DropdownMenu(expanded = showReturnMenu, onDismissRequest = { showReturnMenu = false }) {
                positionStack.reversed().forEachIndexed { displayIdx, posMs ->
                    val stackIdx = positionStack.size - 1 - displayIdx
                    HapticDropdownMenuItem(
                        text = { Text(formatDuration(posMs)) },
                        onClick = { showReturnMenu = false; onReturnToIndex(stackIdx) }
                    )
                }
            }
        }
        ScrimPill(icon = Icons.Default.Check, label = "Confirm", filled = true, onClick = onConfirm)
    }
}

/** Unexpected-jump restore pill (non-destructive; never auto-seeks) — [FlowRow] for the same
 *  wrap-not-clip reason as [ReturnConfirmPills]. */
@Composable
internal fun JumpRestorePill(
    visible: Boolean,
    expandProgress: State<Float>,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.expandReveal(expandProgress)
    ) {
        ScrimPill(icon = Icons.AutoMirrored.Filled.Undo, label = "Playback jumped — tap to go back", onClick = onRestore)
        ScrimPill(icon = Icons.Default.Close, label = "Dismiss", onClick = onDismiss)
    }
}

/** Skip-silence pill, audio-settings/bookmarks icons, and the sleep-timer pill (with countdown) —
 *  the portrait player's secondary action row. Landscape stacks the same four controls with
 *  [PlayerSecondaryActionsColumn]; the individual leaves below are shared by both. */
@Composable
internal fun PlayerSecondaryActionsRow(
    skipSilenceOn: Boolean,
    accent: Color,
    onScrim: Color,
    onScrimMuted: Color,
    sleepRemainingMs: Long,
    expandProgress: State<Float>,
    onToggleSkipSilence: () -> Unit,
    onSkipSilenceLongPress: () -> Unit,
    onAudioSettings: () -> Unit,
    onBookmarks: () -> Unit,
    onSleepTap: () -> Unit,
    onSleepLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** Material You portrait passes its choreographed slice here (see PlayerChoreography);
     *  landscape leaves it null and keeps the original shared fade. */
    motion: ElementMotion? = null,
) {
    Row(
        modifier.fillMaxWidth().then(
            if (motion != null) Modifier.elementMotion(motion, expandProgress)
            else Modifier.expandReveal(expandProgress)
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        SkipSilencePill(
            on = skipSilenceOn, accent = accent, muted = onScrimMuted,
            onClick = onToggleSkipSilence, onLongPress = onSkipSilenceLongPress
        )
        SecondaryIcon(Icons.Default.Tune, "Audio settings", accent, onAudioSettings)
        SecondaryIcon(Icons.Default.Bookmark, "Bookmarks", onScrim, onBookmarks)
        SleepTimerButton(sleepRemainingMs, accent, onScrim, onSleepTap, onSleepLongPress)
    }
}

/** The landscape player's vertical rail of the same four secondary controls
 *  [PlayerSecondaryActionsRow] lays out horizontally in portrait. Fills the height it is given and
 *  spreads the four evenly across it, so it reads as a rail matching the transport column beside
 *  it rather than a short cluster floating against a tall one. */
@Composable
internal fun PlayerSecondaryActionsColumn(
    skipSilenceOn: Boolean,
    accent: Color,
    onScrim: Color,
    onScrimMuted: Color,
    sleepRemainingMs: Long,
    expandProgress: State<Float>,
    onToggleSkipSilence: () -> Unit,
    onSkipSilenceLongPress: () -> Unit,
    onAudioSettings: () -> Unit,
    onBookmarks: () -> Unit,
    onSleepTap: () -> Unit,
    onSleepLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxHeight().expandReveal(expandProgress),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        SkipSilencePill(
            on = skipSilenceOn, accent = accent, muted = onScrimMuted,
            onClick = onToggleSkipSilence, onLongPress = onSkipSilenceLongPress
        )
        SecondaryIcon(Icons.Default.Tune, "Audio settings", accent, onAudioSettings)
        SecondaryIcon(Icons.Default.Bookmark, "Bookmarks", onScrim, onBookmarks)
        SleepTimerButton(sleepRemainingMs, accent, onScrim, onSleepTap, onSleepLongPress)
    }
}

/**
 * Skip-silence toggle — the [R.drawable.ic_skip_silence] waveform-with-the-gap-closed glyph, with
 * no caption in either orientation: the drawing carries the meaning, so portrait no longer spends
 * a text label's worth of row on it and landscape no longer needs a squeezed "Silence" variant.
 * On state is the accent tint plus the filled pill behind it, same as every other toggle here.
 */
@Composable
private fun SkipSilencePill(
    on: Boolean,
    accent: Color,
    muted: Color,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .clip(Pill)
            .background(if (on) accent.copy(alpha = 0.22f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_skip_silence),
            if (on) "Skip silence, on" else "Skip silence, off",
            Modifier.size(22.dp),
            tint = if (on) accent else muted
        )
    }
}

/** Sleep-timer button; shows the remaining countdown under the glyph once a timer is running. */
@Composable
private fun SleepTimerButton(
    remainingMs: Long,
    accent: Color,
    onScrim: Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .clip(Pill)
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        if (remainingMs > 0L) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(22.dp), tint = accent)
                Text(formatDuration(remainingMs), style = MaterialTheme.typography.labelSmall, color = accent)
            }
        } else {
            Icon(Icons.Default.Bedtime, "Sleep timer", Modifier.size(22.dp), tint = onScrim)
        }
    }
}
