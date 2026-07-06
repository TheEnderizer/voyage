package com.betteraudio.sync

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Chapter

/**
 * Flattens a book's Chapter rows (or, if none exist, one span per audio file) onto the book-wide
 * timeline — the same cumulative-file-offset math `PlayerViewModel` uses for
 * `ChapterRow.Item.absStartMs`/`durationMs`. Shared here so the ebook reader's "Listen from here"
 * and the player's "Read from here" build an identical audio-side timeline for [PositionBridge].
 */
object AudioSpanBuilder {

    fun build(files: List<AudioFile>, chapters: List<Chapter>): List<AudioChapterSpan> {
        val sortedFiles = files.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        val cumulative = cumulativeStarts(sortedFiles.map { it.id to it.durationMs })

        return if (chapters.isNotEmpty()) {
            chapters.sortedBy { it.orderIndex }.mapIndexed { i, c ->
                AudioChapterSpan(
                    index = i,
                    title = c.title,
                    absStartMs = (cumulative[c.fileId] ?: 0L) + c.startInFileMs,
                    durationMs = c.durationMs
                )
            }
        } else {
            sortedFiles.mapIndexed { i, f ->
                AudioChapterSpan(
                    index = i,
                    title = f.chapterTitle ?: f.fileName,
                    absStartMs = cumulative[f.id] ?: 0L,
                    durationMs = f.durationMs
                )
            }
        }
    }

    private fun cumulativeStarts(idDur: List<Pair<Long, Long>>): Map<Long, Long> {
        val map = HashMap<Long, Long>(idDur.size)
        var t = 0L
        idDur.forEach { (id, dur) -> map[id] = t; t += dur }
        return map
    }
}
