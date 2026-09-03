package com.betteraudio.companion

import com.betteraudio.companion.model.BoardKind
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.companion.model.PackBoardElement
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.companion.model.Presentation
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PackBoardResolverTest {

    private val book = Book(
        id = 1L, title = "t", author = "a", folderPath = "/lib/book",
        totalDurationMs = 10_000_000L, addedDateMs = 0L, status = BookStatus.NOT_STARTED, fileCount = 1
    )

    private fun entity(id: String, name: String) = PackEntity(entityId = id, kind = EntityKind.CHARACTER, name = name)

    private fun fact(entityId: String, field: String, value: String, globalMs: Long) = PackFact(
        factId = "$entityId-$field-$globalMs", entityId = entityId, field = field, value = value,
        anchor = FactAnchor(bookRef = "m1", globalMs = globalMs), importance = Importance.NOTABLE
    )

    private fun pinElement(id: String, entityRef: String, field: String? = null) = PackBoardElement(
        id = id, kind = PackBoardResolver.PIN_KIND, x = 0f, y = 0f, w = 40f, h = 40f, entityRef = entityRef, field = field
    )

    private fun board(elements: List<PackBoardElement>) = PackBoard(
        boardId = "b1", kind = BoardKind.MAP, presentation = Presentation.FULLSCREEN, elements = elements
    )

    @Test
    fun `a pin with no resolved location fact does not render`() {
        val doc = CompanionPackDoc(entities = listOf(entity("e1", "Kelsier")), facts = emptyList())
        val pins = PackBoardResolver.resolvePins(doc, board(listOf(pinElement("p1", "e1"))), book, revealedMs = 100_000L)
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `a pin renders at its newest revealed location`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(
                fact("e1", "location", "100,200", globalMs = 10_000L),
                fact("e1", "location", "500,600", globalMs = 500_000L),
                fact("e1", "location", "900,900", globalMs = 900_000L) // beyond cursor
            )
        )
        val pins = PackBoardResolver.resolvePins(doc, board(listOf(pinElement("p1", "e1"))), book, revealedMs = 600_000L)
        assertEquals(1, pins.size)
        assertEquals(500f, pins[0].xUnits)
        assertEquals(600f, pins[0].yUnits)
    }

    @Test
    fun `a moved pin at two different reveal points produces two different positions`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(
                fact("e1", "location", "100,100", globalMs = 0L),
                fact("e1", "location", "800,200", globalMs = 1_000_000L)
            )
        )
        val b = board(listOf(pinElement("p1", "e1")))
        val early = PackBoardResolver.resolvePins(doc, b, book, revealedMs = 500_000L)
        val late = PackBoardResolver.resolvePins(doc, b, book, revealedMs = 2_000_000L)
        assertEquals(100f to 100f, early[0].xUnits to early[0].yUnits)
        assertEquals(800f to 200f, late[0].xUnits to late[0].yUnits)
    }

    @Test
    fun `a custom field name is honored instead of the location default`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("e1", "lastSeenAt", "300,300", globalMs = 0L))
        )
        val pins = PackBoardResolver.resolvePins(doc, board(listOf(pinElement("p1", "e1", field = "lastSeenAt"))), book, revealedMs = 1_000L)
        assertEquals(1, pins.size)
    }

    @Test
    fun `an unparseable location value does not render a pin`() {
        val doc = CompanionPackDoc(
            entities = listOf(entity("e1", "Kelsier")),
            facts = listOf(fact("e1", "location", "not-coordinates", globalMs = 0L))
        )
        val pins = PackBoardResolver.resolvePins(doc, board(listOf(pinElement("p1", "e1"))), book, revealedMs = 1_000L)
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `a pin bound to an entity that never appears on the board is skipped`() {
        val doc = CompanionPackDoc(entities = emptyList(), facts = emptyList())
        val pins = PackBoardResolver.resolvePins(doc, board(listOf(pinElement("p1", "ghost"))), book, revealedMs = 1_000_000L)
        assertTrue(pins.isEmpty())
    }

    @Test
    fun `non-pin elements pass through statically regardless of reveal`() {
        val label = PackBoardElement(id = "l1", kind = "LABEL", x = 10f, y = 10f, w = 100f, h = 30f, text = "Luthadel")
        val pin = pinElement("p1", "e1")
        val elements = PackBoardResolver.staticElements(board(listOf(label, pin)))
        assertEquals(listOf("l1"), elements.map { it.id })
    }

    @Test
    fun `coordinate round-trip through format and parse`() {
        val formatted = PackBoardResolver.formatCoords(123.4f, 567.8f)
        val parsed = PackBoardResolver.parseCoords(formatted)
        assertEquals(123f to 567f, parsed)
    }
}
