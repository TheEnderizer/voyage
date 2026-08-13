package com.betteraudio.sync

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.ebook.ParagraphExtractor
import com.betteraudio.data.ebook.SpineParagraphs
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The reverse of what `PlayerViewModel.readFromHere` / `EbookReaderViewModel.audioPositionToLocator`
 * already do (audio → text): converts a book's saved *reading* position into a book-timeline audio
 * ms, so starting playback can pick up exactly where the user left off reading — the two "last
 * position" memories (audio and text) are otherwise independent per [PlaybackProgress], linked only
 * by which was more recently touched ([PlaybackProgress.lastMode]).
 */
object TextToAudioResume {

    /** Returns the audio position to resume at if the book's *reading* position is the freshest
     *  activity (`lastMode == "TEXT"`) and there's a connected epub to convert from; null when
     *  there's nothing to bridge (audio is already freshest, no epub, or conversion isn't
     *  possible) — callers should fall back to their normal audio-only resume in that case. */
    suspend fun resolve(
        book: Book,
        progress: PlaybackProgress?,
        files: List<AudioFile>,
        repository: AudiobookRepository
    ): Long? {
        if (progress?.lastMode != "TEXT") return null
        val epubPath = book.ebookPath ?: run {
            AppLog.d(LogCat.SYNC) { "TextToAudioResume book=${book.id}: lastMode=TEXT but no ebook connected — falling back to audio-only resume" }
            return null
        }
        val spineIndex = progress.textSpineIndex ?: return null
        val fraction = progress.textFraction ?: return null
        if (files.isEmpty()) return null

        val chapters = repository.getChaptersForBookOnce(book.id)
        val spans = AudioSpanBuilder.build(files, chapters)
        if (spans.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            runCatching {
                EpubParser(File(epubPath)).use { parser ->
                    val info = parser.parse()
                    if (info.encrypted || spineIndex !in info.spine.indices) {
                        AppLog.w(LogCat.SYNC, "TextToAudioResume book=${book.id}: epub encrypted=${info.encrypted} or spineIndex=$spineIndex out of range (${info.spine.size} spine item(s))")
                        return@use null
                    }

                    val paraCache = HashMap<Int, SpineParagraphs?>()
                    fun paragraphsFor(idx: Int): SpineParagraphs? =
                        paraCache.getOrPut(idx) {
                            info.spine.getOrNull(idx)?.href?.let { href ->
                                parser.readEntry(href)?.let { ParagraphExtractor.extract(it) }
                            }
                        }

                    val anchors = repository.getSyncAnchorsOnce(book.id)
                        .map { AnchorPoint(it.audioMs, it.spineIndex, it.charOffset) }

                    val paras = paragraphsFor(spineIndex)
                    val map = ChapterMap.fromJson(book.chapterMapJson) ?: ChapterMatcher.autoMatch(spans, info.spine)

                    if (paras == null || paras.totalChars == 0) {
                        AppLog.d(LogCat.SYNC) { "TextToAudioResume book=${book.id}: spine=$spineIndex has no paragraph data — using chapter-map-proportional fallback" }
                        return@use PositionBridge.textToAudio(TextLocator(spineIndex, fraction), spans, map)
                    }
                    val charOffset = paras.charOffsetForFraction(fraction)

                    if (anchors.size >= 2) {
                        PositionBridge.charToAudioAnchored(spineIndex, charOffset, anchors) { idx ->
                            paragraphsFor(idx)?.totalChars ?: 0
                        }?.let {
                            AppLog.d(LogCat.SYNC) { "TextToAudioResume book=${book.id}: resolved via ${anchors.size} anchor(s) -> ${it}ms" }
                            return@use it
                        }
                    }
                    val audioMs = PositionBridge.charToAudio(spineIndex, charOffset, paras.totalChars, spans, map, anchors)
                    AppLog.d(LogCat.SYNC) { "TextToAudioResume book=${book.id}: resolved via chapter map (no/insufficient anchors) -> ${audioMs}ms" }
                    audioMs
                }
            }.onFailure { AppLog.w(LogCat.SYNC, "TextToAudioResume book=${book.id}: failed, falling back to audio-only resume: ${it.message}") }
                .getOrNull()
        }
    }
}
