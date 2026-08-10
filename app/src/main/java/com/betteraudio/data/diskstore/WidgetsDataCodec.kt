package com.betteraudio.data.diskstore

import org.json.JSONArray
import org.json.JSONObject

/** org.json <-> [WidgetsDocument] conversion, same best-effort/forward-compat rules as
 *  [BookDataCodec]/[LibraryDataCodec]. */
object WidgetsDataCodec {

    private val TOP_KEYS = setOf("version", "writtenAt", "designs")
    private val DESIGN_KEYS = setOf("name", "aspectRatio", "documentJson", "createdAt", "updatedAt")

    fun encode(doc: WidgetsDocument): JSONObject = JSONObject().apply {
        put("version", doc.version)
        put("writtenAt", doc.writtenAt)
        put("designs", JSONArray().apply {
            doc.designs.forEach { d ->
                put(JSONObject().apply {
                    put("name", d.name)
                    put("aspectRatio", d.aspectRatio)
                    put("documentJson", d.documentJson)
                    put("createdAt", d.createdAt)
                    put("updatedAt", d.updatedAt)
                    putUnknown(d.unknown)
                })
            }
        })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: WidgetsDocument): String = encode(doc).toString()

    fun decodeOrNull(text: String): WidgetsDocument? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): WidgetsDocument {
        val designs = root.optJSONArray("designs")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = d.optString("name")
                if (name.isBlank()) return@mapNotNull null
                WidgetsDocument.DesignEntry(
                    name = name,
                    aspectRatio = d.optDouble("aspectRatio", 2.0).toFloat(),
                    documentJson = d.optString("documentJson", "{}"),
                    createdAt = d.optLong("createdAt"),
                    updatedAt = d.optLong("updatedAt"),
                    unknown = d.captureUnknown(DESIGN_KEYS)
                )
            }
        } ?: emptyList()

        return WidgetsDocument(
            version = root.optInt("version", WidgetsDocument.CURRENT_VERSION),
            writtenAt = root.optLong("writtenAt"),
            designs = designs,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }
}
