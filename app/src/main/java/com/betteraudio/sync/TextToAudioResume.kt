package com.betteraudio.sync

import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.ebook.EpubParser
import com.betteraudio.data.ebook.ParagraphExtractor
import com.betteraudio.data.ebook.SpineParagraphs
import com.betteraudio.data.repository.AudiobookRepository
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
        val epubPath = book.ebookPath ?: return null
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
                    if (info.encrypted || spineIndex !in info.spine.indices) return@use null

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
                        return@use PositionBridge.textToAudio(TextLocator(spineIndex, fraction), spans, map)
                    }
                    val charOffset = paras.charOffsetForFraction(fraction)

                    if (anchors.size >= 2) {
                        PositionBridge.charToAudioAnchored(spineIndex, charOffset, anchors) { idx ->
                            paragraphsFor(idx)?.totalChars ?: 0
                        }?.let { return@use it }
                    }
                    PositionBridge.charToAudio(spineIndex, charOffset, paras.totalChars, spans, map, anchors)
                }
            }.getOrNull()
        }
    }
}
