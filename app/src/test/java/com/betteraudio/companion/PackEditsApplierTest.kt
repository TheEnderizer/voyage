package com.betteraudio.companion

import com.betteraudio.companion.model.BoardKind
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.companion.model.PackBoardElement
import com.betteraudio.companion.model.PackEditOp
import com.betteraudio.companion.model.PackEditsDoc
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.companion.model.Presentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackEditsApplierTest {

    private fun entity(id: String, name: String, kind: EntityKind = EntityKind.CHARACTER) = PackEntity(id, kind, name)

    private fun fact(id: String, entityId: String, field: String, value: String, rev: Int = 1) = PackFact(
        factId = id, entityId = entityId, field = field, value = value,
        anchor = FactAnchor(bookRef = "m1", globalMs = 0L), importance = Importance.NOTABLE, rev = rev
    )

    private fun edits(vararg ops: PackEditOp, baseRevision: Int = 1) = PackEditsDoc(baseRevision = baseRevision, ops = ops.toList())

    @Test
    fun `no edits doc returns base unchanged`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")))
        assertEquals(base, PackEditsApplier.apply(base, null))
    }

    @Test
    fun `empty ops list returns base unchanged`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")))
        assertEquals(base, PackEditsApplier.apply(base, edits()))
    }

    @Test
    fun `addEntity appends a new entity`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")))
        val result = PackEditsApplier.apply(base, edits(PackEditOp.AddEntity(entity = entity("e2", "Vin"))))
        assertEquals(setOf("Kelsier", "Vin"), result.entities.map { it.name }.toSet())
    }

    @Test
    fun `editEntity patches only the named fields`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier", EntityKind.CHARACTER)))
        val result = PackEditsApplier.apply(base, edits(PackEditOp.EditEntity(entityId = "e1", fields = mapOf("name" to "Kel"), rev = 1)))
        assertEquals("Kel", result.entities[0].name)
        assertEquals(EntityKind.CHARACTER, result.entities[0].kind)
    }

    @Test
    fun `deleteEntity removes the entity and its facts`() {
        val base = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("f1", "e1", "allegiance", "Survivors"))
        )
        val result = PackEditsApplier.apply(base, edits(PackEditOp.DeleteEntity(entityId = "e1", rev = 1)))
        assertTrue(result.entities.isEmpty())
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun `hideEntity removes it from output without deleting the base`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")))
        val result = PackEditsApplier.apply(base, edits(PackEditOp.HideEntity(entityId = "e1")))
        assertTrue(result.entities.isEmpty())
    }

    @Test
    fun `editFact patches value and importance`() {
        val base = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("f1", "e1", "allegiance", "Unknown"))
        )
        val result = PackEditsApplier.apply(base, edits(PackEditOp.EditFact(factId = "f1", fields = mapOf("value" to "Survivors", "importance" to "MAJOR"), rev = 1)))
        assertEquals("Survivors", result.facts[0].value)
        assertEquals(Importance.MAJOR, result.facts[0].importance)
    }

    @Test
    fun `deleteFact tombstones without deleting the entity`() {
        val base = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("f1", "e1", "allegiance", "Survivors"))
        )
        val result = PackEditsApplier.apply(base, edits(PackEditOp.DeleteFact(factId = "f1", rev = 1)))
        assertEquals(1, result.entities.size)
        assertTrue(result.facts.isEmpty())
    }

    @Test
    fun `addElement and editElement and deleteElement operate on the right board`() {
        val board = PackBoard(boardId = "b1", kind = BoardKind.MAP, presentation = Presentation.FULLSCREEN)
        val base = CompanionPackDoc(boards = listOf(board))
        val added = PackEditsApplier.apply(
            base, edits(PackEditOp.AddElement(boardId = "b1", element = PackBoardElement(id = "el1", kind = "PIN", x = 0f, y = 0f, w = 0f, h = 0f)))
        )
        assertEquals(1, added.boards[0].elements.size)

        val edited = PackEditsApplier.apply(
            added, edits(PackEditOp.EditElement(boardId = "b1", elementId = "el1", fields = mapOf("x" to 500.0, "text" to "hi")))
        )
        assertEquals(500f, edited.boards[0].elements[0].x)
        assertEquals("hi", edited.boards[0].elements[0].text)

        val deleted = PackEditsApplier.apply(edited, edits(PackEditOp.DeleteElement(boardId = "b1", elementId = "el1")))
        assertTrue(deleted.boards[0].elements.isEmpty())
    }

    @Test
    fun `ops apply in order - later ops win`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")))
        val result = PackEditsApplier.apply(
            base,
            edits(
                PackEditOp.EditEntity(entityId = "e1", fields = mapOf("name" to "First"), rev = 1),
                PackEditOp.EditEntity(entityId = "e1", fields = mapOf("name" to "Second"), rev = 1)
            )
        )
        assertEquals("Second", result.entities[0].name)
    }

    @Test
    fun `an op targeting a nonexistent id is a silent no-op`() {
        val base = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")))
        val result = PackEditsApplier.apply(base, edits(PackEditOp.EditEntity(entityId = "ghost", fields = mapOf("name" to "X"), rev = 1)))
        assertEquals("Kelsier", result.entities[0].name)
    }

    // ── conflict detection ────────────────────────────────────────────────

    @Test
    fun `no conflicts when nothing upstream changed`() {
        val newBase = CompanionPackDoc(revision = 1, entities = listOf(entity("e1", "Kelsier")), facts = listOf(fact("f1", "e1", "allegiance", "Survivors", rev = 1)))
        val editsDoc = edits(PackEditOp.EditFact(factId = "f1", fields = mapOf("value" to "Legend"), rev = 1))
        assertTrue(PackEditsApplier.detectConflicts(newBase, editsDoc).isEmpty())
    }

    @Test
    fun `fact conflict when upstream bumped that fact's rev`() {
        val newBase = CompanionPackDoc(revision = 2, facts = listOf(fact("f1", "e1", "allegiance", "Survivors", rev = 2)))
        val editsDoc = edits(PackEditOp.EditFact(factId = "f1", fields = mapOf("value" to "Legend"), rev = 1))
        val conflicts = PackEditsApplier.detectConflicts(newBase, editsDoc)
        assertEquals(1, conflicts.size)
        assertEquals(PackEditsApplier.ConflictKind.UPSTREAM_ALSO_CHANGED, conflicts[0].kind)
    }

    @Test
    fun `fact conflict is UPSTREAM_DELETED when the fact no longer exists`() {
        val newBase = CompanionPackDoc(revision = 2, facts = emptyList())
        val editsDoc = edits(PackEditOp.EditFact(factId = "f1", fields = mapOf("value" to "Legend"), rev = 1))
        val conflicts = PackEditsApplier.detectConflicts(newBase, editsDoc)
        assertEquals(PackEditsApplier.ConflictKind.UPSTREAM_DELETED, conflicts[0].kind)
    }

    @Test
    fun `entity edit conflict is coarse - any revision bump flags it`() {
        val newBase = CompanionPackDoc(revision = 3, entities = listOf(entity("e1", "Kelsier")))
        val editsDoc = edits(PackEditOp.EditEntity(entityId = "e1", fields = mapOf("name" to "Kel"), rev = 1))
        val conflicts = PackEditsApplier.detectConflicts(newBase, editsDoc)
        assertEquals(1, conflicts.size)
        assertEquals(PackEditsApplier.ConflictKind.UPSTREAM_ALSO_CHANGED, conflicts[0].kind)
    }

    @Test
    fun `entity edit at the same revision as authored does not conflict`() {
        val newBase = CompanionPackDoc(revision = 1, entities = listOf(entity("e1", "Kelsier")))
        val editsDoc = edits(PackEditOp.EditEntity(entityId = "e1", fields = mapOf("name" to "Kel"), rev = 1))
        assertTrue(PackEditsApplier.detectConflicts(newBase, editsDoc).isEmpty())
    }

    @Test
    fun `addEntity and hideEntity ops never conflict`() {
        val newBase = CompanionPackDoc(revision = 5)
        val editsDoc = edits(
            PackEditOp.AddEntity(entity = entity("e9", "New")),
            PackEditOp.HideEntity(entityId = "e1")
        )
        assertTrue(PackEditsApplier.detectConflicts(newBase, editsDoc).isEmpty())
    }
}
