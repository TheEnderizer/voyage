package com.betteraudio.data.scanner

import java.io.File

// Sub-folder names that label a disc/part of one book rather than a separate book,
// e.g. "Mistborn 1 - The Final Empire (1 of 3)" or "Disc 2".
private val DISC_FOLDER_REGEX = Regex(
    "\\(\\d+\\s+of\\s+\\d+\\)|\\b(?:disc|disk|cd|part|pt)\\s*\\d+",
    RegexOption.IGNORE_CASE
)

// Detects an inline volume number in a filename, e.g. "Shadow Slave Volume 7 ...".
// Deliberately excludes "part"/"pt"/"book" — those mean "same book, multiple files"
// (see stemKey), never a genuinely distinct volume, and including them mis-splits
// e.g. "... pt 1.mp3" / "... pt 2.mp3" / "... pt 3.mp3" into three separate books.
private val VOLUME_IN_NAME_REGEX = Regex(
    "\\b(?:volume|vol)\\s*(\\d+)\\b",
    RegexOption.IGNORE_CASE
)

/**
 * Pure filename/folder-name clustering rules used by the AUTO scan mode. No android.*
 * imports and no I/O beyond reading names already-listed [File]s carry — unit-testable
 * without a MediaMetadataRetriever or a repository.
 */
object ScannerHeuristics {

    // A sub-folder name that labels a disc/part of one book rather than a separate book,
    // e.g. "Mistborn 1 - The Final Empire (1 of 3)" or "Disc 2".
    fun looksLikePartFolder(dir: File): Boolean = DISC_FOLDER_REGEX.containsMatchIn(dir.name)

    fun extractTrackNumber(name: String): Int =
        Regex("^(\\d+)").find(name.trim())?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    /**
     * Group a folder's flat audio files into books by similar name. Files whose names
     * share a common "stem" (after stripping leading/trailing sequence numbers) cluster
     * together. The folder is only split into multiple books when there is more than one
     * stem AND at least one stem forms a real sequence (≥2 files) — this avoids shattering
     * a single book whose chapters happen to have unique titles.
     */
    fun clusterBySimilarName(files: List<File>): List<Pair<String, List<File>>> {
        val groups = files.groupBy { stemKey(it.nameWithoutExtension) }
        // Only split when there are ≥2 genuine sequences. One sequence plus a few stray
        // files (intro/outro/bonus) is far more likely a single book than several books.
        val sequenceGroups = groups.values.count { it.size >= 2 }
        if (groups.size <= 1 || sequenceGroups < 2) {
            return splitBySequentialVolumeNumber(files) ?: listOf("" to files)
        }
        // Stable order: by the earliest track number / name within each group
        return groups.entries
            .sortedBy { entry ->
                entry.value.minOf { extractTrackNumber(it.nameWithoutExtension) }
            }
            .map { it.key to it.value }
    }

    /**
     * Fallback for folders where every file is its own one-file "sequence" (so the stem
     * clustering above refuses to split them), but each filename still carries a distinct
     * inline volume/part number — e.g. "Shadow Slave Volume 7 ..." / "... Volume 8 ...".
     * `stemKey` only strips leading/trailing sequence tokens, so a mid-filename number like
     * this survives into the stem and the two files never share a stem.
     */
    fun splitBySequentialVolumeNumber(files: List<File>): List<Pair<String, List<File>>>? {
        if (files.size < 2) return null
        val numbered = files.map { f ->
            f to VOLUME_IN_NAME_REGEX.find(f.nameWithoutExtension)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (numbered.any { it.second == null }) return null
        val numbers = numbered.map { it.second!! }
        if (numbers.toSet().size != files.size) return null
        return numbered.sortedBy { it.second }.map { (f, n) -> "vol$n" to listOf(f) }
    }

    fun stemKey(name: String): String {
        var s = name.lowercase().trim()
        // strip a leading sequence token: "01 - ", "1. ", "3) "
        s = s.replace(Regex("^\\s*\\d{1,4}\\s*[-_.)\\]]*\\s*"), "")
        // strip a trailing sequence token (optionally prefixed by a word like part/track/cd)
        s = s.replace(
            Regex(
                "[\\s\\-_.(\\[]*(?:cd|disc|disk|part|pt|track|chapter|chap|ch|vol|volume|book|episode|ep)?[\\s\\-_.#]*\\d{1,4}\\s*[)\\]]*\\s*$"
            ),
            ""
        )
        return s.replace(Regex("[\\s\\-_.]+"), " ").trim()
    }
}
