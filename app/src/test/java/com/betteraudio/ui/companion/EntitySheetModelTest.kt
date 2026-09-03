package com.betteraudio.ui.companion

import com.betteraudio.companion.CompanionEntityState
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitySheetModelTest {

    private fun fact(field: String, value: String) = PackFact(
        factId = "f-$field",
        entityId = "e1",
        field = field,
        value = value,
        anchor = FactAnchor(bookRef = "m1", globalMs = 1L),
        importance = Importance.NOTABLE
    )

    private fun state(
        id: String = "e1",
        name: String = "Sunny",
        kind: EntityKind = EntityKind.CHARACTER,
        vararg fields: Pair<String, String>
    ) = CompanionEntityState(
        entity = PackEntity(entityId = id, kind = kind, name = name),
        // LinkedHashMap: EntityStateResolver hands over insertion-ordered fields and the sheet's
        // section order must not depend on hash order.
        fields = LinkedHashMap<String, PackFact>().apply {
            fields.forEach { (f, v) -> put(f, fact(f, v)) }
        },
        firstRevealedMs = 1L
    )

    private fun sectionTitles(sheet: EntitySheetModel.Sheet) = sheet.sections.map { it.title }

    private fun entries(sheet: EntitySheetModel.Sheet, title: String) =
        sheet.sections.first { it.title == title }.entries

    @Test
    fun `identity badges are ordered by the stat line, not by field order`() {
        val sheet = EntitySheetModel.build(
            state(
                fields = arrayOf(
                    "identity:Shadow cores" to "5/7",
                    "identity:Class" to "Tyrant",
                    "identity:Rank" to "Ascended"
                )
            ),
            known = emptyList()
        )
        assertEquals(listOf("Rank", "Class", "Shadow cores"), sheet.badges.map { it.label })
        assertEquals("Ascended", sheet.badges.first().value)
        // Badges are the stat line, never rows.
        assertTrue(sheet.sections.none { it.entries.any { e -> e.label == "Rank" } })
    }

    @Test
    fun `true name wins over other subtitle candidates`() {
        val sheet = EntitySheetModel.build(
            state(
                fields = arrayOf(
                    "identity:Role" to "Commander",
                    "identity:Also known as" to "The Lord of Shadows",
                    "identity:True name" to "Lost from Light"
                )
            ),
            known = emptyList()
        )
        assertEquals("Lost from Light", sheet.subtitle)
    }

    @Test
    fun `an arsenal value becomes chips`() {
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("arsenal:Memories" to "Silver Bell, Midnight Shard, Cruel Sight")),
            known = emptyList()
        )
        val entry = entries(sheet, "Arsenal").single()
        assertTrue(entry is EntitySheetModel.Entry.Chips)
        assertEquals(
            listOf("Silver Bell", "Midnight Shard", "Cruel Sight"),
            (entry as EntitySheetModel.Entry.Chips).values
        )
    }

    @Test
    fun `a power value that is prose stays prose even though it contains commas`() {
        // The distinguishing signal is capitalisation, not the presence of a comma or the length
        // of the parts — every part here is short enough to be a pill, and rendering this value as
        // five of them would be absurd.
        val sheet = EntitySheetModel.build(
            state(
                fields = arrayOf(
                    "power:Aspect" to
                        "Strength, speed, agility, endurance and resilience, all raised at once"
                )
            ),
            known = emptyList()
        )
        assertTrue(entries(sheet, "Power").single() is EntitySheetModel.Entry.Text)
    }

    @Test
    fun `a short power list becomes chips`() {
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("power:Attributes" to "Fated, Flame of Divinity, Blood Weave")),
            known = emptyList()
        )
        assertTrue(entries(sheet, "Power").single() is EntitySheetModel.Entry.Chips)
    }

    @Test
    fun `a bond resolves to a revealed entity and is tappable`() {
        val nephis = state(id = "e2", name = "Nephis")
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("bond:Nephis" to "Fights beside her")),
            known = listOf(nephis)
        )
        val bond = entries(sheet, "Bonds").single() as EntitySheetModel.Entry.Bond
        assertEquals("e2", bond.entityId)
        assertEquals("Nephis", bond.label)
    }

    @Test
    fun `a bond to someone not yet revealed still renders but is not tappable`() {
        // The whole point of the spoiler filter: naming an unrevealed character in a bond must not
        // make them reachable, or the sheet leaks that they exist.
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("bond:Mordret" to "A prince who seems to already know him")),
            known = emptyList()
        )
        val bond = entries(sheet, "Bonds").single() as EntitySheetModel.Entry.Bond
        assertNull(bond.entityId)
        assertEquals("A prince who seems to already know him", bond.value)
    }

    @Test
    fun `location is never a row`() {
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("location" to "200,780", "introduced" to "Chapter 1")),
            known = emptyList()
        )
        assertEquals(listOf("Details"), sectionTitles(sheet))
        assertEquals(listOf("Introduced"), entries(sheet, "Details").map { it.label })
    }

    @Test
    fun `an ungrouped pack still renders every field`() {
        // A pack written before the convention existed must not lose data or crash.
        val sheet = EntitySheetModel.build(
            state(
                fields = arrayOf(
                    "description" to "The protagonist.",
                    "allegiance" to "Survivors",
                    "volume" to "Volume 1"
                )
            ),
            known = emptyList()
        )
        assertEquals("The protagonist.", sheet.summary)
        assertEquals(listOf("Details"), sectionTitles(sheet))
        assertEquals(listOf("Allegiance", "Volume"), entries(sheet, "Details").map { it.label })
    }

    @Test
    fun `sections come out in reading order`() {
        val sheet = EntitySheetModel.build(
            state(
                fields = arrayOf(
                    "bond:Jet" to "Master Jet",
                    "introduced" to "Chapter 1",
                    "arsenal:Memories" to "Silver Bell, Midnight Shard",
                    "power:Flaw" to "Clear Conscience"
                )
            ),
            known = emptyList()
        )
        assertEquals(listOf("Power", "Arsenal", "Bonds", "Details"), sectionTitles(sheet))
    }

    @Test
    fun `an empty value is dropped rather than rendering a blank row`() {
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("power:Flaw" to "   ", "power:Aspect" to "Shadow Slave")),
            known = emptyList()
        )
        assertEquals(listOf("Aspect"), entries(sheet, "Power").map { it.label })
    }

    @Test
    fun `a group prefix with no label is not treated as a group`() {
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf(":" to "nonsense", "power:" to "also nonsense")),
            known = emptyList()
        )
        assertTrue(sheet.sections.isEmpty())
    }

    @Test
    fun `an over-long subtitle candidate becomes a row instead of burying the name`() {
        // A seeded pack routinely puts nine aliases in `identity:Also known as`; as the subtitle
        // that is a wall of accent text above the character's own description.
        val long = "Sunny, Kid/Crazy kid, Doofus/Captain Doofus, Gremlin, Devil, Little Devil, Sah-nee"
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("identity:Also known as" to long)),
            known = emptyList()
        )
        assertNull(sheet.subtitle)
        val entry = entries(sheet, "Details").single()
        assertEquals("Also known as", entry.label)
        assertTrue(entry is EntitySheetModel.Entry.Chips)
    }

    @Test
    fun `a short subtitle candidate is still the subtitle`() {
        val sheet = EntitySheetModel.build(
            state(fields = arrayOf("identity:True name" to "Lost from Light")),
            known = emptyList()
        )
        assertEquals("Lost from Light", sheet.subtitle)
    }
}
