package com.betteraudio.companion

import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityStateResolverTest {

    private val book = Book(
        id = 1L, title = "t", author = "a", folderPath = "/lib/book",
        totalDurationMs = 10_000_000L, addedDateMs = 0L, status = BookStatus.NOT_STARTED, fileCount = 1
    )

    private fun entity(id: String, name: String) = PackEntity(entityId = id, kind = EntityKind.CHARACTER, name = name)

    private fun fact(entityId: String, field: String, value: String, globalMs: Long, rev: Int = 1) = PackFact(
        factId = "$entityId-$field-$globalMs",
        entityId = entityId,
        field = field,
        value = value,
        anchor = FactAnchor(bookRef = "m1", globalMs = globalMs),
        importance = Importance.NOTABLE,
        rev = rev
    )

    @Test
    fun `an entity with no revealed facts does not appear`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("e1", "allegiance", "Survivors", globalMs = 500_000L))
        )
        val states = EntityStateResolver.resolve(doc, book, revealedMs = 100_000L)
        assertTrue(states.isEmpty())
    }

    @Test
    fun `a fact exactly at the reveal cursor is included - inclusive boundary`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("e1", "allegiance", "Survivors", globalMs = 100_000L))
        )
        val states = EntityStateResolver.resolve(doc, book, revealedMs = 100_000L)
        assertEquals(1, states.size)
        assertEquals("Survivors", states[0].fields["allegiance"]?.value)
    }

    @Test
    fun `the newest revealed fact per field wins`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(
                fact("e1", "allegiance", "Unknown", globalMs = 10_000L),
                fact("e1", "allegiance", "Survivors", globalMs = 500_000L),
                fact("e1", "allegiance", "Legend", globalMs = 900_000L) // beyond cursor - excluded
            )
        )
        val states = EntityStateResolver.resolve(doc, book, revealedMs = 600_000L)
        assertEquals("Survivors", states[0].fields["allegiance"]?.value)
    }

    @Test
    fun `different fields on the same entity resolve independently`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(
                fact("e1", "allegiance", "Survivors", globalMs = 100_000L),
                fact("e1", "title", "Lord Ruler's Bane", globalMs = 800_000L)
            )
        )
        val states = EntityStateResolver.resolve(doc, book, revealedMs = 300_000L)
        assertEquals("Survivors", states[0].fields["allegiance"]?.value)
        assertNull(states[0].fields["title"])
    }

    @Test
    fun `firstRevealedMs is the earliest revealed fact, driving introduction order`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier"), entity("e2", "Vin")),
            facts = listOf(
                fact("e1", "name", "Kelsier", globalMs = 500_000L),
                fact("e2", "name", "Vin", globalMs = 100_000L)
            )
        )
        val states = EntityStateResolver.resolve(doc, book, revealedMs = 1_000_000L)
        assertEquals(listOf("Vin", "Kelsier"), states.map { it.entity.name })
    }

    @Test
    fun `a fact for an entity not in the entities list is silently ignored`() {
        val doc = CompanionPackDoc(
            entities = emptyList(),
            facts = listOf(fact("ghost", "name", "Nobody", globalMs = 0L))
        )
        assertTrue(EntityStateResolver.resolve(doc, book, revealedMs = 1_000_000L).isEmpty())
    }

    @Test
    fun `an unresolvable anchor (no globalMs or ratio) never reveals its fact`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(PackFact("f1", "e1", "location", "Luthadel", FactAnchor(bookRef = "m1", chapter = 3)))
        )
        assertTrue(EntityStateResolver.resolve(doc, book, revealedMs = 10_000_000L).isEmpty())
    }
}
