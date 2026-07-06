package com.betteraudio.sync

import org.json.JSONArray
import kotlin.math.floor

/** One audio chapter flattened onto the book-wide timeline (same math as
 *  `PlayerViewModel.ChapterRow.Item.absStartMs/durationMs`: cumulative file starts + in-file
 *  chapter offsets). This is the audio side of the sync anchor. */
data class AudioChapterSpan(
    val index: Int,
    val title: String,
    val absStartMs: Long,
    val durationMs: Long
) {
    val endMs: Long get() = absStartMs + durationMs
}

/** Text-side locator: which epub spine item, and how far (0..1) into it. */
data class TextLocator(val spineIndex: Int, val fraction: Float)

/**
 * Alignment between audio chapters and epub spine items: `audioToSpine[i]` is the spine index that
 * audio chapter `i` corresponds to, or -1 if unmapped (front/back matter, or a chapter
 * [ChapterMatcher] couldn't confidently match — [PositionBridge] interpolates across these).
 * Must be non-decreasing over mapped entries (monotonic in reading order).
 */
data class ChapterMap(val audioToSpine: List<Int>) {
    fun toJson(): String = JSONArray(audioToSpine).toString()

    companion object {
        fun fromJson(s: String?): ChapterMap? {
            if (s.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONArray(s)
                ChapterMap((0 until arr.length()).map { arr.getInt(it) })
            }.getOrNull()
        }
    }
}

/**
 * Pure conversions between the audio timeline and epub text locators through a shared canonical
 * anchor — (chapter index, fraction within that chapter). Every function here is a pure
 * mathematical mapping; no I/O, no Android APIs, fully unit-testable.
 */
object PositionBridge {

    /** The canonical anchor for a book-timeline position: which audio chapter, and how far into it. */
    fun audioAnchor(bookPositionMs: Long, chapters: List<AudioChapterSpan>): Pair<Int, Float> {
        if (chapters.isEmpty()) return -1 to 0f
        val idx = chapters.indexOfLast { bookPositionMs >= it.absStartMs }.let { if (it < 0) 0 else it }
        val chapter = chapters[idx]
        val frac = if (chapter.durationMs > 0)
            ((bookPositionMs - chapter.absStartMs).toFloat() / chapter.durationMs).coerceIn(0f, 1f)
        else 0f
        return idx to frac
    }

    /** Convert a book-timeline audio position into a text locator (epub spine index + fraction). */
    fun audioToText(
        bookPositionMs: Long,
        chapters: List<AudioChapterSpan>,
        map: ChapterMap,
        spineCount: Int
    ): TextLocator {
        if (spineCount <= 0) return TextLocator(0, 0f)
        if (chapters.isEmpty() || map.audioToSpine.isEmpty()) {
            // No chapter structure at all: fall back to whole-book proportional mapping using the
            // last known total duration implied by the chapters list (if any), else spine 0.
            return TextLocator(0, 0f)
        }
        val (chapterIdx, fracInChapter) = audioAnchor(bookPositionMs, chapters)
        val spineIdx = resolveSpine(chapterIdx, map, spineCount)
        if (spineIdx < 0) return proportionalText(bookPositionMs, chapters, spineCount)

        // Find the contiguous run of audio chapters mapped to this same spine item, so the
        // fraction reflects position-within-run rather than position-within-one-chapter (an epub
        // chapter can span several audio chapters, e.g. one xhtml per "Part").
        val runStart = chapters.indices.firstOrNull { i -> resolveSpine(i, map, spineCount) == spineIdx } ?: chapterIdx
        val runEndExclusive = chapters.indices.firstOrNull { i -> i > runStart && resolveSpine(i, map, spineCount) != spineIdx }
            ?: chapters.size
        val runStartMs = chapters[runStart].absStartMs
        val runEndMs = chapters[runEndExclusive - 1].endMs
        val runDurationMs = (runEndMs - runStartMs).coerceAtLeast(1L)
        val posInRunMs = (chapters[chapterIdx].absStartMs - runStartMs) + (fracInChapter * chapters[chapterIdx].durationMs)
        val fraction = (posInRunMs / runDurationMs.toFloat()).coerceIn(0f, 1f)
        return TextLocator(spineIdx, fraction)
    }

    /** Convert a text locator (epub spine index + fraction) into a book-timeline audio position,
     *  suitable for `PlayerController.bookSeekTo`. */
    fun textToAudio(locator: TextLocator, chapters: List<AudioChapterSpan>, map: ChapterMap): Long {
        if (chapters.isEmpty()) return 0L
        val spineCount = map.audioToSpine.maxOrNull()?.plus(1) ?: 0
        if (map.audioToSpine.isEmpty() || spineCount <= 0) {
            return proportionalAudio(locator, chapters, spineCount.coerceAtLeast(locator.spineIndex + 1))
        }
        // Audio chapters mapped to this exact spine index form the contiguous run to seek within.
        val mappedIndices = chapters.indices.filter { resolveSpine(it, map, spineCount) == locator.spineIndex }
        if (mappedIndices.isEmpty()) {
            // No audio chapter maps directly to this spine item (e.g. it's between two mapped
            // items) — interpolate on the audio timeline between the nearest mapped neighbors.
            return interpolateAudioForUnmappedSpine(locator, chapters, map, spineCount)
        }
        val runStart = mappedIndices.first()
        val runEndExclusive = mappedIndices.last() + 1
        val runStartMs = chapters[runStart].absStartMs
        val runEndMs = chapters[runEndExclusive - 1].endMs
        val runDurationMs = (runEndMs - runStartMs).coerceAtLeast(1L)
        return runStartMs + (locator.fraction * runDurationMs).toLong()
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Spine index for audio chapter [chapterIdx], with linear interpolation across unmapped (-1)
     *  entries between the nearest mapped neighbors (audio-time weighted). */
    private fun resolveSpine(chapterIdx: Int, map: ChapterMap, spineCount: Int): Int {
        val raw = map.audioToSpine.getOrNull(chapterIdx) ?: return proportionalSpineIndex(chapterIdx, map.audioToSpine.size, spineCount)
        if (raw >= 0) return raw.coerceIn(0, spineCount - 1)

        val before = (chapterIdx - 1 downTo 0).firstOrNull { (map.audioToSpine.getOrNull(it) ?: -1) >= 0 }
        val after = (chapterIdx + 1 until map.audioToSpine.size).firstOrNull { (map.audioToSpine.getOrNull(it) ?: -1) >= 0 }
        return when {
            before != null && after != null -> {
                val beforeSpine = map.audioToSpine[before]
                val afterSpine = map.audioToSpine[after]
                val t = (chapterIdx - before).toFloat() / (after - before)
                (beforeSpine + t * (afterSpine - beforeSpine)).toInt().coerceIn(0, spineCount - 1)
            }
            before != null -> map.audioToSpine[before]
            after != null -> map.audioToSpine[after]
            else -> proportionalSpineIndex(chapterIdx, map.audioToSpine.size, spineCount)
        }
    }

    private fun proportionalSpineIndex(chapterIdx: Int, chapterCount: Int, spineCount: Int): Int {
        if (chapterCount <= 0 || spineCount <= 0) return 0
        return floor(chapterIdx.toFloat() * spineCount / chapterCount).toInt().coerceIn(0, spineCount - 1)
    }

    private fun proportionalText(bookPositionMs: Long, chapters: List<AudioChapterSpan>, spineCount: Int): TextLocator {
        val total = chapters.last().endMs.coerceAtLeast(1L)
        val f = (bookPositionMs.toFloat() / total).coerceIn(0f, 1f)
        val spineIdx = floor(f * spineCount).toInt().coerceIn(0, spineCount - 1)
        val spineWidth = 1f / spineCount
        val fracInSpine = ((f - spineIdx * spineWidth) / spineWidth).coerceIn(0f, 1f)
        return TextLocator(spineIdx, fracInSpine)
    }

    private fun proportionalAudio(locator: TextLocator, chapters: List<AudioChapterSpan>, spineCount: Int): Long {
        val total = chapters.last().endMs
        val spineWidth = 1f / spineCount.coerceAtLeast(1)
        val f = (locator.spineIndex * spineWidth) + (locator.fraction * spineWidth)
        return (f.coerceIn(0f, 1f) * total).toLong()
    }

    private fun interpolateAudioForUnmappedSpine(
        locator: TextLocator, chapters: List<AudioChapterSpan>, map: ChapterMap, spineCount: Int
    ): Long {
        val entries = map.audioToSpine.withIndex().filter { it.value >= 0 }
        val before = entries.lastOrNull { it.value <= locator.spineIndex }
        val after = entries.firstOrNull { it.value > locator.spineIndex }
        return when {
            before != null && after != null -> {
                val beforeMs = chapters.getOrNull(before.index)?.absStartMs ?: 0L
                val afterMs = chapters.getOrNull(after.index)?.absStartMs ?: chapters.last().endMs
                val spineSpan = (after.value - before.value).coerceAtLeast(1)
                val t = ((locator.spineIndex - before.value) + locator.fraction) / spineSpan
                beforeMs + (t * (afterMs - beforeMs)).toLong()
            }
            before != null -> chapters.getOrNull(before.index)?.absStartMs ?: 0L
            after != null -> chapters.getOrNull(after.index)?.absStartMs ?: 0L
            else -> proportionalAudio(locator, chapters, spineCount)
        }
    }
}
