package com.betteraudio.data.diskstore

/**
 * In-memory shape of a book's `data/book.json` (or `data/<slug>.json` for one member of an AUTO
 * cluster, `data/epub.<slug>.json` for a standalone-ebook row) — the durable, on-disk mirror of
 * everything precious about one book. No Android types, no Room types: [BookDataCodec] is the
 * only thing that knows how to turn this into/from JSON, and [com.betteraudio.data.diskstore]'s
 * higher layers (BookDataStore, RestoreOps) are the only things that translate it to/from actual
 * entities. See the storage-redesign plan for the full field-by-field rationale.
 *
 * Row ids are never carried here — a fresh install has no stable ids, so every cross-reference
 * (progress' current file, a bookmark's file) is by [FileEntry.fileName] instead, re-keyed back
 * to a real id at apply time (see BackupMatcher.matchFile, reused by RestoreOps).
 */
data class BookDocument(
    val version: Int = CURRENT_VERSION,
    val writtenAt: Long,
    val app: String,
    /** The exact folderKey this doc was written for — read back and compared by BookDataStore so
     *  a slug collision degrades to "no data" rather than "wrong book's data". */
    val folderPath: String,
    val relPath: String,
    /** "AUDIO" | "EPUB" — an EPUB-only row (Book.fileCount == 0, no folder of its own). */
    val kind: String = "AUDIO",

    val title: String,
    val author: String,
    val titleOverride: String? = null,
    val authorOverride: String? = null,
    val narrator: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val album: String? = null,
    val description: String? = null,
    val synopsis: String? = null,
    /** Book.status.name — kept as a raw string so this file never depends on the Room entity. */
    val status: String,
    val isIgnored: Boolean = false,
    val skipSilenceEnabled: Boolean = false,
    val addedDateMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val fileCount: Int = 0,

    val cover: CoverInfo? = null,
    val series: SeriesRef? = null,
    val ebook: EbookInfo? = null,
    val files: List<FileEntry> = emptyList(),
    val progress: ProgressEntry? = null,
    val bookmarks: List<BookmarkEntry> = emptyList(),
    val sessions: List<SessionEntry> = emptyList(),
    val skipEvents: List<SkipEventEntry> = emptyList(),

    /** Unrecognized top-level keys from a newer build, preserved verbatim across a decode/encode
     *  round-trip by an older one. See JsonUnknownFields. */
    val unknown: Map<String, Any?> = emptyMap()
) {
    data class CoverInfo(
        /** Path relative to the book's containing directory, e.g. "data/cover.jpg". */
        val relPath: String,
        /** "user" (gallery pick / online search) | "external" (a cover.png the user dropped in
         *  the folder) | "embedded" (extracted from the audio's own tags). */
        val source: String,
        val updatedAt: Long,
        val unknown: Map<String, Any?> = emptyMap()
    )

    data class SeriesRef(
        val name: String,
        val order: Float?,
        val unknown: Map<String, Any?> = emptyMap()
    )

    data class EbookInfo(
        /** Path relative to the book's containing directory. */
        val relPath: String,
        val spineCount: Int,
        /** Book.chapterMapJson, decoded to a plain int array — null = not yet computed. */
        val chapterMap: List<Int>?,
        val unknown: Map<String, Any?> = emptyMap()
    )

    data class FileEntry(
        val fileName: String,
        val durationMs: Long,
        val trackNumber: Int,
        val title: String?,
        val chapterTitle: String?,
        /** AudioFile.damageRangesJson — null = never scanned, "" = scanned clean. */
        val damageRanges: String?
    )

    data class CurrentFileRef(val fileName: String, val durationMs: Long)

    data class ProgressEntry(
        val positionMs: Long,
        val lastPlayedMs: Long,
        val currentFile: CurrentFileRef?,
        val playbackSpeed: Float,
        val boostDb: Int,
        val eqBandsJson: String?,
        val isCompleted: Boolean,
        val completedDateMs: Long?,
        val lastPausedAt: Long,
        val textSpineIndex: Int?,
        val textFraction: Float?,
        val textOverallFraction: Float,
        val lastMode: String,
        val unknown: Map<String, Any?> = emptyMap()
    )

    data class BookmarkEntry(
        val fileName: String,
        val positionInFileMs: Long,
        val absolutePositionMs: Long,
        val comment: String,
        val createdAt: Long
    )

    data class SessionEntry(
        val startMs: Long,
        val endMs: Long,
        val startChapterIndex: Int,
        val startChapterName: String,
        val endChapterIndex: Int,
        val endChapterName: String,
        val startPositionInChapterMs: Long,
        val endPositionInChapterMs: Long,
        val endBookPositionMs: Long,
        val listenedMs: Long
    )

    data class SkipEventEntry(
        val atMs: Long,
        val kind: String,
        /** "jump" | "skip_button" | "auto" — dropped by today's BackupManager export; carried
         *  here so the disk mirror doesn't lose it. */
        val source: String,
        val fromPositionMs: Long,
        val toPositionMs: Long,
        val chapterIndex: Int,
        val chapterName: String,
        val fromSpineIndex: Int?,
        val fromFraction: Float?,
        val toSpineIndex: Int?,
        val toFraction: Float?,
        val toSpineTitle: String?
    )

    companion object {
        const val CURRENT_VERSION = 1
    }
}
