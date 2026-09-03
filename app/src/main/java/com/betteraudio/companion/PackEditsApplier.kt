package com.betteraudio.companion

import com.betteraudio.companion.model.ArtTone
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.companion.model.PackBoardElement
import com.betteraudio.companion.model.PackEditOp
import com.betteraudio.companion.model.PackEditsDoc
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact

/**
 * Pure op-application engine for the edits overlay (docs/companion-packs.md §9.2, P6) — "render =
 * base, then ops" is the whole design, and this is that render step. The received `pack.json`
 * itself is never mutated; [apply] always starts from [base] and folds [PackEditsDoc.ops] over it
 * in list order, so calling it twice with the same inputs is always the same output.
 *
 * Every consumption/authoring surface that reads a pack the device doesn't own — see
 * [com.betteraudio.ui.companion.CompanionViewModel]/[com.betteraudio.ui.companion.CompanionAuthorViewModel] —
 * reads the result of [apply], never the raw base doc, so a local edit is indistinguishable from an
 * author-original one anywhere downstream (the reveal cursor, the map, the cast strip).
 */
object PackEditsApplier {

    fun apply(base: CompanionPackDoc, edits: PackEditsDoc?): CompanionPackDoc {
        if (edits == null || edits.ops.isEmpty()) return base

        val entities = base.entities.toMutableList()
        val facts = base.facts.toMutableList()
        val boards = base.boards.toMutableList()
        val hiddenEntityIds = HashSet<String>()
        val deletedEntityIds = HashSet<String>()
        val deletedFactIds = HashSet<String>()

        for (op in edits.ops) {
            when (op) {
                is PackEditOp.AddEntity -> entities.add(op.entity)
                is PackEditOp.EditEntity -> replaceEntity(entities, op.entityId) { patchEntity(it, op.fields) }
                is PackEditOp.DeleteEntity -> deletedEntityIds += op.entityId
                is PackEditOp.HideEntity -> hiddenEntityIds += op.entityId

                is PackEditOp.AddFact -> facts.add(op.fact)
                is PackEditOp.EditFact -> replaceFact(facts, op.factId) { patchFact(it, op.fields) }
                is PackEditOp.DeleteFact -> deletedFactIds += op.factId

                is PackEditOp.EditBoard -> replaceBoard(boards, op.boardId) { patchBoard(it, op.fields) }
                is PackEditOp.AddElement -> replaceBoard(boards, op.boardId) { it.copy(elements = it.elements + op.element) }
                is PackEditOp.EditElement -> replaceBoard(boards, op.boardId) { board ->
                    board.copy(elements = board.elements.map { el -> if (el.id == op.elementId) patchElement(el, op.fields) else el })
                }
                is PackEditOp.DeleteElement -> replaceBoard(boards, op.boardId) { board ->
                    board.copy(elements = board.elements.filterNot { it.id == op.elementId })
                }
            }
        }

        val hiddenOrDeletedEntities = hiddenEntityIds + deletedEntityIds
        val effectiveEntities = entities.filterNot { it.entityId in hiddenOrDeletedEntities }
        val effectiveFacts = facts.filterNot { it.factId in deletedFactIds || it.entityId in hiddenOrDeletedEntities }

        return base.copy(entities = effectiveEntities, facts = effectiveFacts, boards = boards)
    }

    private fun replaceEntity(list: MutableList<PackEntity>, id: String, patch: (PackEntity) -> PackEntity) {
        val idx = list.indexOfFirst { it.entityId == id }
        if (idx >= 0) list[idx] = patch(list[idx])
    }

    private fun replaceFact(list: MutableList<PackFact>, id: String, patch: (PackFact) -> PackFact) {
        val idx = list.indexOfFirst { it.factId == id }
        if (idx >= 0) list[idx] = patch(list[idx])
    }

    private fun replaceBoard(list: MutableList<PackBoard>, id: String, patch: (PackBoard) -> PackBoard) {
        val idx = list.indexOfFirst { it.boardId == id }
        if (idx >= 0) list[idx] = patch(list[idx])
    }

    private fun patchEntity(e: PackEntity, fields: Map<String, Any?>): PackEntity {
        var result = e
        (fields["name"] as? String)?.let { result = result.copy(name = it) }
        (fields["kind"] as? String)?.let { k -> enumOrNull<EntityKind>(k)?.let { result = result.copy(kind = it) } }
        // Pack-relative portrait path. No codec change was needed to carry it: EditEntity.fields is
        // an open string map precisely so a new patchable field is one line here rather than a
        // format revision (§9.2's op vocabulary is fixed; the fields inside an op are not).
        (fields["media"] as? String)?.let { result = result.copy(media = it) }
        return result
    }

    private fun patchFact(f: PackFact, fields: Map<String, Any?>): PackFact {
        var result = f
        (fields["value"] as? String)?.let { result = result.copy(value = it) }
        (fields["importance"] as? String)?.let { i -> enumOrNull<Importance>(i)?.let { result = result.copy(importance = it) } }
        return result
    }

    private fun patchBoard(b: PackBoard, fields: Map<String, Any?>): PackBoard {
        var result = b
        (fields["artTone"] as? String)?.let { t -> enumOrNull<ArtTone>(t)?.let { result = result.copy(artTone = it) } }
        (fields["artMedia"] as? String)?.let { result = result.copy(artMedia = it) }
        return result
    }

    private fun patchElement(el: PackBoardElement, fields: Map<String, Any?>): PackBoardElement {
        var result = el
        numberOf(fields["x"])?.let { result = result.copy(x = it) }
        numberOf(fields["y"])?.let { result = result.copy(y = it) }
        numberOf(fields["w"])?.let { result = result.copy(w = it) }
        numberOf(fields["h"])?.let { result = result.copy(h = it) }
        numberOf(fields["rotationDeg"])?.let { result = result.copy(rotationDeg = it) }
        numberOf(fields["opacity"])?.let { result = result.copy(opacity = it) }
        (fields["text"] as? String)?.let { result = result.copy(text = it) }
        (fields["imagePath"] as? String)?.let { result = result.copy(imagePath = it) }
        return result
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        runCatching { java.lang.Enum.valueOf(T::class.java, name) }.getOrNull()

    private fun numberOf(v: Any?): Float? = when (v) {
        is Number -> v.toFloat()
        is String -> v.toFloatOrNull()
        else -> null
    }

    // ── conflict detection (§9.2) ────────────────────────────────────────────

    enum class ConflictKind {
        /** Upstream also touched the same item since this op was authored — the table's "keep
         *  yours" / "take theirs" row. */
        UPSTREAM_ALSO_CHANGED,
        /** Upstream deleted the item this op targets — "keep yours, orphaned" / "drop it". */
        UPSTREAM_DELETED
    }

    data class Conflict(val op: PackEditOp, val kind: ConflictKind, val label: String)

    /**
     * Compares every rev-carrying op in [edits] against [newBase] — the incoming updated pack on a
     * revision bump. [PackFact] carries its own `rev`, so fact ops conflict-check precisely
     * (§9.2's actual per-fact rule). [PackEntity]/[PackBoard] carry no `rev` of their own (see
     * their kdocs) — entity/board ops instead compare against the pack's own
     * [CompanionPackDoc.revision], which is coarser (any revision bump flags them) but the only
     * signal the data model provides; see [PackEditOp]'s kdoc for why that split exists. Add-type
     * ops, [PackEditOp.HideEntity], and element ops never conflict — an addition can't collide with
     * an upstream change, and element ops carry no rev to compare (kdoc on [PackEditOp]).
     */
    fun detectConflicts(newBase: CompanionPackDoc, edits: PackEditsDoc): List<Conflict> {
        val entityIds = newBase.entities.mapTo(HashSet()) { it.entityId }
        val boardIds = newBase.boards.mapTo(HashSet()) { it.boardId }
        val factById = newBase.facts.associateBy { it.factId }
        val conflicts = mutableListOf<Conflict>()

        for (op in edits.ops) {
            when (op) {
                is PackEditOp.EditEntity -> packLevelConflict(op, op.entityId, op.rev, op.entityId in entityIds, newBase.revision, "a character you edited", conflicts)
                is PackEditOp.DeleteEntity -> packLevelConflict(op, op.entityId, op.rev, op.entityId in entityIds, newBase.revision, "a character you removed", conflicts)
                is PackEditOp.EditBoard -> packLevelConflict(op, op.boardId, op.rev, op.boardId in boardIds, newBase.revision, "a board you edited", conflicts)
                is PackEditOp.EditFact -> factConflict(op, op.factId, op.rev, factById[op.factId]?.rev, "a fact you edited", conflicts)
                is PackEditOp.DeleteFact -> factConflict(op, op.factId, op.rev, factById[op.factId]?.rev, "a fact you removed", conflicts)
                else -> Unit
            }
        }
        return conflicts
    }

    private fun packLevelConflict(op: PackEditOp, targetId: String, opRev: Int, stillExists: Boolean, newRevision: Int, label: String, out: MutableList<Conflict>) {
        if (!stillExists) {
            out += Conflict(op, ConflictKind.UPSTREAM_DELETED, label)
        } else if (opRev != newRevision) {
            out += Conflict(op, ConflictKind.UPSTREAM_ALSO_CHANGED, label)
        }
    }

    private fun factConflict(op: PackEditOp, factId: String, opRev: Int, currentRev: Int?, label: String, out: MutableList<Conflict>) {
        if (currentRev == null) {
            out += Conflict(op, ConflictKind.UPSTREAM_DELETED, label)
        } else if (currentRev != opRev) {
            out += Conflict(op, ConflictKind.UPSTREAM_ALSO_CHANGED, label)
        }
    }
}
