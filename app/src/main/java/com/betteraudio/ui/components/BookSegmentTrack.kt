package com.betteraudio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The whole book as one hairline: a tick per chapter, everything already listened dimmed, and the
 * chapter you are currently in lit.
 *
 * Shared on purpose. This started as the Immersive player's `BookTickTrack` — its answer to a
 * labelled row plus an expandable second slider that duplicated the chapter scrubber directly above
 * it. It says the same thing that row did (where you are, how much is left) and one thing it never
 * could: how the book is *divided*, and which division you are inside.
 *
 * It now serves three places — both players' whole-book readout and the Book Info page — for two
 * reasons. The plain reason is that the page was showing a flat percentage while the player showed
 * the book's shape, for no reason anyone could name. The structural one is that the Book Info →
 * player transition carries this bar from the page down into the player (see
 * [com.betteraudio.ui.player.ResumeChoreography]), and an element can only travel between two
 * screens if it is literally the same element in both.
 *
 * Deliberately NOT tappable, in any of its homes. It is a picture of where you are, not a second
 * control: in the player the chapter slider sits directly above it, and a 2.5dp line under your
 * thumb was far too easy to hit by accident and jump the book. On the info page there is nothing to
 * seek yet at all.
 *
 * @param chapterStarts absolute start of every chapter in the book, ascending. Empty (or a single
 *        mark) is a legitimate state — a one-file book with no embedded chapters — and draws as a
 *        plain progress line with no divisions, which is the honest picture of that book.
 */
@Composable
fun BookSegmentTrack(
    positionMs: Long,
    totalMs: Long,
    chapterStarts: List<Long>,
    accent: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    if (totalMs <= 0L) return
    val inkTick = Color.Black.copy(alpha = 0.62f)
    // The chapter containing `positionMs`: the last mark that starts at or before it. Derived here
    // rather than taken as a parameter so every caller does not have to hold a ChapterTimeline —
    // the info page has a list of marks and no playback wiring at all.
    val curIndex = chapterStarts.indexOfLast { it <= positionMs }
    val curStart = chapterStarts.getOrNull(curIndex) ?: 0L
    val curEnd = chapterStarts.getOrNull(curIndex + 1) ?: totalMs

    Canvas(modifier.fillMaxWidth().height(TRACK_HEIGHT)) {
        val h = LINE_HEIGHT.toPx()
        val y = (size.height - h) / 2f
        fun x(ms: Long) = (ms.toFloat() / totalMs).coerceIn(0f, 1f) * size.width

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(0f, y),
            size = Size(size.width, h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        // Everything already played, quietly.
        drawRoundRect(
            color = accent.copy(alpha = 0.38f),
            topLeft = Offset(0f, y),
            size = Size(x(positionMs), h),
            cornerRadius = CornerRadius(h / 2f, h / 2f)
        )
        // The chapter you are in, at full strength — this is what makes position legible.
        if (chapterStarts.size > 1) {
            val litStart = x(curStart)
            val litWidth = (x(curEnd) - litStart).coerceAtLeast(h)
            drawRoundRect(
                color = accent,
                topLeft = Offset(litStart, y),
                size = Size(litWidth, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
        }
        // Chapter boundaries, drawn 2px wide so a split actually reads as a split rather than a
        // hairline artefact. A book with a lot of chapters counts in 2s, 3s or more instead of
        // trying to draw every one: past a certain density the ticks stop being information and
        // become a grey bar. The stride is chosen so roughly TARGET_TICKS marks survive, which
        // keeps the divisions legible at any chapter count while still showing the book's shape.
        val tickWidth = 2f
        val stride = ((chapterStarts.size + TARGET_TICKS - 1) / TARGET_TICKS).coerceAtLeast(1)
        // Even after striding, refuse to place two ticks closer than their own width plus a gap.
        val minGap = tickWidth * 3f
        var lastX = -minGap
        chapterStarts.forEachIndexed { index, startMs ->
            if (startMs <= 0L) return@forEachIndexed
            if (index % stride != 0) return@forEachIndexed
            val tx = x(startMs)
            if (tx - lastX < minGap) return@forEachIndexed
            lastX = tx
            drawRect(color = inkTick, topLeft = Offset(tx, y), size = Size(tickWidth, h))
        }
    }
}

/** The bar's own height. The line is thinner than this — the rest is the touch-free breathing room
 *  that keeps it from reading as an edge against whatever sits under it. */
val TRACK_HEIGHT = 10.dp
private val LINE_HEIGHT = 2.5.dp

/** How many chapter ticks the track aims to show before it starts counting in 2s, 3s, … */
private const val TARGET_TICKS = 24
