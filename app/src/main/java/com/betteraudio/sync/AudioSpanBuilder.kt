package com.betteraudio.sync

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.playback.ChapterTimeline

/**
 * Flattens a book's Chapter rows (or, if none exist, one span per audio file) onto the book-wide
 * timeline. A thin adapter over [ChapterTimeline] — the shared boundary math also behind the
 * player's chapter nav/pill/scrubber — so the ebook reader's "Listen from here" and the player's
 * "Read from here" build an identical audio-side timeline for [PositionBridge].
 *
 * Deliberately uses [com.betteraudio.playback.ChapterMark.rawDurationMs], NOT the derived
 * [com.betteraudio.playback.ChapterMark.durationMs]: [AudioChapterSpan.endMs] feeds
 * [PositionBridge]/[ChapterMatcher]'s ebook-sync mapping, and switching to derived spans would
 * change that mapping for every book with gappy/truncated chapter markers — a separate,
 * independently-testable migration, not a side effect of this refactor. The one intentional
 * behaviour change versus the old inline implementation: an orphan chapter (whose `fileId` isn't
 * among the book's files) is now dropped instead of silently anchored at book position 0.
 */
object AudioSpanBuilder {

    fun build(files: List<AudioFile>, chapters: List<Chapter>): List<AudioChapterSpan> =
        ChapterTimeline.build(files, chapters).marks.map { m ->
            AudioChapterSpan(
                index = m.index,
                title = m.title,
                absStartMs = m.startMs,
                durationMs = m.rawDurationMs,
            )
        }
}
