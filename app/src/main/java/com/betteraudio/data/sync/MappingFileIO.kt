package com.betteraudio.data.sync

import com.betteraudio.data.db.entities.SyncAnchor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Portable on-disk mirror of a book's paragraph-resolution sync data (the audio-chapter-to-epub-
 * chapter map + Tier-2 forced-alignment anchors), written next to the audio as "mapping.json" so
 * it survives a folder copy/backup/restructure to another device or install without re-running
 * on-device speech recognition. Anchors are tied to whatever epub is currently connected (same as
 * the DB copy — [com.betteraudio.data.repository.AudiobookRepository.setEbook] clears both when
 * the epub changes), so this file is only meaningful alongside a matching epub in the same folder.
 */
object MappingFileIO {
    private const val FILE_NAME = "mapping.json"
    private const val VERSION = 1

    data class MappingFileData(val chapterMapJson: String?, val anchors: List<SyncAnchor>)

    fun mappingFile(folder: File): File = File(folder, FILE_NAME)

    /** Best-effort write — failures (read-only storage, IO error) are swallowed since this file is
     *  a convenience mirror, not the source of truth (the DB is). */
    fun write(folder: File, chapterMapJson: String?, anchors: List<SyncAnchor>): Boolean =
        runCatching {
            val root = JSONObject().apply {
                put("version", VERSION)
                chapterMapJson?.let { put("chapterMap", JSONArray(it)) }
                put("anchors", JSONArray().apply {
                    anchors.forEach { a ->
                        put(JSONObject().apply {
                            put("audioMs", a.audioMs)
                            put("spineIndex", a.spineIndex)
                            put("paragraphIndex", a.paragraphIndex)
                            put("charOffset", a.charOffset)
                            put("confidence", a.confidence)
                        })
                    }
                })
            }
            mappingFile(folder).writeText(root.toString())
            true
        }.getOrDefault(false)

    /** Parse "mapping.json" from [folder], or null if missing/unreadable/corrupt. */
    fun read(folder: File): MappingFileData? {
        val file = mappingFile(folder)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val chapterMapJson = root.optJSONArray("chapterMap")?.toString()
            val anchorsArr = root.optJSONArray("anchors") ?: JSONArray()
            val anchors = (0 until anchorsArr.length()).map { i ->
                val o = anchorsArr.getJSONObject(i)
                SyncAnchor(
                    bookId = 0L,
                    audioMs = o.getLong("audioMs"),
                    spineIndex = o.getInt("spineIndex"),
                    paragraphIndex = o.getInt("paragraphIndex"),
                    charOffset = o.getInt("charOffset"),
                    confidence = o.optDouble("confidence", 1.0).toFloat(),
                    createdAtMs = System.currentTimeMillis()
                )
            }
            MappingFileData(chapterMapJson, anchors)
        }.getOrNull()
    }
}
