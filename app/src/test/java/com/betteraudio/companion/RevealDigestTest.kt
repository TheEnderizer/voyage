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
import org.junit.Assert.assertTrue
import org.junit.Test

class RevealDigestTest {

    private val book = Book(
        id = 1L, title = "t", author = "a", folderPath = "/lib/book",
        totalDurationMs = 10_000_000L, addedDateMs = 0L, status = BookStatus.NOT_STARTED, fileCount = 1
    )
    private val entity = PackEntity(entityId = "e1", kind = EntityKind.CHARACTER, name = "Kelsier")

    private fun fact(globalMs: Long, importance: Importance) = PackFact(
        factId = "f-$globalMs-$importance", entityId = "e1", field = "status", value = "x",
        anchor = FactAnchor(bookRef = "m1", globalMs = globalMs), importance = importance
    )

    @Test
    fun `facts before sinceMs are not in the digest`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(50_000L, Importance.NOTABLE)))
        val items = RevealDigest.since(doc, book, sinceMs = 100_000L, uptoMs = 500_000L, ReceiverThreshold.NOTABLE_PLUS)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `facts past uptoMs are not in the digest - nothing beyond the current reveal cursor`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(900_000L, Importance.NOTABLE)))
        val items = RevealDigest.since(doc, book, sinceMs = 0L, uptoMs = 500_000L, ReceiverThreshold.NOTABLE_PLUS)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `sinceMs boundary is exclusive, uptoMs boundary is inclusive`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity),
            facts = listOf(fact(100_000L, Importance.NOTABLE), fact(500_000L, Importance.NOTABLE))
        )
        val items = RevealDigest.since(doc, book, sinceMs = 100_000L, uptoMs = 500_000L, ReceiverThreshold.NOTABLE_PLUS)
        assertEquals(listOf(500_000L), items.map { it.resolvedMs })
    }

    @Test
    fun `QUIET never appears in the digest at any threshold`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(200_000L, Importance.QUIET)))
        val items = RevealDigest.since(doc, book, sinceMs = 0L, uptoMs = 500_000L, ReceiverThreshold.NOTABLE_PLUS)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `MAJOR_ONLY threshold excludes NOTABLE`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(200_000L, Importance.NOTABLE)))
        val items = RevealDigest.since(doc, book, sinceMs = 0L, uptoMs = 500_000L, ReceiverThreshold.MAJOR_ONLY)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `MAJOR_ONLY threshold includes MAJOR`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(200_000L, Importance.MAJOR)))
        val items = RevealDigest.since(doc, book, sinceMs = 0L, uptoMs = 500_000L, ReceiverThreshold.MAJOR_ONLY)
        assertEquals(1, items.size)
    }

    @Test
    fun `NOTABLE_PLUS includes both NOTABLE and MAJOR`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity),
            facts = listOf(fact(100_000L, Importance.NOTABLE), fact(200_000L, Importance.MAJOR))
        )
        val items = RevealDigest.since(doc, book, sinceMs = 0L, uptoMs = 500_000L, ReceiverThreshold.NOTABLE_PLUS)
        assertEquals(2, items.size)
    }

    @Test
    fun `NEVER threshold always returns empty regardless of importance`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(200_000L, Importance.MAJOR)))
        val items = RevealDigest.since(doc, book, sinceMs = 0L, uptoMs = 500_000L, ReceiverThreshold.NEVER)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `uptoMs not past sinceMs yields nothing without even scanning facts`() {
        val doc = CompanionPackDoc(entities = listOf(entity), facts = listOf(fact(100_000L, Importance.MAJOR)))
        val items = RevealDigest.since(doc, book, sinceMs = 500_000L, uptoMs = 500_000L, ReceiverThreshold.MAJOR_ONLY)
        assertTrue(items.isEmpty())
    }
}
