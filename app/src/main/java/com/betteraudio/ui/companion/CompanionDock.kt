package com.betteraudio.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.PressFeel
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import java.io.File

/**
 * Everything the deck's dock needs from playback, packaged so the deck itself never touches
 * `PlayerController`.
 *
 * The three read-outs are **lambdas, not values**, for the same reason `MiniPlayerBar`'s
 * `progress` is: the position state ticks twice a second, and a plain `Float`/`String` parameter
 * would recompose the entire deck — cast grid, character sheet and all — on every tick. Read
 * inside a leaf composable, each lambda invalidates only that leaf.
 *
 * [revealFraction] has no equivalent anywhere else in the app: it is how far the *companion* has
 * been revealed, as a fraction of the same book timeline [progress] measures. The two are
 * independent by design (see `PlaybackProgress.revealedMs`), which is exactly why the dock shows
 * both — the gap between them is the thing a listener needs to see, and the deck is the only
 * surface where it means anything.
 */
class CompanionTransport(
    val title: String,
    val coverPath: String?,
    val isPlaying: Boolean,
    val progress: () -> Float,
    val revealFraction: () -> Float,
    val positionLabel: () -> String,
    val onPlayPause: () -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit
)

/**
 * Builds a [CompanionTransport] that does not churn.
 *
 * Both player screens would otherwise construct one inline, and a fresh instance every 500ms tick
 * makes `CompanionDeck`'s parameter unequal on every tick — which drags the whole deck (cast grid,
 * character sheet, map) through a recomposition twice a second just so a progress bar can move.
 *
 * So the instance is remembered against the values that genuinely change rarely (title, cover,
 * play state) and the ticking values are reached through [rememberUpdatedState], which keeps one
 * stable `State` object the lambdas can close over. The read then happens where it belongs: inside
 * the dock's own `drawBehind`, which subscribes to that state and repaints without recomposing.
 */
@Composable
fun rememberCompanionTransport(
    title: String,
    coverPath: String?,
    isPlaying: Boolean,
    position: com.betteraudio.playback.PositionState,
    /** The companion's reveal cursor for this book — the second mark on the dock's rail. */
    revealedMs: Long,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit
): CompanionTransport {
    val pos = rememberUpdatedState(position)
    val rev = rememberUpdatedState(revealedMs)
    val play = rememberUpdatedState(onPlayPause)
    val back = rememberUpdatedState(onSkipBack)
    val forward = rememberUpdatedState(onSkipForward)
    return remember(title, coverPath, isPlaying) {
        CompanionTransport(
            title = title,
            coverPath = coverPath,
            isPlaying = isPlaying,
            progress = {
                val p = pos.value
                // Book-global, not file-local: the reveal cursor is measured against the whole
                // book, and a rail carrying both marks has to measure them on the same ruler.
                val total = if (p.bookTotalDurationMs > 0) p.bookTotalDurationMs else p.durationMs
                if (total > 0) (p.bookPositionMs.toFloat() / total).coerceIn(0f, 1f) else 0f
            },
            revealFraction = {
                val p = pos.value
                val total = if (p.bookTotalDurationMs > 0) p.bookTotalDurationMs else p.durationMs
                if (total > 0) (rev.value.toFloat() / total).coerceIn(0f, 1f) else 0f
            },
            positionLabel = {
                val p = pos.value
                val total = if (p.bookTotalDurationMs > 0) p.bookTotalDurationMs else p.durationMs
                val left = (total - p.bookPositionMs).coerceAtLeast(0L)
                if (total > 0) "${formatDeckHm(p.bookPositionMs)} · ${formatDeckHm(left)} left"
                else formatDeckHm(p.bookPositionMs)
            },
            onPlayPause = { play.value() },
            onSkipBack = { back.value() },
            onSkipForward = { forward.value() }
        )
    }
}

/**
 * Band 4 of the deck (docs/companion-redesign.html §02) — the player's transport, kept.
 *
 * The point of the dock is negative: it is what stops the companion from being a drawer. Opening
 * it used to cost you the controls at exactly the moment you reached for it ("who is this
 * again?"), and the fix is not a shortcut back to the player but the controls themselves, present.
 *
 * Its top edge is [DockRail], which carries two marks rather than one.
 *
 * [compact] is the landscape rail's dock: same parts, stacked instead of in a row, because 172dp
 * of rail cannot hold a cover, a label and three transport buttons side by side.
 */
@Composable
fun CompanionDock(
    transport: CompanionTransport,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    DeckDockSurface(modifier) {
        Column {
            DockRail(
                progress = transport.progress,
                revealFraction = transport.revealFraction,
                modifier = Modifier.fillMaxWidth().height(3.dp)
            )
            if (compact) CompactDockBody(transport) else WideDockBody(transport)
        }
    }
}

@Composable
private fun WideDockBody(transport: CompanionTransport) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockCover(transport.coverPath, 38.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = transport.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = deckOn(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            DockPositionLabel(transport.positionLabel)
        }
        Spacer(Modifier.width(8.dp))
        DockTransport(transport)
    }
}

@Composable
private fun CompactDockBody(transport: CompanionTransport) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DockCover(transport.coverPath, 32.dp)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = transport.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = deckOn(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                DockPositionLabel(transport.positionLabel)
            }
        }
        Spacer(Modifier.height(9.dp))
        DockTransport(transport)
    }
}

@Composable
private fun DockTransport(transport: CompanionTransport) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockSkip(Icons.Default.FastRewind, "Skip back", transport.onSkipBack)
        DockPlayPause(transport.isPlaying, transport.onPlayPause)
        DockSkip(Icons.Default.FastForward, "Skip forward", transport.onSkipForward)
    }
}

@Composable
private fun DockCover(coverPath: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(if (LocalAppTheme.current == AppTheme.IMMERSIVE) 9.dp else 8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        AsyncImage(
            model = coverPath?.let { File(it) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * The position read-out, isolated in its own composable *solely* so the 2 Hz tick behind
 * [CompanionTransport.positionLabel] invalidates one `Text` instead of the deck.
 */
@Composable
private fun DockPositionLabel(label: () -> String) {
    Text(
        text = label(),
        style = MaterialTheme.typography.labelSmall,
        color = deckOnMuted(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DockSkip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    PressFeel(Feel.Transport) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, Modifier.size(19.dp), tint = deckOn())
        }
    }
}

/**
 * The one filled control on the deck.
 *
 * Kept visually identical to the player's own play button (accent circle in Immersive, M3's
 * squircle in Material You) because it is the *same button* — when the morph lands, this is what
 * the player's play button becomes, and a button that changes shape mid-travel would read as two
 * buttons swapping rather than one moving.
 */
@Composable
private fun DockPlayPause(isPlaying: Boolean, onClick: () -> Unit) {
    val immersive = LocalAppTheme.current == AppTheme.IMMERSIVE
    PressFeel(Feel.Transport) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(if (immersive) 50 else 30))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pause" else "Play",
                Modifier.size(23.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/**
 * The dock's top edge: one track, two marks.
 *
 * - The **accent fill** is where the listener is, the ordinary scrubber read-out.
 * - The **notch** is `revealedMs` — how far the pack has been unsealed.
 *
 * Showing both is the whole reason the deck has its own rail instead of borrowing the player's.
 * The reveal cursor only advances by *listening*, never by seeking, and it starts at zero on a
 * book that was already part-listened when packs arrived — so on a 250-hour book the companion can
 * legitimately be describing chapter one while the listener is at chapter four hundred. Every
 * other surface in the app has no way to say that. Here the gap is simply visible, and tapping it
 * is what Timeline's catch-up acts on.
 *
 * When the two coincide (the normal case) the notch sits on the fill's leading edge and reads as
 * part of it — which is correct: there is nothing to report.
 *
 * Drawn in `drawBehind` with both read-outs as lambdas, so the 2 Hz position tick repaints this
 * rail without recomposing anything.
 */
@Composable
private fun DockRail(
    progress: () -> Float,
    revealFraction: () -> Float,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    val track = deckOnMuted().copy(alpha = 0.28f)
    // Deliberately NOT the accent: the reveal mark means something different from the playhead, and
    // painting it the same colour would state one fact twice instead of two facts once.
    val revealMark = deckOn().copy(alpha = 0.85f)
    Box(
        modifier.drawBehind {
            val h = size.height
            val r = h / 2f
            drawRoundRect(
                color = track,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )
            val p = progress().coerceIn(0f, 1f)
            if (p > 0f) {
                drawRoundRect(
                    color = accent,
                    size = Size(size.width * p, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
                )
            }
            val reveal = revealFraction().coerceIn(0f, 1f)
            // Only drawn when it has something to say. A notch permanently sitting under the
            // playhead would be chrome; one that appears when the companion falls behind is a
            // signal.
            if (reveal > 0.0005f && kotlin.math.abs(reveal - p) > 0.004f) {
                val w = 2.dp.toPx()
                val x = (size.width * reveal - w / 2f).coerceIn(0f, size.width - w)
                drawRoundRect(
                    color = revealMark,
                    topLeft = Offset(x, -h * 0.9f),
                    size = Size(w, h * 2.8f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f)
                )
            }
        }
    )
}

/** "4h 12m" / "34m" — coarse on purpose; this labels a position in a book, not a duration. */
fun formatDeckHm(ms: Long): String {
    val minutes = ms / 60_000
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
