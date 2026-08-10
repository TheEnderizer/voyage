package com.betteraudio.data.diskstore

import org.json.JSONArray
import org.json.JSONObject

/**
 * org.json <-> [BookDocument] conversion. Best-effort by design (matches
 * [com.betteraudio.data.sync.MappingFileIO]'s recipe): [decode] never throws, returning null on
 * anything unparseable so the caller treats it exactly like "no disk data". [encode] never omits
 * a field the entity actually has — optional fields are only skipped when genuinely null/blank,
 * matching BackupManager's existing `?.let { put(...) }` idiom so the two exporters stay visibly
 * consistent.
 */
object BookDataCodec {

    private val TOP_KEYS = setOf(
        "version", "writtenAt", "app", "folderPath", "relPath", "kind",
        "title", "author", "titleOverride", "authorOverride", "narrator", "genre", "year",
        "album", "description", "synopsis", "status", "isIgnored", "skipSilenceEnabled",
        "addedDateMs", "totalDurationMs", "fileCount",
        "cover", "series", "ebook", "files", "progress", "bookmarks", "sessions", "skipEvents"
    )
    private val COVER_KEYS = setOf("relPath", "source", "updatedAt")
    private val SERIES_REF_KEYS = setOf("name", "order")
    private val EBOOK_KEYS = setOf("relPath", "spineCount", "chapterMap")
    private val PROGRESS_KEYS = setOf(
        "positionMs", "lastPlayedMs", "currentFile", "playbackSpeed", "boostDb", "eqBandsJson",
        "isCompleted", "completedDateMs", "lastPausedAt", "textSpineIndex", "textFraction",
        "textOverallFraction", "lastMode"
    )

    fun encode(doc: BookDocument): JSONObject = JSONObject().apply {
        put("version", doc.version)
        put("writtenAt", doc.writtenAt)
        put("app", doc.app)
        put("folderPath", doc.folderPath)
        put("relPath", doc.relPath)
        put("kind", doc.kind)
        put("title", doc.title)
        put("author", doc.author)
        doc.titleOverride?.let { put("titleOverride", it) }
        doc.authorOverride?.let { put("authorOverride", it) }
        doc.narrator?.let { put("narrator", it) }
        doc.genre?.let { put("genre", it) }
        doc.year?.let { put("year", it) }
        doc.album?.let { put("album", it) }
        doc.description?.let { put("description", it) }
        doc.synopsis?.let { put("synopsis", it) }
        put("status", doc.status)
        put("isIgnored", doc.isIgnored)
        put("skipSilenceEnabled", doc.skipSilenceEnabled)
        put("addedDateMs", doc.addedDateMs)
        put("totalDurationMs", doc.totalDurationMs)
        put("fileCount", doc.fileCount)

        doc.cover?.let { c ->
            put("cover", JSONObject().apply {
                put("relPath", c.relPath); put("source", c.source); put("updatedAt", c.updatedAt)
                putUnknown(c.unknown)
            })
        }
        doc.series?.let { s ->
            put("series", JSONObject().apply {
                put("name", s.name); s.order?.let { put("order", it) }
                putUnknown(s.unknown)
            })
        }
        doc.ebook?.let { e ->
            put("ebook", JSONObject().apply {
                put("relPath", e.relPath); put("spineCount", e.spineCount)
                e.chapterMap?.let { cm -> put("chapterMap", JSONArray().apply { cm.forEach { put(it) } }) }
                putUnknown(e.unknown)
            })
        }
        put("files", JSONArray().apply {
            doc.files.forEach { f ->
                put(JSONObject().apply {
                    put("fileName", f.fileName); put("durationMs", f.durationMs)
                    put("trackNumber", f.trackNumber)
                    f.title?.let { put("title", it) }
                    f.chapterTitle?.let { put("chapterTitle", it) }
                    f.damageRanges?.let { put("damageRanges", it) }
                })
            }
        })
        doc.progress?.let { p ->
            put("progress", JSONObject().apply {
                put("positionMs", p.positionMs); put("lastPlayedMs", p.lastPlayedMs)
                p.currentFile?.let { cf ->
                    put("currentFile", JSONObject().apply {
                        put("fileName", cf.fileName); put("durationMs", cf.durationMs)
                    })
                }
                put("playbackSpeed", p.playbackSpeed); put("boostDb", p.boostDb)
                p.eqBandsJson?.let { put("eqBandsJson", it) }
                put("isCompleted", p.isCompleted)
                p.completedDateMs?.let { put("completedDateMs", it) }
                put("lastPausedAt", p.lastPausedAt)
                p.textSpineIndex?.let { put("textSpineIndex", it) }
                p.textFraction?.let { put("textFraction", it) }
                put("textOverallFraction", p.textOverallFraction)
                put("lastMode", p.lastMode)
                putUnknown(p.unknown)
            })
        }
        put("bookmarks", JSONArray().apply {
            doc.bookmarks.forEach { b ->
                put(JSONObject().apply {
                    put("fileName", b.fileName); put("positionInFileMs", b.positionInFileMs)
                    put("absolutePositionMs", b.absolutePositionMs)
                    put("comment", b.comment); put("createdAt", b.createdAt)
                })
            }
        })
        put("sessions", JSONArray().apply {
            doc.sessions.forEach { s ->
                put(JSONObject().apply {
                    put("startMs", s.startMs); put("endMs", s.endMs)
                    put("startChapterIndex", s.startChapterIndex); put("startChapterName", s.startChapterName)
                    put("endChapterIndex", s.endChapterIndex); put("endChapterName", s.endChapterName)
                    put("startPositionInChapterMs", s.startPositionInChapterMs)
                    put("endPositionInChapterMs", s.endPositionInChapterMs)
                    put("endBookPositionMs", s.endBookPositionMs); put("listenedMs", s.listenedMs)
                })
            }
        })
        put("skipEvents", JSONArray().apply {
            doc.skipEvents.forEach { sk ->
                put(JSONObject().apply {
                    put("atMs", sk.atMs); put("kind", sk.kind); put("source", sk.source)
                    put("fromPositionMs", sk.fromPositionMs); put("toPositionMs", sk.toPositionMs)
                    put("chapterIndex", sk.chapterIndex); put("chapterName", sk.chapterName)
                    sk.fromSpineIndex?.let { put("fromSpineIndex", it) }
                    sk.fromFraction?.let { put("fromFraction", it) }
                    sk.toSpineIndex?.let { put("toSpineIndex", it) }
                    sk.toFraction?.let { put("toFraction", it) }
                    sk.toSpineTitle?.let { put("toSpineTitle", it) }
                })
            }
        })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: BookDocument): String = encode(doc).toString()

    /** Parses [text] into a [BookDocument], or null if it's missing/unreadable/corrupt. Every
     *  field falls back to a safe default so an old file with fields dropped in a later schema
     *  version, or a new file with fields a running older build has never heard of, both parse. */
    fun decodeOrNull(text: String): BookDocument? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): BookDocument {
        val cover = root.optJSONObject("cover")?.let { c ->
            BookDocument.CoverInfo(
                relPath = c.optString("relPath"),
                source = c.optString("source", "embedded"),
                updatedAt = c.optLong("updatedAt"),
                unknown = c.captureUnknown(COVER_KEYS)
            )
        }
        val series = root.optJSONObject("series")?.let { s ->
            val name = s.optString("name")
            if (name.isBlank()) null else BookDocument.SeriesRef(
                name = name,
                order = if (s.has("order")) s.optDouble("order").toFloat() else null,
                unknown = s.captureUnknown(SERIES_REF_KEYS)
            )
        }
        val ebook = root.optJSONObject("ebook")?.let { e ->
            val relPath = e.optString("relPath")
            if (relPath.isBlank()) null else BookDocument.EbookInfo(
                relPath = relPath,
                spineCount = e.optInt("spineCount"),
                chapterMap = e.optJSONArray("chapterMap")?.let { arr ->
                    (0 until arr.length()).map { arr.optInt(it) }
                },
                unknown = e.captureUnknown(EBOOK_KEYS)
            )
        }
        val files = root.optJSONArray("files")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val f = arr.optJSONObject(i) ?: return@mapNotNull null
                val fileName = f.optString("fileName")
                if (fileName.isBlank()) return@mapNotNull null
                BookDocument.FileEntry(
                    fileName = fileName,
                    durationMs = f.optLong("durationMs"),
                    trackNumber = f.optInt("trackNumber"),
                    title = f.optString("title").takeIf { it.isNotBlank() },
                    chapterTitle = f.optString("chapterTitle").takeIf { it.isNotBlank() },
                    damageRanges = if (f.has("damageRanges")) f.optString("damageRanges") else null
                )
            }
        } ?: emptyList()
        val progress = root.optJSONObject("progress")?.let { p ->
            BookDocument.ProgressEntry(
                positionMs = p.optLong("positionMs"),
                lastPlayedMs = p.optLong("lastPlayedMs"),
                currentFile = p.optJSONObject("currentFile")?.let { cf ->
                    val fn = cf.optString("fileName")
                    if (fn.isBlank()) null else BookDocument.CurrentFileRef(fn, cf.optLong("durationMs"))
                },
                playbackSpeed = p.optDouble("playbackSpeed", 1.0).toFloat(),
                boostDb = p.optInt("boostDb"),
                eqBandsJson = p.optString("eqBandsJson").takeIf { it.isNotBlank() },
                isCompleted = p.optBoolean("isCompleted"),
                completedDateMs = if (p.has("completedDateMs")) p.optLong("completedDateMs") else null,
                lastPausedAt = p.optLong("lastPausedAt"),
                textSpineIndex = if (p.has("textSpineIndex")) p.optInt("textSpineIndex") else null,
                textFraction = if (p.has("textFraction")) p.optDouble("textFraction").toFloat() else null,
                textOverallFraction = p.optDouble("textOverallFraction", 0.0).toFloat(),
                lastMode = p.optString("lastMode", "AUDIO"),
                unknown = p.captureUnknown(PROGRESS_KEYS)
            )
        }
        val bookmarks = root.optJSONArray("bookmarks")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val b = arr.optJSONObject(i) ?: return@mapNotNull null
                val fn = b.optString("fileName")
                if (fn.isBlank()) return@mapNotNull null
                BookDocument.BookmarkEntry(
                    fileName = fn,
                    positionInFileMs = b.optLong("positionInFileMs"),
                    absolutePositionMs = b.optLong("absolutePositionMs"),
                    comment = b.optString("comment"),
                    createdAt = b.optLong("createdAt")
                )
            }
        } ?: emptyList()
        val sessions = root.optJSONArray("sessions")?.let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.optJSONObject(i) ?: JSONObject()
                BookDocument.SessionEntry(
                    startMs = s.optLong("startMs"), endMs = s.optLong("endMs"),
                    startChapterIndex = s.optInt("startChapterIndex", -1),
                    startChapterName = s.optString("startChapterName"),
                    endChapterIndex = s.optInt("endChapterIndex", -1),
                    endChapterName = s.optString("endChapterName"),
                    startPositionInChapterMs = s.optLong("startPositionInChapterMs"),
                    endPositionInChapterMs = s.optLong("endPositionInChapterMs"),
                    endBookPositionMs = s.optLong("endBookPositionMs"),
                    listenedMs = s.optLong("listenedMs")
                )
            }
        } ?: emptyList()
        val skipEvents = root.optJSONArray("skipEvents")?.let { arr ->
            (0 until arr.length()).map { i ->
                val sk = arr.optJSONObject(i) ?: JSONObject()
                BookDocument.SkipEventEntry(
                    atMs = sk.optLong("atMs"), kind = sk.optString("kind", "AUDIO"),
                    source = sk.optString("source", "jump"),
                    fromPositionMs = sk.optLong("fromPositionMs"), toPositionMs = sk.optLong("toPositionMs"),
                    chapterIndex = sk.optInt("chapterIndex", -1), chapterName = sk.optString("chapterName"),
                    fromSpineIndex = if (sk.has("fromSpineIndex")) sk.optInt("fromSpineIndex") else null,
                    fromFraction = if (sk.has("fromFraction")) sk.optDouble("fromFraction").toFloat() else null,
                    toSpineIndex = if (sk.has("toSpineIndex")) sk.optInt("toSpineIndex") else null,
                    toFraction = if (sk.has("toFraction")) sk.optDouble("toFraction").toFloat() else null,
                    toSpineTitle = sk.optString("toSpineTitle").takeIf { it.isNotBlank() }
                )
            }
        } ?: emptyList()

        return BookDocument(
            version = root.optInt("version", BookDocument.CURRENT_VERSION),
            writtenAt = root.optLong("writtenAt"),
            app = root.optString("app"),
            folderPath = root.optString("folderPath"),
            relPath = root.optString("relPath"),
            kind = root.optString("kind", "AUDIO"),
            title = root.optString("title"),
            author = root.optString("author"),
            titleOverride = root.optString("titleOverride").takeIf { it.isNotBlank() },
            authorOverride = root.optString("authorOverride").takeIf { it.isNotBlank() },
            narrator = root.optString("narrator").takeIf { it.isNotBlank() },
            genre = root.optString("genre").takeIf { it.isNotBlank() },
            year = if (root.has("year")) root.optInt("year") else null,
            album = root.optString("album").takeIf { it.isNotBlank() },
            description = root.optString("description").takeIf { it.isNotBlank() },
            synopsis = root.optString("synopsis").takeIf { it.isNotBlank() },
            status = root.optString("status", "NOT_STARTED"),
            isIgnored = root.optBoolean("isIgnored"),
            skipSilenceEnabled = root.optBoolean("skipSilenceEnabled"),
            addedDateMs = root.optLong("addedDateMs"),
            totalDurationMs = root.optLong("totalDurationMs"),
            fileCount = root.optInt("fileCount"),
            cover = cover,
            series = series,
            ebook = ebook,
            files = files,
            progress = progress,
            bookmarks = bookmarks,
            sessions = sessions,
            skipEvents = skipEvents,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }
}
