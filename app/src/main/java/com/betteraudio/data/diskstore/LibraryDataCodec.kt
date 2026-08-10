package com.betteraudio.data.diskstore

import org.json.JSONArray
import org.json.JSONObject

/** org.json <-> [LibraryDocument] conversion, same best-effort/forward-compat rules as
 *  [BookDataCodec]. */
object LibraryDataCodec {

    private val TOP_KEYS = setOf("version", "writtenAt", "libraryRoot", "presets", "authors", "series")

    fun encode(doc: LibraryDocument): JSONObject = JSONObject().apply {
        put("version", doc.version)
        put("writtenAt", doc.writtenAt)
        put("libraryRoot", doc.libraryRoot)
        put("presets", JSONArray().apply {
            doc.presets.forEach { p ->
                put(JSONObject().apply {
                    put("name", p.name); put("type", p.type)
                    put("speedMult", p.speedMult); put("boostDb", p.boostDb)
                    p.eqBandsJson?.let { put("eqBandsJson", it) }
                    put("isDefault", p.isDefault)
                })
            }
        })
        put("authors", JSONArray().apply {
            doc.authors.forEach { a ->
                put(JSONObject().apply {
                    put("name", a.name)
                    a.cover?.let { put("cover", it) }
                })
            }
        })
        put("series", JSONArray().apply {
            doc.series.forEach { s ->
                put(JSONObject().apply {
                    put("name", s.name)
                    s.author?.let { put("author", it) }
                    s.narrator?.let { put("narrator", it) }
                    s.description?.let { put("description", it) }
                    s.playbackSpeed?.let { put("playbackSpeed", it) }
                    s.boostDb?.let { put("boostDb", it) }
                    s.eqBandsJson?.let { put("eqBandsJson", it) }
                    s.skipSilenceEnabled?.let { put("skipSilenceEnabled", it) }
                    s.cover?.let { put("cover", it) }
                    put("createdAtMs", s.createdAtMs)
                    put("members", JSONArray().apply {
                        s.members.forEach { m ->
                            put(JSONObject().apply {
                                put("folderPath", m.folderPath); put("relPath", m.relPath)
                                put("title", m.title); put("author", m.author)
                                m.seriesOrder?.let { put("seriesOrder", it) }
                            })
                        }
                    })
                })
            }
        })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: LibraryDocument): String = encode(doc).toString()

    fun decodeOrNull(text: String): LibraryDocument? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): LibraryDocument {
        val presets = root.optJSONArray("presets")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val p = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = p.optString("name")
                if (name.isBlank()) return@mapNotNull null
                LibraryDocument.PresetEntry(
                    name = name,
                    type = p.optString("type", "BUNDLE"),
                    speedMult = p.optDouble("speedMult", 1.0).toFloat(),
                    boostDb = p.optInt("boostDb"),
                    eqBandsJson = p.optString("eqBandsJson").takeIf { it.isNotBlank() },
                    isDefault = p.optBoolean("isDefault")
                )
            }
        } ?: emptyList()

        val authors = root.optJSONArray("authors")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = a.optString("name")
                if (name.isBlank()) return@mapNotNull null
                LibraryDocument.AuthorEntry(name = name, cover = a.optString("cover").takeIf { it.isNotBlank() })
            }
        } ?: emptyList()

        val series = root.optJSONArray("series")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = s.optString("name")
                if (name.isBlank()) return@mapNotNull null
                val members = s.optJSONArray("members")?.let { marr ->
                    (0 until marr.length()).mapNotNull { j ->
                        val m = marr.optJSONObject(j) ?: return@mapNotNull null
                        LibraryDocument.MemberRef(
                            folderPath = m.optString("folderPath"),
                            relPath = m.optString("relPath"),
                            title = m.optString("title"),
                            author = m.optString("author"),
                            seriesOrder = if (m.has("seriesOrder")) m.optDouble("seriesOrder").toFloat() else null
                        )
                    }
                } ?: emptyList()
                LibraryDocument.SeriesEntry(
                    name = name,
                    author = s.optString("author").takeIf { it.isNotBlank() },
                    narrator = s.optString("narrator").takeIf { it.isNotBlank() },
                    description = s.optString("description").takeIf { it.isNotBlank() },
                    playbackSpeed = if (s.has("playbackSpeed")) s.optDouble("playbackSpeed").toFloat() else null,
                    boostDb = if (s.has("boostDb")) s.optInt("boostDb") else null,
                    eqBandsJson = s.optString("eqBandsJson").takeIf { it.isNotBlank() },
                    skipSilenceEnabled = if (s.has("skipSilenceEnabled")) s.optBoolean("skipSilenceEnabled") else null,
                    cover = s.optString("cover").takeIf { it.isNotBlank() },
                    createdAtMs = s.optLong("createdAtMs"),
                    members = members
                )
            }
        } ?: emptyList()

        return LibraryDocument(
            version = root.optInt("version", LibraryDocument.CURRENT_VERSION),
            writtenAt = root.optLong("writtenAt"),
            libraryRoot = root.optString("libraryRoot"),
            presets = presets,
            authors = authors,
            series = series,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }
}
