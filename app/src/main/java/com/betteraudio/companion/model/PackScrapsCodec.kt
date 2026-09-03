package com.betteraudio.companion.model

import com.betteraudio.data.diskstore.captureUnknown
import com.betteraudio.data.diskstore.putUnknown
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** org.json <-> [PackScrapsDoc] conversion — see [CompanionPackCodec]'s kdoc for the house
 *  convention this follows (hand-rolled `org.json`, `JsonUnknownFields` per nested object, ids
 *  generated on decode if missing). */
object PackScrapsCodec {

    private val TOP_KEYS = setOf("schemaVersion", "scraps")
    private val SCRAP_KEYS = setOf("scrapId", "anchor", "note", "createdAtMs", "converted")
    private val ANCHOR_KEYS = setOf("bookRef", "fileKey", "offsetMs", "globalMs", "chapter", "chapterOffsetMs", "quote", "ratio")

    fun encode(doc: PackScrapsDoc): JSONObject = JSONObject().apply {
        put("schemaVersion", doc.schemaVersion)
        put("scraps", JSONArray().apply { doc.scraps.forEach { put(encodeScrap(it)) } })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: PackScrapsDoc, indent: Int = 2): String = encode(doc).toString(indent)

    fun decodeOrNull(text: String): PackScrapsDoc? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): PackScrapsDoc {
        val scraps = root.optJSONArray("scraps")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeScrap) }
        } ?: emptyList()
        return PackScrapsDoc(
            schemaVersion = root.optInt("schemaVersion", PackScrapsDoc.CURRENT_SCHEMA_VERSION),
            scraps = scraps,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }

    private fun encodeAnchor(a: FactAnchor): JSONObject = JSONObject().apply {
        put("bookRef", a.bookRef)
        put("fileKey", a.fileKey)
        put("offsetMs", a.offsetMs)
        put("globalMs", a.globalMs)
        put("chapter", a.chapter)
        put("chapterOffsetMs", a.chapterOffsetMs)
        put("quote", a.quote)
        put("ratio", a.ratio)
        putUnknown(a.unknown)
    }

    private fun decodeAnchor(o: JSONObject): FactAnchor = FactAnchor(
        bookRef = o.optString("bookRef"),
        fileKey = o.optStringOrNull("fileKey"),
        offsetMs = o.optLongOrNull("offsetMs"),
        globalMs = o.optLongOrNull("globalMs"),
        chapter = o.optIntOrNull("chapter"),
        chapterOffsetMs = o.optLongOrNull("chapterOffsetMs"),
        quote = o.optStringOrNull("quote"),
        ratio = o.optFloatOrNull("ratio"),
        unknown = o.captureUnknown(ANCHOR_KEYS)
    )

    private fun encodeScrap(s: PackScrap): JSONObject = JSONObject().apply {
        put("scrapId", s.scrapId)
        put("anchor", encodeAnchor(s.anchor))
        put("note", s.note)
        put("createdAtMs", s.createdAtMs)
        put("converted", s.converted)
        putUnknown(s.unknown)
    }

    private fun decodeScrap(o: JSONObject): PackScrap? {
        val anchorObj = o.optJSONObject("anchor") ?: return null
        return PackScrap(
            scrapId = o.optString("scrapId").ifBlank { UUID.randomUUID().toString() },
            anchor = decodeAnchor(anchorObj),
            note = o.optString("note"),
            createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
            converted = o.optBoolean("converted", false),
            unknown = o.captureUnknown(SCRAP_KEYS)
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
}
