package com.betteraudio.companion

import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackBoardElement
import com.betteraudio.companion.model.PackEditOp
import com.betteraudio.companion.model.PackEditsDoc
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.diskstore.CompanionDataStore
import com.betteraudio.data.diskstore.DiskMirror
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local-edit writes for a **received** pack (docs/companion-packs.md §9.2, P6) — the counterpart
 * to [CompanionAuthorRepository] for a pack this device doesn't own. The base `pack.json` is never
 * touched; every mutation here appends (or rewrites) an op in `edits.json` instead, keyed against
 * the base's own current `rev` for that item so a later revision bump can tell "upstream also
 * changed this" from "upstream left it alone" (see [PackEditsApplier.detectConflicts]).
 *
 * Every method here mirrors one on [CompanionAuthorRepository] with the same name and shape, which
 * is what lets [com.betteraudio.ui.companion.CompanionAuthorViewModel] present one UI
 * ([com.betteraudio.ui.companion.CompanionEditorSheet], [com.betteraudio.ui.companion.PackBoardScreen])
 * for both "author my own pack" and "adjust a pack someone sent me", routed by
 * [com.betteraudio.data.db.entities.CompanionPack.isOwn] rather than duplicated.
 */
@Singleton
class CompanionEditsRepository @Inject constructor(
    private val bookDao: BookDao,
    private val companionPackDao: CompanionPackDao,
    private val companionDataStore: CompanionDataStore,
    private val diskMirror: DiskMirror
) {
    suspend fun editEntity(
        bookId: Long,
        packId: String,
        entityId: String,
        name: String? = null,
        kind: EntityKind? = null,
        /** Pack-relative portrait path, e.g. `media/sunny.webp`. The image file itself is written
         *  into the *base* pack's `media/` directory by [CompanionDataStore.writeBookPackMedia] —
         *  an overlay can only ever add ops, and an op that carried image bytes would make
         *  `edits.json` unshareable at the 4 KB size §9.3 depends on. */
        media: String? = null
    ): Boolean =
        appendOp(bookId, packId) { base ->
            val fields = buildMap {
                name?.let { put("name", it) }
                kind?.let { put("kind", it.name) }
                media?.let { put("media", it) }
            }
            if (fields.isEmpty()) return@appendOp null
            PackEditOp.EditEntity(entityId = entityId, fields = fields, rev = base.revision)
        } != null

    suspend fun deleteEntity(bookId: Long, packId: String, entityId: String): Boolean =
        appendOp(bookId, packId) { base -> PackEditOp.DeleteEntity(entityId = entityId, rev = base.revision) } != null

    suspend fun hideEntity(bookId: Long, packId: String, entityId: String): Boolean =
        appendOp(bookId, packId) { PackEditOp.HideEntity(entityId = entityId) } != null

    suspend fun addEntity(bookId: Long, packId: String, kind: EntityKind, name: String): String? {
        val entityId = UUID.randomUUID().toString()
        val ok = appendOp(bookId, packId) { PackEditOp.AddEntity(entity = PackEntity(entityId = entityId, kind = kind, name = name)) } != null
        return if (ok) entityId else null
    }

    suspend fun addFact(
        bookId: Long,
        packId: String,
        entityId: String,
        field: String,
        value: String,
        anchorGlobalMs: Long,
        importance: Importance
    ): String? {
        val factId = UUID.randomUUID().toString()
        val ok = appendOp(bookId, packId) { base ->
            val bookRef = base.members.firstOrNull()?.bookRef ?: "m1"
            val book = bookDao.getBookOnce(bookId)
            val ratio = book?.takeIf { it.totalDurationMs > 0L }?.let { (anchorGlobalMs.toDouble() / it.totalDurationMs).toFloat().coerceIn(0f, 1f) }
            PackEditOp.AddFact(
                fact = com.betteraudio.companion.model.PackFact(
                    factId = factId, entityId = entityId, field = field, value = value,
                    anchor = FactAnchor(bookRef = bookRef, globalMs = anchorGlobalMs, ratio = ratio),
                    importance = importance, origin = packId, rev = base.revision
                )
            )
        } != null
        return if (ok) factId else null
    }

    /** Edits the fact if a live op for it already exists in this session's edits (avoids spamming
     *  waypoints the way [CompanionAuthorRepository]'s dedup does); otherwise appends a fresh op
     *  against the base fact's own `rev`. */
    suspend fun editOrAddFact(bookId: Long, packId: String, existingFactId: String?, entityId: String, field: String, value: String, anchorGlobalMs: Long, importance: Importance): Boolean {
        if (existingFactId != null) {
            return appendOp(bookId, packId) { base ->
                val baseRev = base.facts.firstOrNull { it.factId == existingFactId }?.rev ?: base.revision
                PackEditOp.EditFact(factId = existingFactId, fields = mapOf("value" to value, "importance" to importance.name), rev = baseRev)
            } != null
        }
        return addFact(bookId, packId, entityId, field, value, anchorGlobalMs, importance) != null
    }

    suspend fun editFact(bookId: Long, packId: String, factId: String, value: String? = null, importance: Importance? = null): Boolean =
        appendOp(bookId, packId) { base ->
            val fields = buildMap {
                value?.let { put("value", it) }
                importance?.let { put("importance", it.name) }
            }
            if (fields.isEmpty()) return@appendOp null
            val baseRev = base.facts.firstOrNull { it.factId == factId }?.rev ?: base.revision
            PackEditOp.EditFact(factId = factId, fields = fields, rev = baseRev)
        } != null

    suspend fun deleteFact(bookId: Long, packId: String, factId: String): Boolean =
        appendOp(bookId, packId) { base ->
            val baseRev = base.facts.firstOrNull { it.factId == factId }?.rev ?: base.revision
            PackEditOp.DeleteFact(factId = factId, rev = baseRev)
        } != null

    /** Op-based counterpart to [CompanionAuthorRepository.convertScrap] — an [PackEditOp.AddEntity]
     *  (only when [entityId] is null) followed by an [PackEditOp.AddFact], both against the base's
     *  current revision. The scrap itself is marked converted the same way regardless of ownership
     *  (`scraps.json` isn't part of the shared schema — see [PackScrapsDoc]'s kdoc), so that part is
     *  left to the caller, matching [CompanionAuthorRepository.convertScrap]'s own contract. */
    suspend fun convertScrap(
        bookId: Long,
        packId: String,
        note: String,
        anchor: FactAnchor,
        entityId: String?,
        newEntityName: String?,
        newEntityKind: EntityKind,
        field: String,
        importance: Importance
    ): Boolean {
        val base = companionDataStore.readBookPack(bookId, packId) ?: return false
        val targetEntityId: String
        if (entityId != null && base.entities.any { it.entityId == entityId }) {
            targetEntityId = entityId
        } else {
            targetEntityId = UUID.randomUUID().toString()
            val name = newEntityName?.takeIf { it.isNotBlank() } ?: note.take(30).ifBlank { "New entity" }
            appendOp(bookId, packId) { PackEditOp.AddEntity(entity = PackEntity(entityId = targetEntityId, kind = newEntityKind, name = name)) }
        }
        val op = appendOp(bookId, packId) { b ->
            PackEditOp.AddFact(
                fact = com.betteraudio.companion.model.PackFact(
                    factId = UUID.randomUUID().toString(), entityId = targetEntityId, field = field, value = note,
                    anchor = anchor, importance = importance, origin = packId, rev = b.revision
                )
            )
        }
        return op != null
    }

    suspend fun addPinElement(bookId: Long, packId: String, boardId: String, entityId: String, field: String = "location"): String? {
        val elementId = UUID.randomUUID().toString()
        val ok = appendOp(bookId, packId) {
            PackEditOp.AddElement(
                boardId = boardId,
                element = PackBoardElement(id = elementId, kind = PackBoardResolver.PIN_KIND, x = 0f, y = 0f, w = 0f, h = 0f, entityRef = entityId, field = field)
            )
        } != null
        return if (ok) elementId else null
    }

    suspend fun deleteBoardElement(bookId: Long, packId: String, boardId: String, elementId: String): Boolean =
        appendOp(bookId, packId) { PackEditOp.DeleteElement(boardId = boardId, elementId = elementId) } != null

    /** "Duplicate as my own" (§9.2) — flattens base+edits into a brand-new, fully-owned pack with a
     *  fresh id, stamped with `derivedFrom` for attribution, and disables the original registry row
     *  (this app shows one active pack per book — §2's stated v1 non-goal on layering/switchers). */
    suspend fun forkPack(bookId: Long, packId: String): String? {
        val original = companionPackDao.getById(packId) ?: return null
        val base = companionDataStore.readBookPack(bookId, packId) ?: return null
        val ourEdits = companionDataStore.readBookPackEdits(bookId, packId)
        val effective = PackEditsApplier.apply(base, ourEdits)
        val forked = effective.copy(
            packId = UUID.randomUUID().toString(),
            revision = 1,
            derivedFrom = com.betteraudio.companion.model.DerivedFrom(
                packId = base.packId, revision = base.revision, authorHandle = base.authorHandle
            )
        )
        diskMirror.flushBookPack(bookId, forked)
        companionPackDao.upsert(
            com.betteraudio.data.db.entities.CompanionPack(
                packId = forked.packId,
                scope = forked.scope.name,
                targetKey = original.targetKey,
                title = "${forked.title} (mine)",
                revision = forked.revision,
                enabled = true,
                isOwn = true
            )
        )
        companionPackDao.setEnabled(packId, false)
        return forked.packId
    }

    /**
     * Applies an incoming updated base (`§9.2`'s revision-swap): writes the new `pack.json`,
     * bumps the registry row, and leaves `edits.json` untouched — ops are self-describing (each
     * carries the `rev` it was authored against), so nothing about them needs rewriting; only the
     * base changes underneath them. Conflicts against the new base are [PackEditsApplier.detectConflicts]'s
     * job, called separately so the caller can show a review screen first when the list isn't empty.
     */
    suspend fun applyUpdate(bookId: Long, newBase: CompanionPackDoc) {
        diskMirror.flushBookPack(bookId, newBase)
        val existing = companionPackDao.getById(newBase.packId)
        if (existing != null) {
            companionPackDao.upsert(existing.copy(title = newBase.title, revision = newBase.revision))
        }
    }

    /** [applyUpdate] plus [PackEditsApplier.detectConflicts] against the local `edits.json`, run
     *  before the swap so the conflict list is computed against the actual old-edits/new-base pair
     *  (called from [CompanionImportService] on re-importing a pack this device already has). */
    suspend fun updateWithConflictCheck(bookId: Long, newBase: CompanionPackDoc): List<PackEditsApplier.Conflict> {
        val existingEdits = companionDataStore.readBookPackEdits(bookId, newBase.packId)
        val conflicts = existingEdits?.let { PackEditsApplier.detectConflicts(newBase, it) }.orEmpty()
        applyUpdate(bookId, newBase)
        return conflicts
    }

    /** "Take theirs" (§9.2) — drops one specific op, letting the new base's value show through
     *  unmodified for that item. */
    suspend fun dropOp(bookId: Long, packId: String, opId: String) {
        val edits = companionDataStore.readBookPackEdits(bookId, packId) ?: return
        val updated = edits.copy(ops = edits.ops.filterNot { it.opId == opId })
        diskMirror.flushBookPackEdits(bookId, packId, updated)
    }

    private suspend fun appendOp(bookId: Long, packId: String, build: suspend (base: CompanionPackDoc) -> PackEditOp?): PackEditOp? {
        val pack = companionPackDao.getById(packId)?.takeIf { !it.isOwn } ?: return null
        val base = companionDataStore.readBookPack(bookId, packId) ?: return null
        val op = build(base) ?: return null
        val existing = companionDataStore.readBookPackEdits(bookId, packId) ?: PackEditsDoc(baseRevision = base.revision)
        val updated = existing.copy(baseRevision = base.revision, ops = existing.ops + op)
        diskMirror.flushBookPackEdits(bookId, packId, updated)
        return op
    }
}
