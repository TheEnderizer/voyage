package com.betteraudio.data.transcribe

import com.betteraudio.data.db.entities.SyncAnchor
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.ebook.ParagraphCache
import com.betteraudio.data.ebook.SpineParagraphs
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.sync.AudioChapterSpan
import com.betteraudio.sync.AudioSpanBuilder
import com.betteraudio.sync.ChapterTitleMatcher
import com.betteraudio.sync.TextSimilarity
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Live progress of a per-book alignment run, exposed so the reader can show a status card. */
data class AlignProgress(
    val running: Boolean,
    val chaptersDone: Int,
    val chaptersTotal: Int,
    val anchorsFound: Int,
    val currentChapter: String? = null,
    val error: String? = null
)

/**
 * On-device forced alignment: transcribes short audio snippets with Vosk and matches them to the
 * epub's paragraph text, producing verified (audioMs ↔ charOffset) anchors that
 * [com.betteraudio.sync.PositionBridge] interpolates for paragraph-resolution sync. A @Singleton
 * with its own scope (like `SeriesPlayer`) so a long run survives leaving the reader; cancellable
 * per book. Anchors are replaced atomically on completion — a cancelled run keeps prior anchors.
 */
@Singleton
class SyncAligner @Inject constructor(
    private val repository: AudiobookRepository,
    private val modelManager: VoskModelManager,
    private val paragraphCache: ParagraphCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Long, Job>()

    private val _progress = MutableStateFlow<Map<Long, AlignProgress>>(emptyMap())
    val progress: StateFlow<Map<Long, AlignProgress>> = _progress.asStateFlow()

    fun start(bookId: Long) {
        if (jobs[bookId]?.isActive == true) return
        val job = scope.launch {
            try { align(bookId) }
            catch (e: Exception) {
                AppLog.e(LogCat.SYNC, "align failed for book=$bookId", e)
                setProgress(bookId) { it.copy(running = false, error = e.message ?: "Alignment failed") }
            } finally { jobs.remove(bookId) }
        }
        jobs[bookId] = job
    }

    fun cancel(bookId: Long) {
        jobs.remove(bookId)?.cancel()
        setProgress(bookId) { it.copy(running = false) }
    }

    // ── the run ──────────────────────────────────────────────────────────────

    private suspend fun align(bookId: Long) {
        val modelDir = modelManager.modelDirOrNull()
            ?: run { setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "Speech model not downloaded") }; return }

        val book = repository.getBookOnce(bookId)
            ?: run { setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "Book not found") }; return }
        val epubPath = book.ebookPath
            ?: run { setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "No ebook linked to this book") }; return }
        if (book.fileCount == 0) {
            setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "No audio to align") }
            return
        }

        val files = repository.getAudioFilesOnce(bookId).sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        if (files.isEmpty()) {
            setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "No audio to align") }
            return
        }
        val chapters = repository.getChaptersForBookOnce(bookId)
        val spans = AudioSpanBuilder.build(files, chapters)
        if (spans.isEmpty()) {
            setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "Could not determine audio chapter spans") }
            return
        }

        val parser = runCatching { EpubParser(File(epubPath)) }.getOrNull()
            ?: run { setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "Could not open ebook file") }; return }
        val info = runCatching { parser.parse() }.getOrNull()
        if (info == null) {
            parser.close()
            setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "Could not parse ebook") }
            return
        }
        if (info.encrypted) {
            parser.close()
            setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "Ebook is encrypted") }
            return
        }
        if (info.spine.isEmpty()) {
            parser.close()
            setProgress(bookId) { AlignProgress(false, 0, 0, 0, error = "No readable text in ebook") }
            return
        }

        // Book-timeline ms → (file, in-file offset), so a probe window maps to a real file span.
        val cumStart = LongArray(files.size)
        run { var t = 0L; files.forEachIndexed { i, f -> cumStart[i] = t; t += f.durationMs } }

        val totalSteps = spans.size * 2
        setProgress(bookId) { AlignProgress(true, 0, totalSteps, 0) }

        val model = runCatching { Model(modelDir.absolutePath) }.getOrNull()
            ?: run { parser.close(); setProgress(bookId) { AlignProgress(false, 0, totalSteps, 0, error = "Could not load speech model") }; return }

        // Whole-book token stream + inverted index (token → positions), built once. Pass 1 below
        // searches ALL of this rather than trusting the chapter map to point at the right spine —
        // so an approximate/wrong chapter map can't prevent matches (each snippet finds its true
        // location; the matched token carries its own spine/paragraph/char offset).
        val bookToks = ArrayList<TokenPos>()
        for (s in info.spine.indices) {
            coroutineContext.ensureActive()
            val paras = paragraphCache.get(bookId, s) { parser.readEntry(info.spine[s].href) } ?: continue
            bookToks.addAll(spineTokens(paras, s))
        }
        val tokenIndex = HashMap<String, MutableList<Int>>()
        bookToks.forEachIndexed { i, tp -> tokenIndex.getOrPut(tp.token) { ArrayList() }.add(i) }

        // Paragraph slices over the same token stream — what the title matcher scans instead of
        // every token position, since a chapter heading is always a paragraph of its own.
        val slices = paragraphSlices(bookToks)

        val raw = ArrayList<SyncAnchor>()
        try {
            // Pass 1: find where each audio span (chapter/"Part" file) begins in the text.
            // Audiobooks are frequently split into narration parts with no 1:1 correspondence to
            // epub chapters, so establishing a real per-span text range first is what lets pass 2
            // search that (small) range instead of the whole book on every probe.
            //
            // Two ways to get that start, cheapest first:
            //  1a. Match the span's own chapter TITLE against the epub's headings
            //      ([ChapterTitleMatcher]) — no audio decode, no Vosk. Hits on the common
            //      one-xhtml-per-chapter layout, which is most of the time.
            //  1b. Otherwise decode the span's first snippet and transcribe it, as before.
            // 1b still searches the WHOLE book rather than trusting the chapter map, for the
            // reason above: an approximate map must not be able to prevent a match. 1a is not that
            // — it is a verified hit on the actual heading text, or it does not fire at all.
            val startBookTokIdx = arrayOfNulls<Int>(spans.size)
            val totalAudioMs = spans.lastOrNull()?.endMs ?: 0L
            val maxDrift = (bookToks.size * TITLE_MAX_DRIFT_FRACTION).toInt().coerceAtLeast(1)
            var titleFrom = 0          // monotonicity floor for title matching only
            var titleHits = 0
            spans.forEachIndexed { i, span ->
                coroutineContext.ensureActive()
                setProgress(bookId) { it.copy(chaptersDone = i, currentChapter = span.title, anchorsFound = raw.size) }

                // 1a — title first.
                val expected = if (totalAudioMs > 0L && bookToks.isNotEmpty()) {
                    ((span.absStartMs.toDouble() / totalAudioMs) * bookToks.size).toInt()
                } else null
                val titleMatch = ChapterTitleMatcher.find(
                    title = span.title,
                    tokenAt = { k -> bookToks[k].token },
                    slices = slices,
                    searchFromToken = titleFrom,
                    expectedToken = expected,
                    maxDriftTokens = maxDrift
                )
                if (titleMatch != null) {
                    val tp = bookToks[titleMatch.tokenIndex]
                    startBookTokIdx[i] = titleMatch.tokenIndex
                    titleFrom = titleMatch.tokenIndex + 1
                    titleHits++
                    raw.add(
                        SyncAnchor(
                            bookId = bookId, audioMs = span.absStartMs, spineIndex = tp.spineIndex,
                            paragraphIndex = tp.paragraphIndex, charOffset = tp.charOffset,
                            confidence = titleMatch.score
                        )
                    )
                    AppLog.i(
                        LogCat.SYNC,
                        "TITLE @${span.absStartMs}ms '${span.title}' -> spine=${tp.spineIndex} char=${tp.charOffset} " +
                            "score=${"%.2f".format(titleMatch.score)} (skipped transcription)"
                    )
                    setProgress(bookId) { it.copy(anchorsFound = raw.size) }
                    return@forEachIndexed
                }

                // 1b — fall back to decode + transcribe.
                val startMs = probeOffsets(span).firstOrNull() ?: return@forEachIndexed
                probe(files, cumStart, startMs, bookToks, tokenIndex, model)?.let { r ->
                    startBookTokIdx[i] = r.windowStart
                    if (r.windowStart >= titleFrom) titleFrom = r.windowStart + 1
                    raw.add(r.anchor.copy(bookId = bookId))
                    setProgress(bookId) { it.copy(anchorsFound = raw.size) }
                }
            }
            AppLog.i(LogCat.SYNC, "pass 1: ${titleHits}/${spans.size} spans located by chapter title, ${spans.size - titleHits} by transcription")
            val ranges = SyncAlignerMath.spanSearchRanges(spans.size, startBookTokIdx, bookToks.size)

            // Pass 2: the remaining probes per span, restricted to that span's range.
            spans.forEachIndexed { i, span ->
                coroutineContext.ensureActive()
                setProgress(bookId) { it.copy(chaptersDone = spans.size + i, currentChapter = span.title, anchorsFound = raw.size) }
                for (probeStartMs in probeOffsets(span).drop(1)) {
                    coroutineContext.ensureActive()
                    probe(files, cumStart, probeStartMs, bookToks, tokenIndex, model, ranges[i])?.let { r ->
                        raw.add(r.anchor.copy(bookId = bookId))
                        setProgress(bookId) { it.copy(anchorsFound = raw.size) }
                    }
                }
            }

            // Anchors must advance in both audio time and text position; the two passes above
            // don't add them in audio-time order (pass 1 = one per span, pass 2 = the rest), so
            // sort first and then keep only what's monotonic.
            val accepted = ArrayList<SyncAnchor>()
            for (a in raw.sortedBy { it.audioMs }) {
                if (accepted.isEmpty() || SyncAlignerMath.isMonotonic(accepted.last(), a)) accepted.add(a)
            }

            // Replace-all on success only (cancellation throws before this and keeps old anchors).
            repository.deleteSyncAnchors(bookId)
            if (accepted.isNotEmpty()) repository.insertSyncAnchors(accepted)
            setProgress(bookId) { AlignProgress(false, totalSteps, totalSteps, accepted.size) }
            AppLog.i(LogCat.SYNC, "book=$bookId anchors=${accepted.size} across ${spans.size} chapters")

            // Mirror the result into the book's own data/ folder (best-effort) — so it travels
            // with a backup/restructure/device move and can be re-imported without another
            // on-device alignment run. A cluster ("::"-keyed) book gets its own <slug>.mapping.json
            // inside the shared data/ dir, same as its book.json; only a containing directory that
            // doesn't exist at all skips the write.
            if (accepted.isNotEmpty() && com.betteraudio.data.diskstore.BookDataPaths.containingDir(book.folderPath).isDirectory) {
                com.betteraudio.data.sync.MappingFileIO.write(book.folderPath, book.chapterMapJson, accepted)
            }
        } finally {
            runCatching { model.close() }
            parser.close()
        }
    }

    private data class ProbeResult(val anchor: SyncAnchor, val windowStart: Int)

    /** Decode one snippet, transcribe it, and (on a confident text match) return an anchor plus
     *  the matched book-token index (used to bootstrap per-span search ranges). [range], when
     *  given, restricts the text search to that slice of [bookToks] instead of the whole book. */
    private suspend fun probe(
        files: List<com.betteraudio.data.db.entities.AudioFile>,
        cumStart: LongArray,
        probeStartMs: Long,
        bookToks: List<TokenPos>,
        tokenIndex: Map<String, MutableList<Int>>,
        model: Model,
        range: IntRange? = null
    ): ProbeResult? {
        // Locate the file containing this book-ms and clamp the snippet window to that file.
        val fi = cumStart.indices.lastOrNull { probeStartMs >= cumStart[it] } ?: return null
        val inFileOffset = probeStartMs - cumStart[fi]
        val fileDur = files[fi].durationMs
        val windowMs = minOf(SNIPPET_MS, fileDur - inFileOffset)
        if (windowMs < 8_000) return null

        val pcm = AudioSnippetDecoder.decode(files[fi].filePath, inFileOffset, windowMs) ?: return null

        val recognizer = Recognizer(model, AudioSnippetDecoder.TARGET_HZ.toFloat()).apply { setWords(true) }
        try {
            var i = 0
            while (i < pcm.size) {
                val len = minOf(4000, pcm.size - i)
                recognizer.acceptWaveForm(pcm.copyOfRange(i, i + len), len)
                i += len
            }
            val json = JSONObject(recognizer.finalResult)
            val words = json.optJSONArray("result") ?: return null
            if (words.length() < MIN_WORDS) return null

            val transcript = ArrayList<String>(words.length())
            var confSum = 0.0
            var firstStartSec = 0.0
            for (w in 0 until words.length()) {
                val o = words.getJSONObject(w)
                val tok = TextSimilarity.normalize(o.optString("word"))
                if (tok.isNotEmpty()) transcript.add(tok)
                confSum += o.optDouble("conf", 1.0)
                if (w == 0) firstStartSec = o.optDouble("start", 0.0)
            }
            val meanConf = confSum / words.length()
            if (transcript.size < MIN_WORDS || meanConf < MIN_MEAN_CONF) return null

            val (windowStart, score) = matchTranscript(bookToks, tokenIndex, transcript, range) ?: return null
            AppLog.i(LogCat.SYNC, "probe @${probeStartMs}ms words=${transcript.size} conf=${"%.2f".format(meanConf)} bestScore=${"%.2f".format(score)}")
            if (score < ACCEPT_SCORE) return null
            val tp = bookToks[windowStart]
            val audioMs = probeStartMs + (firstStartSec * 1000).toLong()

            // Debug: dump exactly what Vosk heard vs. the epub text it matched to, so the match
            // quality can be inspected directly instead of trusting the score alone.
            val windowEnd = (windowStart + transcript.size).coerceAtMost(bookToks.size)
            val matchedEpubText = (windowStart until windowEnd).joinToString(" ") { bookToks[it].token }
            AppLog.i(
                LogCat.SYNC,
                "MATCH @${audioMs}ms spine=${tp.spineIndex} char=${tp.charOffset}\n" +
                    "  transcript: ${transcript.joinToString(" ")}\n" +
                    "  epub:       $matchedEpubText"
            )

            val anchor = SyncAnchor(
                bookId = 0L, audioMs = audioMs, spineIndex = tp.spineIndex,
                paragraphIndex = tp.paragraphIndex, charOffset = tp.charOffset, confidence = score
            )
            return ProbeResult(anchor, windowStart)
        } finally {
            runCatching { recognizer.close() }
        }
    }

    // ── matching + helpers ──────────────────────────────────────────────────

    private data class TokenPos(val token: String, val charOffset: Int, val paragraphIndex: Int, val spineIndex: Int)

    /** Collapses the token stream into one [ChapterTitleMatcher.ChapterSlice] per paragraph. A new
     *  paragraph starts wherever the (spineIndex, paragraphIndex) pair changes — the stream is
     *  built spine by spine and paragraph by paragraph, so that transition is the boundary. */
    private fun paragraphSlices(bookToks: List<TokenPos>): List<ChapterTitleMatcher.ChapterSlice> {
        if (bookToks.isEmpty()) return emptyList()
        val out = ArrayList<ChapterTitleMatcher.ChapterSlice>()
        var start = 0
        var indexInSpine = 0
        for (k in 1..bookToks.size) {
            val prev = bookToks[k - 1]
            val cur = bookToks.getOrNull(k)
            val boundary = cur == null || cur.spineIndex != prev.spineIndex || cur.paragraphIndex != prev.paragraphIndex
            if (!boundary) continue
            out.add(ChapterTitleMatcher.ChapterSlice(start, k, prev.spineIndex, indexInSpine))
            indexInSpine = if (cur != null && cur.spineIndex != prev.spineIndex) 0 else indexInSpine + 1
            start = k
        }
        return out
    }

    private fun spineTokens(paras: SpineParagraphs, spineIndex: Int): List<TokenPos> {
        val out = ArrayList<TokenPos>()
        for (p in paras.paragraphs) {
            var offset = p.charStart
            for (tok in p.normalizedText.split(' ')) {
                if (tok.isNotEmpty()) out.add(TokenPos(tok, offset, p.index, spineIndex))
                offset += tok.length + 1   // +1 for the joining space
            }
        }
        return out
    }

    /** Finds where [transcript] best matches the book token stream, using the inverted index to
     *  bound the search to windows anchored on the transcript's rarest (most discriminative)
     *  tokens. When [range] is given, only positions inside it are considered (the token position
     *  lists are ascending, so this is a binary-search bound, not a full rescan) — this is what
     *  makes pass-2 probing cheap instead of re-searching the whole book every time. Returns the
     *  best (windowStartIndex, score); the caller applies [ACCEPT_SCORE]. */
    private fun matchTranscript(
        bookToks: List<TokenPos>,
        tokenIndex: Map<String, MutableList<Int>>,
        transcript: List<String>,
        range: IntRange? = null
    ): Pair<Int, Float>? {
        val w = transcript.size
        if (w < MIN_WORDS || bookToks.size < w) return null
        val transSet = transcript.toHashSet()

        val searchStart = (range?.first ?: 0).coerceAtLeast(0)
        val searchEndExclusive = ((range?.last?.plus(1)) ?: bookToks.size).coerceAtMost(bookToks.size)
        if (searchEndExclusive - searchStart < w) return null

        // Candidate window starts: for each of the transcript's rarest tokens, every book position
        // it occurs at (within range) implies a window start (position − token's transcript index).
        val distinct = transSet.mapNotNull { t -> tokenIndex[t]?.let { t to it } }.sortedBy { it.second.size }
        val candidates = HashSet<Int>()
        var used = 0
        for ((tok, positions) in distinct) {
            if (positions.size > 300) break        // too common to be discriminative
            val ti = transcript.indexOf(tok)
            val lo = positions.binarySearch(searchStart).let { if (it < 0) -(it + 1) else it }
            for (idx in lo until positions.size) {
                val p = positions[idx]
                if (p >= searchEndExclusive) break
                val start = p - ti
                if (start in searchStart..(searchEndExclusive - w)) candidates.add(start)
            }
            if (++used >= 6 || candidates.size > 6000) break
        }
        if (candidates.isEmpty()) return null

        var bestIdx = -1
        var bestScore = 0f
        val windowTokens = ArrayList<String>(w)
        for (start in candidates) {
            windowTokens.clear()
            for (k in start until start + w) windowTokens.add(bookToks[k].token)
            val jac = SyncAlignerMath.jaccard(windowTokens.toHashSet(), transSet)
            if (jac >= 0.30f) {   // cheap prefilter before the O(w²) LCS
                val lcs = TextSimilarity.lcsLength(windowTokens, transcript).toFloat() / w
                val score = 0.6f * jac + 0.4f * lcs
                if (score > bestScore) { bestScore = score; bestIdx = start }
            }
        }
        // Contract: when no window scores above 0, this returns (0, 0f) as a sentinel rather than
        // null — callers must re-check the score (< ACCEPT_SCORE) before trusting the index; never
        // dereference the index alone.
        return if (bestIdx >= 0) bestIdx to bestScore else 0 to 0f
    }

    private fun probeOffsets(span: AudioChapterSpan): List<Long> {
        val out = ArrayList<Long>()
        var t = span.absStartMs + 15_000
        while (t + SNIPPET_MS <= span.endMs - 5_000) { out.add(t); t += PROBE_INTERVAL_MS }
        if (out.isEmpty() && span.durationMs > 20_000) out.add(span.absStartMs + 5_000)
        return out
    }

    private fun setProgress(bookId: Long, transform: (AlignProgress) -> AlignProgress) {
        _progress.update { m ->
            val cur = m[bookId] ?: AlignProgress(false, 0, 0, 0)
            m + (bookId to transform(cur))
        }
    }

    companion object {
        private const val SNIPPET_MS = 15_000L
        private const val PROBE_INTERVAL_MS = 150_000L
        private const val MIN_WORDS = 12
        private const val MIN_MEAN_CONF = 0.5
        private const val ACCEPT_SCORE = 0.55f
        /** How far from its proportionally-expected text position a title match may land, as a
         *  fraction of the book. Loose on purpose — front/back matter shifts the audio:text ratio
         *  for every chapter — it only rejects a grossly misplaced match, which left unchecked
         *  would cascade through the monotonic search into every later chapter. */
        private const val TITLE_MAX_DRIFT_FRACTION = 0.30
    }
}
