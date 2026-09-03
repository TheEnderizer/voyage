package com.betteraudio.companion.fandom

import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only access to a Fandom community's MediaWiki API.
 *
 * Fandom's own convenience endpoints (`/api/v1/Wikis/ByString`, `/api/v1/Search/CrossWiki`) have
 * been retired and now answer 404, so there is no global "which wiki is this book?" lookup to call.
 * [resolveWiki] therefore does the only thing that still works reliably: derives candidate
 * subdomains from the book's title and asks each one whether it exists. That is also why the UI
 * lets the listener correct the wiki by hand — the guess is a convenience, never a requirement.
 *
 * A browser-ish `User-Agent` is mandatory, not cargo-culted: Fandom sits behind Cloudflare and a
 * request without one is served an interstitial challenge page instead of JSON.
 */
@Singleton
class FandomWikiClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Wiki(val slug: String, val siteName: String) {
        val host: String get() = "$slug.fandom.com"
        fun pageUrl(title: String): String =
            "https://$host/wiki/" + title.replace(' ', '_')
    }

    /**
     * Category names tried in order. A wiki that curates its own "main" category is answering the
     * listener's question directly and is trusted over any ranking we could invent — Shadow Slave's
     * `Category:Main Cast`, for instance, is exactly the seven characters a reader would name.
     * `Characters` is the broad fallback and routinely holds hundreds, which is what
     * [findCharacters]'s size ranking exists to cut down.
     */
    private val CATEGORY_CANDIDATES = listOf(
        "Main Characters", "Main Character", "Main Cast", "Protagonists",
        "Major Characters", "Characters"
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun get(slug: String, query: String): JSONObject? {
        val url = "https://$slug.fandom.com/api.php?format=json&$query"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.w(LogCat.NET, "fandom $slug: status=${response.code} for $query")
                    return null
                }
                val body = response.body?.string() ?: return null
                if (!body.trimStart().startsWith("{")) {
                    // Cloudflare interstitial or an HTML error page.
                    AppLog.w(LogCat.NET, "fandom $slug: non-JSON response (${body.length} bytes)")
                    return null
                }
                JSONObject(body)
            }
        } catch (e: Exception) {
            AppLog.w(LogCat.NET, "fandom $slug request failed: ${e.message}")
            null
        }
    }

    /**
     * Candidate subdomains for a book title, best guess first: "Shadow Slave" → `shadowslave`,
     * `shadow-slave`, `shadowslavewiki`. A subtitle after `:` or a trailing parenthetical is
     * dropped first — "Mistborn: The Final Empire" should look for `mistborn`.
     */
    fun slugCandidates(bookTitle: String): List<String> {
        val head = bookTitle
            .substringBefore(':')
            .replace(Regex("""\(.*?\)"""), "")
            .trim()
        val words = head.lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.isNotBlank() && it !in setOf("the", "a", "an") }
        if (words.isEmpty()) return emptyList()
        val joined = words.joinToString("")
        return listOf(joined, words.joinToString("-"), joined + "wiki", words.first())
            .distinct()
            .filter { it.length >= 3 }
    }

    /** Confirms a wiki exists and returns its display name. */
    suspend fun describe(slug: String): Wiki? = withContext(Dispatchers.IO) {
        val json = get(slug, "action=query&meta=siteinfo&siprop=general") ?: return@withContext null
        val name = json.optJSONObject("query")?.optJSONObject("general")?.optString("sitename")
        if (name.isNullOrBlank()) null else Wiki(slug, name)
    }

    /** The first candidate subdomain that actually resolves, or null. */
    suspend fun resolveWiki(bookTitle: String): Wiki? {
        for (slug in slugCandidates(bookTitle)) {
            describe(slug)?.let { return it }
        }
        return null
    }

    /**
     * The [limit] most significant character articles.
     *
     * Ranking is by **article byte length**, which is a blunt instrument that happens to be an
     * excellent one: on the Shadow Slave wiki it orders Sunny (110 KB), Nephis (64 KB), Cassie
     * (27 KB) … Gantry (1 KB), which is the same order a reader would give and the same order a
     * whole-text mention count gives. It also costs nothing — `prop=info` returns every candidate's
     * length in the one request that lists them.
     */
    suspend fun findCharacters(slug: String, limit: Int): List<String> = withContext(Dispatchers.IO) {
        val sizes = LinkedHashMap<String, Int>()
        for (category in CATEGORY_CANDIDATES) {
            val json = get(
                slug,
                "action=query&generator=categorymembers&gcmtitle=${encode("Category:$category")}" +
                    "&gcmlimit=500&gcmnamespace=0&prop=info"
            )
            val pages = json?.optJSONObject("query")?.optJSONObject("pages")
            if (pages != null) {
                for (key in pages.keys()) {
                    val page = pages.optJSONObject(key) ?: continue
                    val title = page.optString("title").takeIf { it.isNotBlank() } ?: continue
                    sizes.putIfAbsent(title, page.optInt("length", 0))
                }
            }
            if (category != "Characters" && sizes.size >= CURATED_ENOUGH) break
        }
        val ordered = sizes.entries.sortedByDescending { it.value }
        AppLog.i(LogCat.NET, "fandom $slug: ${sizes.size} character candidate(s), taking $limit")
        ordered.take(limit).map { it.key }
    }

    /** Lead-section wikitext (section 0) — the infobox plus the opening paragraphs, and nothing
     *  from the plot summary below it, which on any wiki is nothing but spoilers. */
    suspend fun fetchLead(slug: String, title: String): String? = withContext(Dispatchers.IO) {
        val json = get(slug, "action=parse&page=${encode(title)}&prop=wikitext&section=0")
        json?.optJSONObject("parse")?.optJSONObject("wikitext")?.optString("*")?.takeIf { it.isNotBlank() }
    }

    // ── Images (§9.4) ──────────────────────────────────────────────────────────────────────
    //
    // A wiki is not just prose. The two things it holds that a listener cannot reasonably type in
    // are the cast's faces and the world's map, and both were left on the table by the text-only
    // seed: `PackEntity.media` and `PackBoard.artMedia` existed in the format with nothing ever
    // filling them.
    //
    // Spoiler note: images are *not* gated by the chapter cutoff the way facts are, because there
    // is nothing to gate them by — an image carries no `<ref>` citation, so there is no measured
    // basis to say when it stops being a spoiler (§9.4's whole principle). What keeps this safe is
    // that a portrait is attached to a character the cutoff already admitted, and a map is a map.
    // Neither states anything the seed has not already decided the listener has earned.

    /** A resolved wiki image: where it lives and how big it is. */
    data class ImageRef(val fileTitle: String, val url: String, val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height.toLong()
    }

    /**
     * Lead images for several articles in **one** request per batch.
     *
     * `prop=pageimages&piprop=original` is the right tool rather than `prop=images`: the latter
     * returns every file the page touches — nav icons, rating badges, the wiki's own wordmark — in
     * document order, and picking the infobox portrait out of that is guesswork. PageImages already
     * knows which one is the page's image, which is the same judgement a reader makes.
     *
     * Chunked at 20 titles because the API caps `titles=` at 50 for anonymous callers and a long
     * URL is the one failure mode here that returns a confusing error rather than an empty result.
     */
    suspend fun leadImages(slug: String, titles: List<String>): Map<String, ImageRef> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, ImageRef>()
            for (chunk in titles.chunked(20)) {
                val json = get(
                    slug,
                    "action=query&prop=pageimages&piprop=original&pilicense=any" +
                        "&titles=${encode(chunk.joinToString("|"))}"
                ) ?: continue
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: continue
                for (key in pages.keys()) {
                    val page = pages.optJSONObject(key) ?: continue
                    val title = page.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val original = page.optJSONObject("original") ?: continue
                    val url = original.optString("source").takeIf { it.isNotBlank() } ?: continue
                    out[title] = ImageRef(
                        fileTitle = title,
                        url = url,
                        width = original.optInt("width", 0),
                        height = original.optInt("height", 0)
                    )
                }
            }
            out
        }

    /**
     * The wiki's map, if it has one worth showing.
     *
     * Three strategies, cheapest and most trustworthy first. A wiki that has an article *called*
     * "Map" has answered the question itself, exactly as [CATEGORY_CANDIDATES] trusts a curated
     * character category over any ranking this file could invent. Only when that fails does it
     * fall back to hunting the File namespace, where "map" in a filename is a hint rather than a
     * statement.
     */
    suspend fun findMapImage(slug: String): ImageRef? {
        leadImages(slug, MAP_ARTICLE_TITLES).values
            .let { bestMap(it.toList()) }
            ?.let { return it }

        bestMap(imageInfo(slug, searchFiles(slug, "map")))?.let { return it }

        for (category in MAP_CATEGORIES) {
            bestMap(imageInfo(slug, categoryFiles(slug, category)))?.let { return it }
        }
        AppLog.i(LogCat.NET, "fandom $slug: no map image found")
        return null
    }

    /** Resolves `File:` titles to direct URLs and pixel sizes. */
    private suspend fun imageInfo(slug: String, fileTitles: List<String>): List<ImageRef> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<ImageRef>()
            for (chunk in fileTitles.chunked(20)) {
                val json = get(
                    slug,
                    "action=query&prop=imageinfo&iiprop=url|size&titles=${encode(chunk.joinToString("|"))}"
                ) ?: continue
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: continue
                for (key in pages.keys()) {
                    val page = pages.optJSONObject(key) ?: continue
                    val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                    val url = info.optString("url").takeIf { it.isNotBlank() } ?: continue
                    out += ImageRef(
                        fileTitle = page.optString("title"),
                        url = url,
                        width = info.optInt("width", 0),
                        height = info.optInt("height", 0)
                    )
                }
            }
            out
        }

    private suspend fun searchFiles(slug: String, term: String): List<String> =
        withContext(Dispatchers.IO) {
            val json = get(
                slug,
                "action=query&list=search&srsearch=${encode(term)}&srnamespace=6&srlimit=25"
            ) ?: return@withContext emptyList()
            val results = json.optJSONObject("query")?.optJSONArray("search")
                ?: return@withContext emptyList()
            (0 until results.length()).mapNotNull {
                results.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() }
            }
        }

    private suspend fun categoryFiles(slug: String, category: String): List<String> =
        withContext(Dispatchers.IO) {
            val json = get(
                slug,
                "action=query&list=categorymembers&cmtitle=${encode("Category:$category")}" +
                    "&cmnamespace=6&cmlimit=50"
            ) ?: return@withContext emptyList()
            val members = json.optJSONObject("query")?.optJSONArray("categorymembers")
                ?: return@withContext emptyList()
            (0 until members.length()).mapNotNull {
                members.optJSONObject(it)?.optString("title")?.takeIf { t -> t.isNotBlank() }
            }
        }

    /**
     * Picks the most map-like image from a set of candidates.
     *
     * Size is the filter, not the ranking: a wiki's File namespace is full of 80px faction crests
     * and chapter-header decorations whose names happen to contain "map", and none of them survive
     * a floor of 480×320. Among what does survive, a filename saying "map" outranks a bigger image
     * that does not — a 4000px character render is the single most likely wrong answer here, and
     * it beats every real map on area alone.
     *
     * SVG is excluded because Coil cannot decode it without an extra decoder, and a map that
     * renders as a blank pane is worse than no map.
     */
    private fun bestMap(candidates: List<ImageRef>): ImageRef? = candidates
        .filterNot { it.url.substringBefore('?').endsWith(".svg", ignoreCase = true) }
        .filter { it.width >= MIN_MAP_WIDTH && it.height >= MIN_MAP_HEIGHT }
        .sortedWith(
            compareByDescending<ImageRef> { it.fileTitle.contains("map", ignoreCase = true) }
                .thenByDescending { it.area }
        )
        .firstOrNull()

    /**
     * Downloads an image, refusing anything implausibly large.
     *
     * The cap is not paranoia about disk: these bytes are read fully into memory before being
     * handed to the pack store, and a wiki's original-resolution map can genuinely be tens of
     * megabytes. Refusing is the right failure — the board falls back to its flat [ArtTone] ground,
     * which is a design that already exists, rather than an OOM on someone's phone.
     */
    suspend fun downloadImage(url: String, maxBytes: Long = MAX_IMAGE_BYTES): ByteArray? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLog.w(LogCat.NET, "fandom image ${response.code}: $url")
                        return@withContext null
                    }
                    val body = response.body ?: return@withContext null
                    val declared = body.contentLength()
                    if (declared > maxBytes) {
                        AppLog.w(LogCat.NET, "fandom image too large ($declared bytes): $url")
                        return@withContext null
                    }
                    val bytes = body.bytes()
                    if (bytes.size > maxBytes) null else bytes
                }
            } catch (e: Exception) {
                AppLog.w(LogCat.NET, "fandom image download failed: ${e.message}")
                null
            }
        }

    private companion object {
        /** A curated category this size or larger is treated as authoritative on its own. */
        const val CURATED_ENOUGH = 3

        /** Articles whose own lead image is, on most wikis, the world map. */
        val MAP_ARTICLE_TITLES = listOf("Map", "World Map", "Maps", "Geography", "World")
        val MAP_CATEGORIES = listOf("Maps", "Map", "Locations")

        /** Below this a candidate is a crest or a header decoration, not a map. */
        const val MIN_MAP_WIDTH = 480
        const val MIN_MAP_HEIGHT = 320

        const val MAX_IMAGE_BYTES = 12L * 1024 * 1024

        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
