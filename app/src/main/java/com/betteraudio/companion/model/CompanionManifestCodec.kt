package com.betteraudio.companion.model

import com.betteraudio.data.diskstore.captureUnknown
import com.betteraudio.data.diskstore.putUnknown
import org.json.JSONArray
import org.json.JSONObject

/** org.json <-> [CompanionManifestDoc] conversion — same house convention as
 *  [CompanionPackCodec]/[PackScrapsCodec]. Member encode/decode is intentionally duplicated from
 *  [CompanionPackCodec] rather than shared (that codec's helpers are private, and a manifest
 *  member is conceptually a standalone fingerprint record, not "borrowed" from the pack). */
object CompanionManifestCodec {

    private val TOP_KEYS = setOf("schemaVersion", "packId", "scope", "payload", "title", "exportedAtMs", "members", "mediaFiles", "audioFiles")
    private val MEMBER_KEYS = setOf("bookRef", "title", "author", "seriesOrder", "durationMs", "fileCount", "fileKeys")

    fun encode(doc: CompanionManifestDoc): JSONObject = JSONObject().apply {
        put("schemaVersion", doc.schemaVersion)
        put("packId", doc.packId)
        put("scope", doc.scope.name)
        put("payload", doc.payload.name)
        put("title", doc.title)
        put("exportedAtMs", doc.exportedAtMs)
        put("members", JSONArray().apply { doc.members.forEach { put(encodeMember(it)) } })
        put("mediaFiles", JSONArray(doc.mediaFiles))
        put("audioFiles", JSONArray().apply {
            doc.audioFiles.forEach { af ->
                put(JSONObject().apply {
                    put("fileName", af.fileName); put("sizeBytes", af.sizeBytes); put("fileKey", af.fileKey)
                })
            }
        })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: CompanionManifestDoc, indent: Int = 2): String = encode(doc).toString(indent)

    fun decodeOrNull(text: String): CompanionManifestDoc? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): CompanionManifestDoc {
        val members = root.optJSONArray("members")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeMember) }
        } ?: emptyList()
        val mediaFiles = root.optJSONArray("mediaFiles")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val audioFiles = root.optJSONArray("audioFiles")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    ManifestAudioFile(
                        fileName = o.optString("fileName"),
                        sizeBytes = o.optLong("sizeBytes"),
                        fileKey = if (o.has("fileKey") && !o.isNull("fileKey")) o.optString("fileKey") else null
                    )
                }
            }
        } ?: emptyList()
        return CompanionManifestDoc(
            schemaVersion = root.optInt("schemaVersion", CompanionManifestDoc.CURRENT_SCHEMA_VERSION),
            packId = root.optString("packId"),
            scope = runCatching { PackScope.valueOf(root.optString("scope")) }.getOrDefault(PackScope.BOOK),
            payload = runCatching { PackPayload.valueOf(root.optString("payload")) }.getOrDefault(PackPayload.DATA_ONLY),
            title = root.optString("title"),
            exportedAtMs = root.optLong("exportedAtMs", System.currentTimeMillis()),
            members = members,
            mediaFiles = mediaFiles,
            audioFiles = audioFiles,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }

    private fun encodeMember(m: PackMember): JSONObject = JSONObject().apply {
        put("bookRef", m.bookRef)
        put("title", m.title)
        put("author", m.author)
        put("seriesOrder", m.seriesOrder)
        put("durationMs", m.durationMs)
        put("fileCount", m.fileCount)
        put("fileKeys", JSONArray(m.fileKeys))
    }

    private fun decodeMember(o: JSONObject): PackMember = PackMember(
        bookRef = o.optString("bookRef"),
        title = o.optString("title"),
        author = o.optString("author"),
        seriesOrder = if (o.has("seriesOrder") && !o.isNull("seriesOrder")) o.optDouble("seriesOrder").toFloat() else null,
        durationMs = o.optLong("durationMs"),
        fileCount = o.optInt("fileCount"),
        fileKeys = o.optJSONArray("fileKeys")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
        unknown = o.captureUnknown(MEMBER_KEYS)
    )
}
