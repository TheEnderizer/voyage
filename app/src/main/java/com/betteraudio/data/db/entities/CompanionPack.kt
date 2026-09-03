package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Thin registry row for one companion pack (docs/companion-packs.md) — enough to list/enable/
 * disable packs and locate their doc on disk WITHOUT parsing it. The actual entities/facts/
 * boards live in `pack.json` (+ `edits.json` if the pack has local edits), read into memory by
 * [com.betteraudio.data.diskstore.CompanionDataStore] when a companion becomes active — a
 * generous pack is a few thousand records, kilobytes, so there is deliberately no relational fact
 * storage here (same conclusion the widget maker reached in its v19 rewrite: a JSON design
 * document, not a grid schema).
 *
 * [packId] is the pack's own UUID (minted at authoring time, never a Room id — see
 * CompanionPackDoc's kdoc for why). [targetKey] is `BookDataPaths.relPath(...)` for a BOOK-scoped
 * pack or the local `Series.name` for a SERIES-scoped pack — portable identity, NOT
 * `Book.folderPath`, which `LibraryRestructurer` rewrites on every move and this table does not
 * track. There is deliberately no stored doc path: it is derivable from [targetKey] + [scope] +
 * [packId] (see CompanionDataStore), so storing it would be redundant and a second thing that
 * could go stale.
 */
@Entity(tableName = "companion_packs", indices = [Index("targetKey")])
data class CompanionPack(
    @PrimaryKey val packId: String,
    /** "BOOK" | "SERIES" — see [com.betteraudio.companion.model.PackScope]. */
    val scope: String,
    val targetKey: String,
    val title: String,
    val authorHandle: String? = null,
    val revision: Int = 1,
    val enabled: Boolean = true,
    /** Drives the "since you last looked" digest (docs/companion-packs.md §8.1) — the reveal
     *  position as of the last time the companion for this pack was opened. */
    val lastSeenRevealMs: Long = 0L,
    /**
     * True for a pack this device authored (§9.1 — the timeline editor writes straight into
     * `pack.json`); false for a pack received from someone else (§9.2 — the base `pack.json` is
     * immutable and local changes live in `edits.json` instead). Determines which write path
     * [com.betteraudio.companion.CompanionAuthorRepository]/the P6 edits-overlay logic takes.
     * Defaults true because every pack registered before P4's import flow exists is, definitionally,
     * one this device created; P4's attach step explicitly sets this false.
     */
    val isOwn: Boolean = true
)
