package com.betteraudio.data.sync

import com.betteraudio.data.db.entities.SyncAnchor
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.ensureNoMedia
import com.betteraudio.data.diskstore.writeTextAtomic
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Portable on-disk mirror of a book's paragraph-resolution sync data (the audio-chapter-to-epub-
 * chapter map + Tier-2 forced-alignment anchors), written into the book's own `data/` folder so
 * it survives a folder copy/backup/restructure — or an app uninstall/reinstall — without
 * re-running on-device speech recognition. Anchors are tied to whatever epub is currently
 * connected (same as the DB copy — [com.betteraudio.data.repository.AudiobookRepository.setEbook]
 * clears both when the epub changes), so this file is only meaningful alongside a matching epub
 * in the same folder.
 *
 * Primary location moved from `<bookFolder>/mapping.json` into `data/` as part of the storage
 * redesign — [legacyMappingFile] is read as a fallback so an existing on-disk file, or a mapping
 * a user manually drops into the book's folder, still works. [folderKey] is a
 * [com.betteraudio.data.db.entities.Book.folderPath] and may be a synthetic "<dir>::<stem>"
 * cluster key or a "<parent>::epub::<stem>" ebook-only key — see [BookDataPaths].
 */
object MappingFileIO {
    private const val VERSION = 1
    private const val LEGACY_FILE_NAME = "mapping.json"

    data class MappingFileData(val chapterMapJson: String?, val anchors: List<SyncAnchor>)

    fun mappingFile(folderKey: String): File =
        File(BookDataPaths.dataDir(folderKey), BookDataPaths.mappingFileName(folderKey))

    /** The pre-redesign location — only meaningful for a real, non-cluster folder (a cluster
     *  never had a location of its own to begin with). */
    fun legacyMappingFile(folderKey: String): File? =
        if (BookDataPaths.isCluster(folderKey)) null
        else File(BookDataPaths.containingDir(folderKey), LEGACY_FILE_NAME)

    fun exists(folderKey: String): Boolean =
        mappingFile(folderKey).isFile || legacyMappingFile(folderKey)?.isFile == true

    /** Best-effort write — failures (read-only storage, IO error) are swallowed since this file is
     *  a convenience mirror, not the source of truth (the DB is). */
    fun write(folderKey: String, chapterMapJson: String?, anchors: List<SyncAnchor>): Boolean {
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
        val target = mappingFile(folderKey)
        val ok = writeTextAtomic(target, root.toString())
        if (ok) ensureNoMedia(target.parentFile ?: return ok)
        return ok
    }

    /** Reads the `data/` copy first, falling back to the pre-redesign / user-drop-in location.
     *  Null if missing/unreadable/corrupt in both places. */
    fun read(folderKey: String): MappingFileData? =
        readFrom(mappingFile(folderKey)) ?: legacyMappingFile(folderKey)?.let(::readFrom)

    private fun readFrom(file: File): MappingFileData? {
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
        }.onFailure {
            // Distinct from "file doesn't exist" (the isFile check above already returns null for
            // that, silently) — this means a mapping.json IS present but couldn't be parsed, which
            // is the "why did my sync anchors disappear" case worth a trace for.
            AppLog.w(LogCat.SYNC, "mapping file exists but failed to parse: ${file.absolutePath}: ${it.message}")
        }.getOrNull()
    }
}
