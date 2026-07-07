package com.betteraudio.data.transcribe

import com.betteraudio.data.db.entities.SyncAnchor
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.ebook.ParagraphCache
import com.betteraudio.data.ebook.SpineParagraphs
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.sync.AudioChapterSpan
import com.betteraudio.sync.AudioSpanBuilder
import com.betteraudio.sync.TextSimilarity
import com.betteraudio.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
                AppLog.e("Aligner", "align failed for book=$bookId", e)
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

        val book = repository.getBookById(bookId).first() ?: return
        val epubPath = book.ebookPath ?: return
        if (book.fileCount == 0) return   // ebook-only: no audio to align

        val files = repository.getAudioFilesOnce(bookId).sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        if (files.isEmpty()) return
        val chapters = repository.getChaptersForBookOnce(bookId)
        val spans = AudioSpanBuilder.build(files, chapters)
        if (spans.isEmpty()) return

        val parser = runCatching { EpubParser(File(epubPath)) }.getOrNull() ?: return
        val info = runCatching { parser.parse() }.getOrNull()
        if (info == null || info.encrypted || info.spine.isEmpty()) { parser.close(); return }

        // Book-timeline ms → (file, in-file offset), so a probe window maps to a real file span.
        val cumStart = LongArray(files.size)
        run { var t = 0L; files.forEachIndexed { i, f -> cumStart[i] = t; t += f.durationMs } }

        setProgress(bookId) { AlignProgress(true, 0, spans.size, 0) }

        val model = runCatching { Model(modelDir.absolutePath) }.getOrNull()
            ?: run { parser.close(); setProgress(bookId) { AlignProgress(false, 0, spans.size, 0, error = "Could not load speech model") }; return }

        // Whole-book token stream + inverted index (token → positions), built once. Anchoring
        // searches ALL of the book's text rather than trusting the chapter map to point at the
        // right spine — so an approximate/wrong chapter map can't prevent matches (each snippet
        // finds its true location; the matched token carries its own spine/paragraph/char offset).
        val bookToks = ArrayList<TokenPos>()
        for (s in info.spine.indices) {
            coroutineContext.ensureActive()
            val paras = paragraphCache.get(bookId, s) { parser.readEntry(info.spine[s].href) } ?: continue
            bookToks.addAll(spineTokens(paras, s))
        }
        val tokenIndex = HashMap<String, MutableList<Int>>()
        bookToks.forEachIndexed { i, tp -> tokenIndex.getOrPut(tp.token) { ArrayList() }.add(i) }

        val accepted = ArrayList<SyncAnchor>()
        try {
            spans.forEachIndexed { doneIdx, span ->
                coroutineContext.ensureActive()
                setProgress(bookId) { it.copy(chaptersDone = doneIdx, currentChapter = span.title, anchorsFound = accepted.size) }
                for (probeStartMs in probeOffsets(span)) {
                    coroutineContext.ensureActive()
                    val anchor = probe(files, cumStart, probeStartMs, bookToks, tokenIndex, model)
                    if (anchor != null && (accepted.isEmpty() || isMonotonic(accepted.last(), anchor))) {
                        accepted.add(anchor.copy(bookId = bookId))
                        setProgress(bookId) { it.copy(anchorsFound = accepted.size) }
                    }
                }
            }

            // Replace-all on success only (cancellation throws before this and keeps old anchors).
            repository.deleteSyncAnchors(bookId)
            if (accepted.isNotEmpty()) repository.insertSyncAnchors(accepted)
            setProgress(bookId) { AlignProgress(false, spans.size, spans.size, accepted.size) }
            AppLog.i("Aligner", "book=$bookId anchors=${accepted.size} across ${spans.size} chapters")
        } finally {
            runCatching { model.close() }
            parser.close()
        }
    }

    /** Decode one snippet, transcribe it, and (on a confident text match) return an anchor. */
    private suspend fun probe(
        files: List<com.betteraudio.data.db.entities.AudioFile>,
        cumStart: LongArray,
        probeStartMs: Long,
        bookToks: List<TokenPos>,
        tokenIndex: Map<String, MutableList<Int>>,
        model: Model
    ): SyncAnchor? {
        // Locate the file containing this book-ms and clamp the 25 s window to that file.
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

            val (windowStart, score) = matchTranscript(bookToks, tokenIndex, transcript) ?: return null
            AppLog.i("Aligner", "probe @${probeStartMs}ms words=${transcript.size} conf=${"%.2f".format(meanConf)} bestScore=${"%.2f".format(score)}")
            if (score < ACCEPT_SCORE) return null
            val tp = bookToks[windowStart]
            val audioMs = probeStartMs + (firstStartSec * 1000).toLong()

            // Debug: dump exactly what Vosk heard vs. the epub text it matched to, so the match
            // quality can be inspected directly instead of trusting the score alone.
            val windowEnd = (windowStart + transcript.size).coerceAtMost(bookToks.size)
            val matchedEpubText = (windowStart until windowEnd).joinToString(" ") { bookToks[it].token }
            AppLog.i(
                "Aligner",
                "MATCH @${audioMs}ms spine=${tp.spineIndex} char=${tp.charOffset}\n" +
                    "  transcript: ${transcript.joinToString(" ")}\n" +
                    "  epub:       $matchedEpubText"
            )

            return SyncAnchor(
                bookId = 0L, audioMs = audioMs, spineIndex = tp.spineIndex,
                paragraphIndex = tp.paragraphIndex, charOffset = tp.charOffset, confidence = score
            )
        } finally {
            runCatching { recognizer.close() }
        }
    }

    // ── matching + helpers ──────────────────────────────────────────────────

    private data class TokenPos(val token: String, val charOffset: Int, val paragraphIndex: Int, val spineIndex: Int)

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

    /** Finds where [transcript] best matches the whole-book token stream, using the inverted index
     *  to bound the search to windows anchored on the transcript's rarest (most discriminative)
     *  tokens. Returns the best (windowStartIndex, score); the caller applies [ACCEPT_SCORE]. */
    private fun matchTranscript(
        bookToks: List<TokenPos>,
        tokenIndex: Map<String, MutableList<Int>>,
        transcript: List<String>
    ): Pair<Int, Float>? {
        val w = transcript.size
        if (w < MIN_WORDS || bookToks.size < w) return null
        val transSet = transcript.toHashSet()

        // Candidate window starts: for each of the transcript's rarest tokens, every book position
        // it occurs at implies a window start (position − token's index within the transcript).
        val distinct = transSet.mapNotNull { t -> tokenIndex[t]?.let { t to it } }.sortedBy { it.second.size }
        val candidates = HashSet<Int>()
        var used = 0
        for ((tok, positions) in distinct) {
            if (positions.size > 300) break        // too common to be discriminative
            val ti = transcript.indexOf(tok)
            for (p in positions) {
                val start = p - ti
                if (start in 0..(bookToks.size - w)) candidates.add(start)
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
            val jac = jaccard(windowTokens.toHashSet(), transSet)
            if (jac >= 0.30f) {   // cheap prefilter before the O(w²) LCS
                val lcs = TextSimilarity.lcsLength(windowTokens, transcript).toFloat() / w
                val score = 0.6f * jac + 0.4f * lcs
                if (score > bestScore) { bestScore = score; bestIdx = start }
            }
        }
        return if (bestIdx >= 0) bestIdx to bestScore else 0 to 0f
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        return a.intersect(b).size.toFloat() / a.union(b).size
    }

    /** Anchors must advance in both audio time and text position. */
    private fun isMonotonic(prev: SyncAnchor, next: SyncAnchor): Boolean =
        next.audioMs > prev.audioMs &&
            (next.spineIndex > prev.spineIndex || (next.spineIndex == prev.spineIndex && next.charOffset > prev.charOffset))

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
        private const val SNIPPET_MS = 25_000L
        private const val PROBE_INTERVAL_MS = 150_000L
        private const val MIN_WORDS = 12
        private const val MIN_MEAN_CONF = 0.5
        private const val ACCEPT_SCORE = 0.55f
    }
}
