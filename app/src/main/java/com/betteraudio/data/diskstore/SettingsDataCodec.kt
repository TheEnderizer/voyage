package com.betteraudio.data.diskstore

import org.json.JSONArray
import org.json.JSONObject

/** org.json <-> [SettingsDocument] conversion, same best-effort/forward-compat rules as
 *  [BookDataCodec]. An unparseable individual setting entry is skipped rather than failing the
 *  whole document, matching how BackupManager treats each restored setting as independent. */
object SettingsDataCodec {

    private val TOP_KEYS = setOf("version", "writtenAt", "libraryFolderAtWrite", "settings")

    fun encode(doc: SettingsDocument): JSONObject = JSONObject().apply {
        put("version", doc.version)
        put("writtenAt", doc.writtenAt)
        put("libraryFolderAtWrite", doc.libraryFolderAtWrite)
        put("settings", JSONArray().apply {
            doc.settings.forEach { s ->
                put(JSONObject().apply { put("name", s.name); put("type", s.type); put("value", s.value) })
            }
        })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: SettingsDocument): String = encode(doc).toString()

    fun decodeOrNull(text: String): SettingsDocument? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): SettingsDocument {
        val settings = root.optJSONArray("settings")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank() || !o.has("value")) return@mapNotNull null
                SettingsDocument.SettingValue(name = name, type = o.optString("type", "string"), value = o.optString("value"))
            }
        } ?: emptyList()

        return SettingsDocument(
            version = root.optInt("version", SettingsDocument.CURRENT_VERSION),
            writtenAt = root.optLong("writtenAt"),
            libraryFolderAtWrite = root.optString("libraryFolderAtWrite"),
            settings = settings,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }
}
