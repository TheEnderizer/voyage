package com.betteraudio.data.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The user's manual accent picks, one per cover, keyed by the cover's absolute path.
 *
 * Voyage normally chooses the theme's accent itself, out of the ~16 colours Palette quantizes a
 * cover into. That choice is a fallback chain, so a cover whose art sits near one of its branch
 * points can resolve differently between devices — or between two decodes of what is nominally the
 * same file. An entry here pins one of those sampled colours instead, and the automatic pick is
 * skipped entirely for that cover.
 *
 * Keyed by cover rather than by book on purpose: the app themes from whichever cover is on screen,
 * which may belong to a book, to a series, or to the media session — all the theme layer ever has
 * is the path. Absolute paths mean a restore onto a library at a different root starts fresh,
 * which is the right failure: those are different files.
 *
 * Stored as one JSON string in a single preference (`cover_accents`), which rides the normal
 * settings mirror out to `settings.json` and so survives a reinstall.
 */
object CoverAccentCodec {
    /** Roughly a large library's worth of pinned covers. Past this the oldest entry is dropped —
     *  a stale pin is worth less than an unbounded preference value. */
    private const val MAX_ENTRIES = 240

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun decode(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            buildMap {
                json.parseToJsonElement(raw).jsonObject.forEach { (path, value) ->
                    value.jsonPrimitive.content.toArgbOrNull()?.let { put(path, it) }
                }
            }
        }.getOrElse { emptyMap() }
    }

    fun encode(map: Map<String, Int>): String =
        JsonObject(map.mapValues { (_, argb) -> JsonPrimitive(argb.toHexArgb()) }).toString()

    /** [raw] with [coverPath] pinned to [argb], or unpinned when [argb] is null. Re-inserting at
     *  the end keeps the map in "least recently touched first" order, which is what the cap trims
     *  from. */
    fun with(raw: String, coverPath: String, argb: Int?): String {
        val next = LinkedHashMap(decode(raw))
        next.remove(coverPath)
        if (argb != null) next[coverPath] = argb
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        return if (next.isEmpty()) "" else encode(next)
    }

    private fun Int.toHexArgb(): String = "#%08X".format(this)

    /** Hand-rolled rather than android.graphics.Color.parseColor so this stays a plain JVM object
     *  the settings tests can exercise without a mocked framework. */
    private fun String.toArgbOrNull(): Int? {
        val hex = removePrefix("#")
        if (hex.length != 8) return null
        return hex.toLongOrNull(16)?.toInt()
    }
}
