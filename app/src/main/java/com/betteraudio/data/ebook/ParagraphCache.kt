package com.betteraudio.data.ebook

import android.util.LruCache
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of parsed spine paragraphs, keyed by (bookId, spineIndex). Shared across the
 * reader, the player's "Read from here", and the sync aligner so an epub spine item is parsed at
 * most once per session. Memory-only — paragraph offsets are cheap to recompute and never need to
 * survive a restart.
 */
@Singleton
class ParagraphCache @Inject constructor() {

    private val cache = LruCache<Long, SpineParagraphs>(24)

    private fun key(bookId: Long, spineIndex: Int): Long = (bookId shl 20) or (spineIndex.toLong() and 0xFFFFF)

    /** Returns cached paragraphs for the spine item, extracting from [readEntry] on a miss.
     *  Null only when the entry can't be read; an empty/image-only item yields an empty result. */
    fun get(bookId: Long, spineIndex: Int, readEntry: () -> ByteArray?): SpineParagraphs? {
        val k = key(bookId, spineIndex)
        cache.get(k)?.let { return it }
        val bytes = readEntry() ?: return null
        val parsed = ParagraphExtractor.extract(bytes)
        cache.put(k, parsed)
        return parsed
    }

    /** Drop every cached spine of a book — call when its epub changes (connect/disconnect). */
    fun invalidate(bookId: Long) {
        // LruCache has no key iteration; snapshot and evict matching keys.
        val prefix = bookId shl 20
        cache.snapshot().keys.filter { it and (0xFFFFFL.inv()) == prefix }.forEach { cache.remove(it) }
    }
}
