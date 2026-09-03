package com.betteraudio.companion.model

import java.util.UUID

/**
 * In-memory shape of `scraps.json` (docs/companion-packs.md §9.1, P3) — quick-capture notes taken
 * during playback, waiting to be turned into a real entity + fact by the timeline editor.
 *
 * Deliberately **not** part of [CompanionPackDoc] / `pack.json`. §12 froze the shared pack schema
 * at P0 (facts, entities, boards, elements, the §9.2 op vocabulary) specifically so sharing (P4)
 * never has to ship a second pack format — a scrap is a local authoring aid with no meaning to a
 * recipient (it hasn't become a fact yet), so it lives in its own sidecar file next to `pack.json`
 * in the same pack directory, and is never zipped into a `.voyagepack` export. Same
 * `JsonUnknownFields`-tolerant `org.json` codec convention as every other on-disk document here.
 */
data class PackScrapsDoc(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val scraps: List<PackScrap> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * One quick-capture: an anchor (where in the story) and a scrap of text, nothing more — the
 * timeline editor turns this into a [PackEntity] + [PackFact] later (§9.1's "capture now, structure
 * later"). [converted] marks a scrap that has already been turned into a fact so the editor's
 * "unconverted notes" list can filter it out without deleting the record of where it came from.
 */
data class PackScrap(
    val scrapId: String = UUID.randomUUID().toString(),
    val anchor: FactAnchor,
    val note: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val converted: Boolean = false,
    val unknown: Map<String, Any?> = emptyMap()
)
