package com.betteraudio.companion.fandom

import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.playback.ChapterTimeline
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a **draft** cast for a book from its Fandom wiki (docs/companion-packs.md §9.4) — the
 * "Pregenerate from wiki" path, which exists because the blank-pack experience asks the listener
 * to type in a cast they can already see documented elsewhere.
 *
 * Nothing here writes: this produces a [Preview] the listener reviews, deselects from and edits.
 * That is the whole shape of the feature — a seed, not an import. Applying it is
 * [com.betteraudio.companion.CompanionAuthorRepository.addSeededEntities].
 *
 * ### The cutoff is the feature
 *
 * Every fact is gated and anchored by the chapter the wiki cites for it ([FandomWikitext]), and
 * chapters become audio positions through [ChapterNumbering]. When that mapping cannot be made,
 * the service does not guess — it reports [ChapterNumbering.Kind.NONE], the dialog asks the
 * listener what chapter they are on, and the facts land at the position they are listening from.
 */
@Singleton
class FandomSeedService @Inject constructor(
    private val client: FandomWikiClient,
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val audioFileDao: AudioFileDao,
    private val audiobookRepository: AudiobookRepository
) {

    /** One fact, already placed on the book's timeline. */
    data class PlacedFact(
        val field: String,
        val value: String,
        /** The chapter the wiki cited, or null for an undatable value (appearance, description). */
        val chapter: Int?,
        /** Book-global ms this fact reveals at. */
        val globalMs: Long
    )

    data class Candidate(
        val name: String,
        val facts: List<PlacedFact>,
        val withheld: Int,
        val sourceUrl: String,
        /** The article's own lead image — the infobox portrait. Null when the wiki has none. */
        val portrait: FandomWikiClient.ImageRef? = null
    )

    data class Preview(
        val wiki: FandomWikiClient.Wiki,
        val cutoffChapter: Int,
        val positioning: ChapterNumbering.Kind,
        val characters: List<Candidate>,
        /**
         * The community's map, if it has one. Offered alongside the cast rather than as a separate
         * action because it is the same act — "take what this wiki already knows about the book" —
         * and a map board that has to be created empty and then hand-fed a picture is a board
         * nobody creates.
         */
        val map: FandomWikiClient.ImageRef? = null
    ) {
        val factCount: Int get() = characters.sumOf { it.facts.size }
        val withheldCount: Int get() = characters.sumOf { it.withheld }
        val portraitCount: Int get() = characters.count { it.portrait != null }
    }

    /** What the dialog pre-fills itself with before the listener touches anything. */
    data class Detected(
        val bookTitle: String,
        val wiki: FandomWikiClient.Wiki?,
        /** Best guess at where the listener is, from the reveal cursor. Null when the book's
         *  chapters cannot be numbered — the listener is then asked outright. */
        val cutoffChapter: Int?,
        val positioning: ChapterNumbering.Kind
    )

    sealed interface SeedResult {
        data class Success(val preview: Preview) : SeedResult
        data class Error(val message: String) : SeedResult
    }

    /** Fetches one image's bytes. A thin pass-through so callers never touch the HTTP client. */
    suspend fun downloadImage(url: String): ByteArray? = client.downloadImage(url)

    private suspend fun mappingFor(bookId: Long): ChapterNumbering.Mapping {
        val files = audioFileDao.getFilesForBookOnce(bookId)
        val chapters = chapterDao.getChaptersForBookOnce(bookId)
        return ChapterNumbering.of(ChapterTimeline.build(files, chapters, bookId).marks)
    }

    /**
     * How far into the story the listener actually is, book-global ms.
     *
     * The **furthest** of the reveal cursor and the saved resume position, deliberately. Using
     * `revealedMs` alone was wrong in practice: it starts at 0 and only advances through continuous
     * listening (§6), so it reads 0 for anyone who imported a pack, restored a backup, or has
     * simply never triggered the catch-up — and a cutoff of "chapter 1" makes the whole seed come
     * back empty, which is exactly how this feature first failed on a real device. The resume
     * position is the honest floor for "what have I already heard", and taking the max means a
     * listener who *has* run the catch-up keeps its more accurate answer.
     */
    private suspend fun listenedThroughMs(bookId: Long): Long {
        val revealedMs = audiobookRepository.getRevealedMs(bookId)
        val progress = audiobookRepository.getProgressForBookOnce(bookId)
        val positionMs = (progress?.filesBeforeCurrentMs ?: 0L) + (progress?.positionMs ?: 0L)
        return maxOf(revealedMs, positionMs)
    }

    /** Guesses the wiki and the listener's chapter, so the dialog opens already filled in. */
    suspend fun detect(bookId: Long): Detected? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        val mapping = mappingFor(bookId)
        return Detected(
            bookTitle = book.displayTitle,
            wiki = client.resolveWiki(book.displayTitle),
            cutoffChapter = mapping.chapterAt(listenedThroughMs(bookId)),
            positioning = mapping.kind
        )
    }

    /**
     * Fetches and parses up to [limit] characters.
     *
     * @param cutoffChapter the listener's position in the story — supplied by the dialog, which
     *   pre-fills it from [detect] but always lets it be corrected.
     */
    suspend fun preview(
        bookId: Long,
        slug: String,
        cutoffChapter: Int,
        limit: Int
    ): SeedResult {
        val wiki = client.describe(slug)
            ?: return SeedResult.Error("No wiki found at $slug.fandom.com")
        val titles = client.findCharacters(slug, limit)
        if (titles.isEmpty()) {
            return SeedResult.Error("${wiki.siteName} has no character category this can read.")
        }

        val mapping = mappingFor(bookId)
        // Where an undatable fact goes: the position the listener has already reached, so seeding
        // never puts a fact in front of them that they have not earned, and never hides one they
        // have. Matches the anchor rule the manual "Add fact" dialog already uses.
        val fallbackMs = listenedThroughMs(bookId)

        // One batched request for every portrait, before the per-article loop — N articles would
        // otherwise mean N extra round trips on a connection that has already made N.
        val portraits = client.leadImages(slug, titles)

        val characters = titles.mapNotNull { title ->
            val wikitext = client.fetchLead(slug, title) ?: return@mapNotNull null
            val url = wiki.pageUrl(title)
            val parsed = FandomWikitext.parseLead(title, wikitext, cutoffChapter, url)
            val placed = parsed.facts.map { fact ->
                PlacedFact(
                    field = fact.field,
                    value = fact.value,
                    chapter = fact.chapter,
                    // A cited chapter the mapping cannot place (past the last chapter mark, or
                    // no mapping at all) falls back rather than being dropped — the fact is
                    // already known to be at or before the cutoff, so the listener has earned it.
                    globalMs = fact.chapter?.let { mapping.positionOf(it) } ?: fallbackMs
                )
            }
            if (placed.isEmpty()) null
            else Candidate(parsed.name, placed, parsed.withheld, url, portraits[title])
        }

        if (characters.isEmpty()) {
            return SeedResult.Error(
                "Nothing could be read from ${wiki.siteName} without spoilers — its articles may not cite chapters."
            )
        }
        val map = client.findMapImage(slug)
        AppLog.i(
            LogCat.NET,
            "fandom seed for book $bookId: ${characters.size} character(s), " +
                "${characters.sumOf { it.facts.size }} fact(s), " +
                "${characters.count { it.portrait != null }} portrait(s), " +
                "map=${map?.fileTitle ?: "none"}, " +
                "${characters.sumOf { it.withheld }} withheld past chapter $cutoffChapter"
        )
        return SeedResult.Success(
            Preview(wiki, cutoffChapter, mapping.kind, characters, map)
        )
    }
}
