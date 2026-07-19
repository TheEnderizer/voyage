package com.betteraudio.ui.widget

import com.betteraudio.widget.model.WidgetSnapshot

/** Which state feeds the editor's live canvas preview. */
enum class PreviewMode { LIVE, SAMPLE }

/** A believable fake book so the editor is fully usable with nothing playing. */
val SAMPLE_WIDGET_SNAPSHOT = WidgetSnapshot(
    bookId = -1,
    title = "The Fellowship of the Ring",
    author = "J.R.R. Tolkien",
    chapterTitle = "Chapter 3: Three Is Company",
    seriesName = "The Lord of the Rings",
    isPlaying = true,
    speed = 1.25f,
    boostDb = 3,
    bookCoverPath = null,
    seriesCoverPath = null,
    positionMs = 2 * 3_600_000L + 14 * 60_000L,
    bookDurationMs = 19 * 3_600_000L,
    chapterPositionMs = 6 * 60_000L,
    chapterDurationMs = 24 * 60_000L,
    sleepEndAtElapsedMs = 0,
    sleepRemainingMs = 0,
    writtenAtElapsedRealtimeMs = 0,
)
