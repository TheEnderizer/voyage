package com.betteraudio.companion.model

/**
 * In-memory shape of a `.voyagepack` archive's `manifest.json` (docs/companion-packs.md §10.1) —
 * kept as a separate top-level zip entry from `pack.json` so an importer can read placement info
 * and matching fingerprints ([members]) without inflating the whole archive. For a DATA_ONLY pack
 * that's a minor convenience (`pack.json` is already tiny); it's load-bearing once P7 ships FULL
 * packs with gigabytes of bundled audio, so the layout is fixed now rather than changed later.
 *
 * Never record where the sender kept the book (§10.2) — [members] carries only the semantic
 * fingerprint ([PackMember]'s title/author/durationMs/fileCount/fileKeys), which the *recipient's*
 * app resolves against their own library.
 */
data class CompanionManifestDoc(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val packId: String,
    val scope: PackScope,
    val payload: PackPayload,
    val title: String,
    val exportedAtMs: Long = System.currentTimeMillis(),
    val members: List<PackMember> = emptyList(),
    /** Paths of every file actually zipped under `media/`, relative to the archive root — lets
     *  import verify the archive is complete without trusting the zip's own entry list alone. */
    val mediaFiles: List<String> = emptyList(),
    /** FULL only (§10.1, P7) — every file zipped under `audio/`, in playback order, with the
     *  fingerprint import verifies extraction against (§10.3 step 3: name + size, not [fileKey] —
     *  see [ManifestAudioFile]'s kdoc for why). Empty for DATA_ONLY. */
    val audioFiles: List<ManifestAudioFile> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * One audio file bundled in a FULL `.voyagepack`. [fileKey] is carried for completeness/parity with
 * [PackMember.fileKeys] but import verification (§10.3 step 3) checks name + size, matching
 * [com.betteraudio.data.files.LibraryRestructurer.verify]'s own established recipe for exactly this
 * kind of check — computing and comparing a fresh SHA-1 per file on every import would cost a full
 * read of every extracted file for no matching benefit once the bytes have already round-tripped
 * through a zip's own CRC check.
 */
data class ManifestAudioFile(
    val fileName: String,
    val sizeBytes: Long,
    val fileKey: String? = null
)
