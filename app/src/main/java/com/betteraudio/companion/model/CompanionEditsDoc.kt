package com.betteraudio.companion.model

import java.util.UUID

/**
 * In-memory shape of `edits.json` (docs/companion-packs.md §9.2) — the local overlay sitting
 * beside an immutable received `pack.json`. Render = base pack, then these ops, in order. Nothing
 * ever mutates the base file, which is what lets an update to a new [PackEditsDoc.baseRevision]
 * swap the base and re-apply the ops instead of destroying local changes.
 *
 * A pack authored locally (not received) has no `edits.json` at all — this file exists only for a
 * pack the user does not own. Applying ops and resolving update conflicts is P6 (build phases,
 * §12); this shape is frozen at P0 anyway, alongside the rest of the schema, because sharing ships
 * at P4 — two phases before edits exist — and once a pack has left the device there is no recall
 * mechanism (§2: no server, no accounts) to fix an incompatible shape later.
 */
data class PackEditsDoc(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** The [CompanionPackDoc.revision] these ops were authored against — an update to a newer
     *  revision diffs against this, not against the (now-replaced) base file itself. */
    val baseRevision: Int,
    val ops: List<PackEditOp> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * One local edit. Every op that targets an existing fact/entity/board/element carries the [rev]
 * (or, for elements, the owning board's fact-equivalent — elements don't have their own `rev` in
 * [PackBoardElement], so element ops are conflict-detected at the board level via [EditBoard]'s
 * `rev`) it was authored against — §9.2's conflict rule is exactly `op.rev != incoming.rev`.
 * [opId] exists only for the review UI (P6) to reference a specific op ("drop it" / "take theirs"
 * acts on one row); it plays no part in ordering or apply semantics.
 */
sealed class PackEditOp {
    abstract val opId: String

    // ── entity ops ─────────────────────────────────────────────────────────
    data class AddEntity(
        override val opId: String = UUID.randomUUID().toString(),
        val entity: PackEntity
    ) : PackEditOp()

    data class EditEntity(
        override val opId: String = UUID.randomUUID().toString(),
        val entityId: String,
        val fields: Map<String, Any?>,
        val rev: Int
    ) : PackEditOp()

    data class DeleteEntity(
        override val opId: String = UUID.randomUUID().toString(),
        val entityId: String,
        val rev: Int
    ) : PackEditOp()

    /** Distinct from delete: the entity stays in the base pack (so an update that restores it
     *  from upstream can't "resurrect" something the recipient never wanted removed outright),
     *  but it renders nowhere. */
    data class HideEntity(
        override val opId: String = UUID.randomUUID().toString(),
        val entityId: String
    ) : PackEditOp()

    // ── fact ops ───────────────────────────────────────────────────────────
    data class AddFact(
        override val opId: String = UUID.randomUUID().toString(),
        val fact: PackFact
    ) : PackEditOp()

    data class EditFact(
        override val opId: String = UUID.randomUUID().toString(),
        val factId: String,
        val fields: Map<String, Any?>,
        val rev: Int
    ) : PackEditOp()

    /** A tombstone, not a physical removal — the base pack's fact stays put; rendering simply
     *  skips any factId with a live DeleteFact op ahead of it. */
    data class DeleteFact(
        override val opId: String = UUID.randomUUID().toString(),
        val factId: String,
        val rev: Int
    ) : PackEditOp()

    // ── board ops — the vocabulary a received pack's boards can be locally adjusted through
    //    (§9.2: without this, the single most likely edit anyone wants — nudging a misplaced pin —
    //    is unexpressible, and the recipient forks instead, which the overlay exists to prevent) ──
    data class EditBoard(
        override val opId: String = UUID.randomUUID().toString(),
        val boardId: String,
        val fields: Map<String, Any?>,
        val rev: Int
    ) : PackEditOp()

    data class AddElement(
        override val opId: String = UUID.randomUUID().toString(),
        val boardId: String,
        val element: PackBoardElement
    ) : PackEditOp()

    data class EditElement(
        override val opId: String = UUID.randomUUID().toString(),
        val boardId: String,
        val elementId: String,
        val fields: Map<String, Any?>
    ) : PackEditOp()

    data class DeleteElement(
        override val opId: String = UUID.randomUUID().toString(),
        val boardId: String,
        val elementId: String
    ) : PackEditOp()
}
