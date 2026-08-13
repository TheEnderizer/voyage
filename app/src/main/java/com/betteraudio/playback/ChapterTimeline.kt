package com.betteraudio.playback

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat

/**
 * One chapter's absolute position within its own book's timeline.
 *
 * [endMs] is DERIVED — the next mark's [startMs], or the timeline's `bookTotalMs` for the last
 * mark — never read from [Chapter.durationMs]/[rawDurationMs] directly. This is deliberate: an
 * embedded chapter list whose last entry doesn't reach end-of-file (e.g. the Nero `chpl` u8
 * chapter-count truncation past 255 chapters, see [com.betteraudio.data.scanner.ChapterExtractor])
 * would otherwise leave a chapter mark that doesn't span to where the audio actually ends.
 * [rawDurationMs] is kept alongside for callers (namely [com.betteraudio.sync.AudioSpanBuilder])
 * that need the originally-stored value for byte-identical behaviour.
 */
data class ChapterMark(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val rawDurationMs: Long,
    val fileId: Long,
    val fileIndex: Int,
    val key: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Where an absolute book-level position lands: a specific file plus an offset within it.
 * Callers should seek by matching [fileId] against a queue's `MediaItem.mediaId` first
 * (so a queue built in a different order can't mis-seek), falling back to [fileIndex].
 */
data class ChapterLocus(
    val fileId: Long,
    val fileIndex: Int,
    val offsetInFileMs: Long,
)

/**
 * The single shared source of truth for chapter boundaries — collapses what used to be four
 * independent reimplementations of the same cumulative-file-offset math (each with slightly
 * different orphan-chapter handling) plus three independent "which chapter is active" scans.
 *
 * Two construction paths:
 *  - [ofFiles] is SYNCHRONOUS and needs no chapter/DB data — one mark per file. Used to seed
 *    playback immediately (so a chapter pick right after `playBook` doesn't race an async load —
 *    see [PlayerController.buildAndPlay]/[PlayerController.loadTimeline]).
 *  - [build] is the full timeline, folding in embedded/per-file [Chapter] rows.
 *
 * Both order files identically to every existing caller: `compareBy({ trackNumber }, { fileName })`.
 */
class ChapterTimeline private constructor(
    val bookId: Long,
    val marks: List<ChapterMark>,
    val fileIds: List<Long>,
    val fileStartsMs: List<Long>,
    val bookTotalMs: Long,
    val hasChapterData: Boolean,
) {
    val isEmpty: Boolean get() = marks.isEmpty()
    val hasMultiple: Boolean get() = marks.size > 1

    /** Index of the mark containing [absMs], using [toleranceMs] slack on the boundary check. */
    fun indexAt(absMs: Long, toleranceMs: Long = 0L): Int {
        if (marks.isEmpty()) return -1
        val idx = marks.indexOfLast { it.startMs <= absMs + toleranceMs }
        return idx.coerceAtLeast(0)
    }

    /**
     * The chapter containing [absMs] for DISPLAY purposes (pill/scrubber/highlighted row) — uses
     * a [toleranceMs] slack (default [DISPLAY_TOLERANCE_MS]) matching the app's previous
     * `currentChapter()` behaviour, so nothing shifts visually.
     */
    fun chapterAt(absMs: Long, toleranceMs: Long = DISPLAY_TOLERANCE_MS): ChapterMark? {
        val idx = indexAt(absMs, toleranceMs)
        return marks.getOrNull(idx)
    }

    /**
     * Start of the next chapter, or null if [absMs] is already in the last one. Uses ZERO
     * tolerance (unlike [chapterAt]) — a tolerant index just before a boundary would already
     * report the next chapter, and "next" would then skip one entirely.
     */
    fun nextStartMs(absMs: Long): Long? {
        if (marks.isEmpty()) return null
        val idx = indexAt(absMs, toleranceMs = 0L)
        return marks.getOrNull(idx + 1)?.startMs
    }

    /**
     * Start of the current chapter if [absMs] is more than [restartThresholdMs] into it
     * (the standard "restart current" behaviour), else the start of the previous chapter, or
     * 0L if already at the first chapter.
     */
    fun prevStartMs(absMs: Long, restartThresholdMs: Long = PREV_RESTART_MS): Long? {
        if (marks.isEmpty()) return null
        val idx = indexAt(absMs, toleranceMs = 0L)
        val current = marks[idx]
        return if (absMs - current.startMs > restartThresholdMs) {
            current.startMs
        } else {
            marks.getOrNull(idx - 1)?.startMs ?: 0L
        }
    }

    /** Resolves an absolute book position to a file + in-file offset. */
    fun locate(absMs: Long): ChapterLocus? {
        if (fileStartsMs.isEmpty()) return null
        val fileIndex = fileStartsMs.indexOfLast { it <= absMs }.coerceAtLeast(0)
        val offset = (absMs - fileStartsMs[fileIndex]).coerceAtLeast(0L)
        return ChapterLocus(fileIds[fileIndex], fileIndex, offset)
    }

    fun startOfFileMs(fileId: Long): Long {
        val idx = fileIds.indexOf(fileId)
        return if (idx >= 0) fileStartsMs[idx] else 0L
    }

    companion object {
        const val DISPLAY_TOLERANCE_MS = 250L
        const val PREV_RESTART_MS = 3_000L

        val EMPTY = ChapterTimeline(
            bookId = -1L,
            marks = emptyList(),
            fileIds = emptyList(),
            fileStartsMs = emptyList(),
            bookTotalMs = 0L,
            hasChapterData = false,
        )

        private fun sortedFiles(files: List<AudioFile>): List<AudioFile> =
            files.sortedWith(compareBy({ it.trackNumber }, { it.fileName }))

        /** One mark per file — synchronous, no chapter/DB data needed. */
        fun ofFiles(files: List<AudioFile>, bookId: Long = -1L): ChapterTimeline {
            if (files.isEmpty()) return EMPTY.let { it.copy(bookId = bookId) }
            val sorted = sortedFiles(files)
            val fileIds = sorted.map { it.id }
            val fileStarts = ArrayList<Long>(sorted.size)
            var running = 0L
            for (f in sorted) { fileStarts.add(running); running += f.durationMs }
            val bookTotal = running
            val marks = sorted.mapIndexed { i, f ->
                val start = fileStarts[i]
                val end = fileStarts.getOrNull(i + 1) ?: bookTotal
                ChapterMark(
                    index = i,
                    title = f.chapterTitle ?: f.fileName,
                    startMs = start,
                    endMs = end.coerceAtLeast(start),
                    rawDurationMs = f.durationMs,
                    fileId = f.id,
                    fileIndex = i,
                    key = f.id,
                )
            }
            return ChapterTimeline(bookId, marks, fileIds, fileStarts, bookTotal, hasChapterData = false)
        }

        /** Full timeline: embedded/per-file [chapters] folded in, falling back to [ofFiles] when
         *  [chapters] is empty or every entry is dropped as an orphan. */
        fun build(files: List<AudioFile>, chapters: List<Chapter>, bookId: Long = -1L): ChapterTimeline {
            if (files.isEmpty()) return EMPTY.let { it.copy(bookId = bookId) }
            val sorted = sortedFiles(files)
            val fileIds = sorted.map { it.id }
            val fileIdSet = fileIds.toHashSet()
            val fileStarts = ArrayList<Long>(sorted.size)
            var running = 0L
            val fileById = HashMap<Long, AudioFile>(sorted.size)
            for (f in sorted) { fileStarts.add(running); running += f.durationMs; fileById[f.id] = f }
            val bookTotal = running
            val startByFileId = fileIds.zip(fileStarts).toMap()

            // Drop orphan chapters (fileId not among this book's files) instead of silently
            // anchoring them at position 0 — the bug all previous copies of this math shared.
            val valid = chapters.filter { it.fileId in fileIdSet }
            val orphanCount = chapters.size - valid.size
            if (orphanCount > 0) {
                AppLog.w(LogCat.PLAYBACK, "ChapterTimeline.build book=$bookId: dropped $orphanCount orphan chapter(s) (fileId not among this book's ${fileIds.size} file(s))")
            }
            if (valid.isEmpty()) {
                if (chapters.isNotEmpty()) AppLog.w(LogCat.PLAYBACK, "ChapterTimeline.build book=$bookId: all ${chapters.size} chapter(s) were orphans — falling back to one mark per file")
                return ofFiles(sorted, bookId)
            }

            data class Pending(val chapter: Chapter, val startMs: Long, val fileIndex: Int)
            val pending = valid
                .sortedBy { it.orderIndex }
                .map { c ->
                    val file = fileById.getValue(c.fileId)
                    val clampedOffset = c.startInFileMs.coerceIn(0L, file.durationMs)
                    val fileStart = startByFileId.getValue(c.fileId)
                    Pending(c, fileStart + clampedOffset, fileIds.indexOf(c.fileId))
                }
                .sortedBy { it.startMs }

            val marks = pending.mapIndexed { i, p ->
                val start = p.startMs
                val end = pending.getOrNull(i + 1)?.startMs ?: bookTotal
                ChapterMark(
                    index = i,
                    title = p.chapter.title,
                    startMs = start,
                    endMs = end.coerceAtLeast(start),
                    rawDurationMs = p.chapter.durationMs,
                    fileId = p.chapter.fileId,
                    fileIndex = p.fileIndex,
                    key = p.chapter.id,
                )
            }
            return ChapterTimeline(bookId, marks, fileIds, fileStarts, bookTotal, hasChapterData = true)
        }
    }

    /** Copy helper for the [EMPTY] singleton so call sites can carry a bookId through even when
     *  there's nothing to build a real timeline from. */
    private fun copy(bookId: Long): ChapterTimeline =
        ChapterTimeline(bookId, marks, fileIds, fileStartsMs, bookTotalMs, hasChapterData)
}
