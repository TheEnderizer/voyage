package com.betteraudio.data.diskstore

/**
 * In-memory shape of `<libraryRoot>/.voyage/library.json` — presets, authors and series, the
 * library-wide data that has no single book folder to live in. Field shapes mirror
 * BackupManager.gatherPresets()/gatherAuthors()/gatherSeries() plus the additions the disk
 * mirror needs (covers, createdAtMs) that the manual backup export doesn't carry today.
 */
data class LibraryDocument(
    val version: Int = CURRENT_VERSION,
    val writtenAt: Long,
    val libraryRoot: String,
    val presets: List<PresetEntry> = emptyList(),
    val authors: List<AuthorEntry> = emptyList(),
    val series: List<SeriesEntry> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
) {
    data class PresetEntry(
        val name: String,
        val type: String,
        val speedMult: Float,
        val boostDb: Int,
        val eqBandsJson: String?,
        val isDefault: Boolean
    )

    data class AuthorEntry(
        val name: String,
        /** Path relative to .voyage/, e.g. "covers/author_brandon_sanderson.jpg". */
        val cover: String?
    )

    data class SeriesEntry(
        val name: String,
        val author: String?,
        val narrator: String?,
        val description: String?,
        val playbackSpeed: Float?,
        val boostDb: Int?,
        val eqBandsJson: String?,
        val skipSilenceEnabled: Boolean?,
        /** Path relative to .voyage/. */
        val cover: String?,
        val createdAtMs: Long,
        val members: List<MemberRef>
    )

    data class MemberRef(
        val folderPath: String,
        val relPath: String,
        val title: String,
        val author: String,
        val seriesOrder: Float?
    )

    companion object {
        const val CURRENT_VERSION = 1
    }
}
