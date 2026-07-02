package com.betteraudio.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.ui.components.FrostedOverlay
import com.betteraudio.ui.theme.Pill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Per-book listening history shown as an in-player frosted overlay (not a system sheet):
 * a list of completed listening sessions and confirmed navigation skips. Opened from the
 * player 3-dot menu and the book-info screen.
 */
@Composable
fun BookHistoryOverlay(
    visible: Boolean,
    sessions: List<ListeningSession>,
    skips: List<SkipEvent>,
    onResumeSession: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    FrostedOverlay(visible = visible, onDismiss = onDismiss) {
        val onScrim = Color.White
        val onScrimMuted = Color.White.copy(alpha = 0.6f)
        val accent = MaterialTheme.colorScheme.primary
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.Black.copy(alpha = 0.42f),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, null, Modifier.size(22.dp), tint = accent)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Listening history",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = onScrim
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = onScrimMuted)
                    }
                }

                if (sessions.isEmpty() && skips.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
                        Text(
                            "No listening history yet.\nPlay this book and it will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = onScrimMuted
                        )
                    }
                    return@Column
                }

                LazyColumn(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (sessions.isNotEmpty()) {
                        item { SectionHeader("Sessions", accent) }
                        items(sessions, key = { "s${it.id}" }) {
                            SessionRow(it, onScrim, onScrimMuted, accent) { onResumeSession(it.endBookPositionMs) }
                        }
                    }
                    if (skips.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            SectionHeader("Skips", accent)
                        }
                        items(skips, key = { "k${it.id}" }) { SkipRow(it, onScrim, onScrimMuted, accent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, accent: Color) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = accent
    )
}

@Composable
private fun SessionRow(s: ListeningSession, onScrim: Color, muted: Color, accent: Color, onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onResume)
            .padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Lead with chapter + position-within-chapter — that's what matters, not book offset.
            Text(
                chapterPosLine(s),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = onScrim,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${dayLabel(s.startMs)} · ${clock(s.startMs)}",
                style = MaterialTheme.typography.labelSmall, color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            humanDuration(s.listenedMs),
            style = MaterialTheme.typography.labelLarge,
            color = onScrim
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.PlayArrow, "Resume from here", Modifier.size(20.dp), tint = accent)
    }
}

@Composable
private fun SkipRow(k: SkipEvent, onScrim: Color, muted: Color, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.GraphicEq, null, Modifier.size(18.dp).clip(Pill), tint = accent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${hms(k.fromPositionMs)} → ${hms(k.toPositionMs)}",
                style = MaterialTheme.typography.bodyMedium, color = onScrim
            )
            val sub = buildString {
                append(chapterLabel(k.chapterIndex, k.chapterName)).append(" · ")
                append("${dayLabel(k.atMs)} ${clock(k.atMs)}")
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ── Formatting helpers ──────────────────────────────────────────────────────
private val dayFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
private val clockFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun dayLabel(ms: Long): String = dayFmt.format(Date(ms))
private fun clock(ms: Long): String = clockFmt.format(Date(ms))

private fun chapterLabel(index: Int, @Suppress("UNUSED_PARAMETER") name: String): String =
    if (index >= 0) "Ch ${index + 1}" else "Ch ?"

/** Chapter + position-within-chapter for a session, e.g. "Ch 13 · 04:32 → 11:08". */
private fun chapterPosLine(s: ListeningSession): String {
    val sPos = hms(s.startPositionInChapterMs)
    val ePos = hms(s.endPositionInChapterMs)
    return if (s.startChapterIndex == s.endChapterIndex) {
        "${chapterLabel(s.endChapterIndex, s.endChapterName)}  ·  $sPos → $ePos"
    } else {
        "${chapterLabel(s.startChapterIndex, s.startChapterName)} $sPos → " +
            "${chapterLabel(s.endChapterIndex, s.endChapterName)} $ePos"
    }
}

private fun humanDuration(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

private fun hms(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
