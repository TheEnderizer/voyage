package com.betteraudio.data.scanner

import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.BookGroupRepository
import com.betteraudio.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After a scan, automatically joins books that clearly belong to the same work into a
 * [com.betteraudio.data.db.entities.BookGroup], so they play back-to-back. Three signals,
 * applied in order of confidence (only ungrouped books are considered, so books the user
 * has manually grouped are left alone):
 *
 *  1. **Similar numbered title** — "Mistborn 1", "Mistborn 2" → stem "mistborn".
 *  2. **Same series + same author** — series-container siblings ("Blood of Elves",
 *     "Time of Contempt" under "The Witcher" by one author). The same-author guard avoids
 *     merging a whole library root whose top-level standalone books were tagged with the
 *     root folder's name.
 *  3. **Sequential chapter numbering** — book A has tracks 1–10, book B 11–20, same author.
 *
 * Runs only on an explicit user import/scan (not the silent launch rescan) so that a group
 * the user later splits is not immediately re-created.
 */
@Singleton
class AutoJoiner @Inject constructor(
    private val repository: AudiobookRepository,
    private val groupRepository: BookGroupRepository,
    private val settings: SettingsStore
) {

    suspend fun run() {
        val books = repository.getAllUngroupedOnce()
        if (books.size < 2) return
        val used = HashSet<Long>()

        // 1) Numbered-title stem
        groupByKey(books) { seriesStem(it.title) }.forEach { grp ->
            val ordered = grp.sortedWith(compareBy({ bookNumber(it) ?: Int.MAX_VALUE }, { it.title.lowercase() }))
            join(displayName(seriesStem(ordered.first().title) ?: ordered.first().title), ordered, used)
        }

        // 2) Same series name AND single shared author
        groupByKey(books.filter { it.id !in used }) { it.seriesName?.lowercase()?.trim()?.ifBlank { null } }
            .forEach { grp ->
                val authors = grp.mapNotNull { it.author.trim().lowercase().ifBlank { null } }.toSet()
                if (authors.size != 1) return@forEach   // mixed/empty authors → not a real series
                val ordered = grp.sortedWith(compareBy({ it.seriesOrder ?: Float.MAX_VALUE }, { it.title.lowercase() }))
                join(ordered.first().seriesName ?: ordered.first().title, ordered, used)
            }

        // 3) Sequential track numbering among same-author books
        val remaining = books.filter { it.id !in used && it.author.isNotBlank() }
        remaining.groupBy { it.author.trim().lowercase() }.values.forEach { grp ->
            if (grp.size < 2) return@forEach
            val ranged = grp.mapNotNull { b -> trackRange(b)?.let { b to it } }
            sequentialChains(ranged).forEach { chain ->
                join(chain.first().title, chain, used)
            }
        }
    }

    private inline fun groupByKey(books: List<Book>, key: (Book) -> String?): List<List<Book>> {
        val map = LinkedHashMap<String, MutableList<Book>>()
        for (b in books) key(b)?.let { map.getOrPut(it) { mutableListOf() }.add(b) }
        return map.values.filter { it.size >= 2 }
    }

    private suspend fun join(name: String, ordered: List<Book>, used: MutableSet<Long>) {
        if (ordered.size < 2 || ordered.any { it.id in used }) return
        groupRepository.createGroup(
            name = name,
            coverArtPath = ordered.firstNotNullOfOrNull { it.coverArtPath },
            playbackSpeed = settings.currentDefaultSpeed,
            orderedBookIds = ordered.map { it.id }
        )
        used += ordered.map { it.id }
    }

    /** Title stem before a sequence number; null if the title has no number. */
    private fun seriesStem(title: String): String? {
        val t = title.lowercase().trim()
        val m = Regex("^(.+?)[\\s\\-_:#]*(?:book|vol\\.?|volume|part|pt\\.?|no\\.?|#)?\\s*(\\d{1,3})(?:\\b|\$)")
            .find(t) ?: return null
        return m.groupValues[1].trim().trim('-', '_', ':', '#', '.', ' ').ifBlank { null }
    }

    private fun bookNumber(b: Book): Int? {
        b.seriesOrder?.let { return it.toInt() }
        return Regex("(?:book|vol\\.?|volume|part|pt\\.?|no\\.?|#)?\\s*(\\d{1,3})\\b")
            .find(b.title.lowercase())?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun displayName(s: String): String =
        s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Range from min to max of real track numbers, or null when not cleanly numbered. */
    private suspend fun trackRange(b: Book): IntRange? {
        val files = repository.getAudioFilesOnce(b.id)
        if (files.size < 2) return null
        val nums = files.map { it.trackNumber }.filter { it > 0 }
        if (nums.size < files.size) return null
        return nums.min()..nums.max()
    }

    /** Chain books whose track ranges are disjoint and consecutive (prevMax + 1 == nextMin). */
    private fun sequentialChains(ranged: List<Pair<Book, IntRange>>): List<List<Book>> {
        val sorted = ranged.sortedBy { it.second.first }
        val chains = mutableListOf<MutableList<Book>>()
        var current: MutableList<Book>? = null
        var prevMax = Int.MIN_VALUE
        for ((book, range) in sorted) {
            if (current != null && range.first == prevMax + 1) {
                current.add(book)
            } else {
                current = mutableListOf(book)
                chains.add(current)
            }
            prevMax = range.last
        }
        return chains.filter { it.size >= 2 }
    }
}
