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

/** Fine-grained text locator down to a paragraph. */
data class ParagraphLocator(val spineIndex: Int, val paragraphIndex: Int, val fractionInParagraph: Float)

/** A verified sync point (from Tier-2 forced alignment): at [audioMs] on the book timeline the
 *  narration is at character [charOffset] of spine item [spineIndex]. Room-free so PositionBridge
 *  stays a pure module (the SyncAnchor entity is mapped to this at the call site). */
data class AnchorPoint(val audioMs: Long, val spineIndex: Int, val charOffset: Int)

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

    // ── char-offset conversions (Tier 1 proportional / Tier 2 anchor-interpolated) ──

    /** Audio position → (spine index, character offset into that spine's normalized text). With
     *  [anchors] for the spine, interpolate piecewise-linearly on (audioMs ↔ charOffset); without,
     *  fall back to proportional (runFraction × totalChars). */
    fun audioToChar(
        bookPositionMs: Long,
        chapters: List<AudioChapterSpan>,
        map: ChapterMap,
        spineCount: Int,
        totalCharsInSpine: Int,
        anchors: List<AnchorPoint> = emptyList()
    ): Pair<Int, Int> {
        val loc = audioToText(bookPositionMs, chapters, map, spineCount)
        val spineIdx = loc.spineIndex
        if (totalCharsInSpine <= 0) return spineIdx to 0
        val spineAnchors = anchors.filter { it.spineIndex == spineIdx }.sortedBy { it.audioMs }
        val charOffset = if (spineAnchors.isEmpty()) {
            (loc.fraction * totalCharsInSpine).toInt()
        } else {
            val rb = runBoundsFor(spineIdx, chapters, map, spineCount)
            val pts = controlPoints(rb, totalCharsInSpine, spineAnchors)
            interpolate(bookPositionMs.toFloat(), pts.map { it.first.toFloat() to it.second.toFloat() }).toInt()
        }
        return spineIdx to charOffset.coerceIn(0, totalCharsInSpine)
    }

    /** (spine index, character offset) → book-timeline audio position. Inverse of [audioToChar]. */
    fun charToAudio(
        spineIndex: Int,
        charOffset: Int,
        totalCharsInSpine: Int,
        chapters: List<AudioChapterSpan>,
        map: ChapterMap,
        anchors: List<AnchorPoint> = emptyList()
    ): Long {
        if (chapters.isEmpty()) return 0L
        val spineCount = (map.audioToSpine.maxOrNull()?.plus(1) ?: 0).coerceAtLeast(spineIndex + 1)
        val spineAnchors = anchors.filter { it.spineIndex == spineIndex }.sortedBy { it.charOffset }
        if (spineAnchors.isEmpty() || totalCharsInSpine <= 0) {
            val fraction = if (totalCharsInSpine > 0) charOffset.toFloat() / totalCharsInSpine else 0f
            return textToAudio(TextLocator(spineIndex, fraction.coerceIn(0f, 1f)), chapters, map)
        }
        val rb = runBoundsFor(spineIndex, chapters, map, spineCount)
        val pts = controlPoints(rb, totalCharsInSpine, spineAnchors)
        // Invert: interpolate charOffset (x) → audioMs (y).
        return interpolate(charOffset.toFloat(), pts.map { it.second.toFloat() to it.first.toFloat() }).toLong()
    }

    // ── anchor-only resolution (ignores ChapterMap entirely) ──────────────────

    /**
     * Locates (spineIndex, charOffset) using ONLY the Tier-2 anchors, ignoring the ChapterMap.
     * Audiobooks are frequently split into narration *parts* (Part 1.mp3, Part 2.mp3…) with no
     * 1:1 correspondence to epub chapters, so a title/number-matched [ChapterMap] can point at
     * entirely the wrong spine item — but each anchor was independently verified by searching the
     * WHOLE book's text (see `SyncAligner`), so anchors alone are immune to that mismatch. Returns
     * null when there are fewer than 2 anchors (caller should fall back to the ChapterMap-based
     * proportional mapping). [totalCharsFor] supplies a spine's total normalized-char count on
     * demand (only spines actually spanned by the bracketing anchor pair are queried).
     */
    fun audioToCharAnchored(
        bookPositionMs: Long,
        anchors: List<AnchorPoint>,
        totalCharsFor: (Int) -> Int
    ): Pair<Int, Int>? {
        if (anchors.size < 2) return null
        val sorted = anchors.sortedBy { it.audioMs }
        val i = sorted.indexOfLast { it.audioMs <= bookPositionMs }
        val (prev, next) = when {
            i < 0 -> sorted[0] to sorted[1]
            i >= sorted.size - 1 -> sorted[sorted.size - 2] to sorted[sorted.size - 1]
            else -> sorted[i] to sorted[i + 1]
        }
        if (prev.audioMs == next.audioMs) return prev.spineIndex to prev.charOffset
        val t = (bookPositionMs - prev.audioMs).toFloat() / (next.audioMs - prev.audioMs).toFloat()

        if (prev.spineIndex == next.spineIndex) {
            val charOffset = (prev.charOffset + t * (next.charOffset - prev.charOffset)).toInt()
            return prev.spineIndex to charOffset.coerceAtLeast(0)
        }

        // The pair straddles a spine transition: walk a single path made of prev's remaining
        // chars, any spines fully spanned in between, and next's leading chars, then land `t`
        // fraction of the way along it.
        val d1 = (totalCharsFor(prev.spineIndex).coerceAtLeast(prev.charOffset) - prev.charOffset).coerceAtLeast(0)
        val midSpines = (prev.spineIndex + 1) until next.spineIndex
        val midLens = midSpines.map { totalCharsFor(it).coerceAtLeast(0) }
        val d2 = next.charOffset.coerceAtLeast(0)
        val totalPath = (d1 + midLens.sum() + d2).coerceAtLeast(1)
        var travelled = (t * totalPath).coerceIn(0f, totalPath.toFloat())

        if (travelled <= d1) return prev.spineIndex to travelled.toInt()
        travelled -= d1
        for ((k, spineIdx) in midSpines.withIndex()) {
            val len = midLens[k]
            if (travelled <= len) return spineIdx to travelled.toInt()
            travelled -= len
        }
        return next.spineIndex to travelled.toInt().coerceAtMost(next.charOffset)
    }

    /** Inverse of [audioToCharAnchored]: (spineIndex, charOffset) → book-timeline audio position,
     *  using only the anchors. Returns null when there are fewer than 2 anchors. */
    fun charToAudioAnchored(
        spineIndex: Int,
        charOffset: Int,
        anchors: List<AnchorPoint>,
        totalCharsFor: (Int) -> Int
    ): Long? {
        if (anchors.size < 2) return null
        val sorted = anchors.sortedBy { it.audioMs }
        fun cmp(aSpine: Int, aChar: Int, bSpine: Int, bChar: Int): Int =
            if (aSpine != bSpine) aSpine - bSpine else aChar - bChar

        val i = sorted.indexOfLast { cmp(it.spineIndex, it.charOffset, spineIndex, charOffset) <= 0 }
        val (prev, next) = when {
            i < 0 -> sorted[0] to sorted[1]
            i >= sorted.size - 1 -> sorted[sorted.size - 2] to sorted[sorted.size - 1]
            else -> sorted[i] to sorted[i + 1]
        }

        if (prev.spineIndex == next.spineIndex) {
            val span = next.charOffset - prev.charOffset
            val t = if (span == 0) 0f else (charOffset - prev.charOffset).toFloat() / span
            return (prev.audioMs + t.coerceIn(0f, 1f) * (next.audioMs - prev.audioMs)).toLong()
        }

        val d1 = (totalCharsFor(prev.spineIndex).coerceAtLeast(prev.charOffset) - prev.charOffset).coerceAtLeast(0)
        val midSpines = (prev.spineIndex + 1) until next.spineIndex
        val midLens = midSpines.map { totalCharsFor(it).coerceAtLeast(0) }
        val d2 = next.charOffset.coerceAtLeast(0)
        val totalPath = (d1 + midLens.sum() + d2).coerceAtLeast(1)

        val travelled = when {
            spineIndex == prev.spineIndex -> (charOffset - prev.charOffset).coerceAtLeast(0)
            spineIndex == next.spineIndex -> d1 + midLens.sum() + charOffset
            else -> {
                val k = midSpines.indexOf(spineIndex)
                if (k < 0) d1 else d1 + midLens.take(k).sum() + charOffset
            }
        }
        val t = (travelled.toFloat() / totalPath).coerceIn(0f, 1f)
        return (prev.audioMs + t * (next.audioMs - prev.audioMs)).toLong()
    }

    // ── internals ────────────────────────────────────────────────────────────

    /** Audio-time bounds (start, end) covering [spineIdx]: the contiguous run of chapters that
     *  resolve to it. When none map directly (a spine item with no mapped audio) it interpolates a
     *  proportional slice of the whole book so callers still get a sane range. */
    private fun runBoundsFor(spineIdx: Int, chapters: List<AudioChapterSpan>, map: ChapterMap, spineCount: Int): Pair<Long, Long> {
        val mapped = chapters.indices.filter { resolveSpine(it, map, spineCount) == spineIdx }
        if (mapped.isNotEmpty()) {
            return chapters[mapped.first()].absStartMs to chapters[mapped.last()].endMs
        }
        val total = chapters.last().endMs.coerceAtLeast(1L)
        val width = total.toFloat() / spineCount.coerceAtLeast(1)
        return (spineIdx * width).toLong() to ((spineIdx + 1) * width).toLong()
    }

    /** Ordered (audioMs, charOffset) control points for a spine: the run edges plus the anchors,
     *  each clamped into range and forced monotonic non-decreasing so interpolation never inverts. */
    private fun controlPoints(runBounds: Pair<Long, Long>, totalChars: Int, anchors: List<AnchorPoint>): List<Pair<Long, Int>> {
        val (startMs, endMs) = runBounds
        val raw = ArrayList<Pair<Long, Int>>()
        raw.add(startMs to 0)
        anchors.forEach { raw.add(it.audioMs.coerceIn(startMs, endMs) to it.charOffset.coerceIn(0, totalChars)) }
        raw.add(endMs to totalChars)
        raw.sortBy { it.first }
        // Enforce monotonic non-decreasing charOffset as audioMs increases.
        val out = ArrayList<Pair<Long, Int>>(raw.size)
        var lastChar = 0
        for ((ms, ch) in raw) {
            val c = ch.coerceAtLeast(lastChar)
            if (out.isNotEmpty() && out.last().first == ms) out[out.lastIndex] = ms to c
            else out.add(ms to c)
            lastChar = c
        }
        return out
    }

    /** Piecewise-linear interpolation of y at x over sorted (x, y) control points. */
    private fun interpolate(x: Float, points: List<Pair<Float, Float>>): Float {
        if (points.isEmpty()) return 0f
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        for (i in 1 until points.size) {
            val (x1, y1) = points[i]
            if (x <= x1) {
                val (x0, y0) = points[i - 1]
                val span = (x1 - x0)
                val t = if (span == 0f) 0f else (x - x0) / span
                return y0 + t * (y1 - y0)
            }
        }
        return points.last().second
    }

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
