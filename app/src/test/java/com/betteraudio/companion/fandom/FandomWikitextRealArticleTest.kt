package com.betteraudio.companion.fandom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the parser over **unedited** lead sections captured from the live Shadow Slave wiki
 * (`app/src/test/resources/fandom/`), because a hand-written fixture only ever exercises the shapes
 * its author already thought of. These two articles between them carry every awkward construct the
 * feature has to survive: a `<gallery>` inside an infobox parameter, three nested `{{ScrollBox}}`
 * lists, `mw-collapsible` divs, strikethrough superseded titles, `{{c|…}}` asides, `<ref name=":N"/>`
 * back-references, and numbered `rank1`/`rank3` tab variants.
 *
 * The assertions are deliberately about *spoilers*, not about exact strings — the wiki is edited by
 * other people and the day a value changes should not be the day this test fails. Refresh the
 * fixtures with `action=parse&page=X&prop=wikitext&section=0`.
 */
class FandomWikitextRealArticleTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fandom/$name")) {
            "missing test fixture fandom/$name"
        }.bufferedReader().readText()

    private fun parse(name: String, title: String, cutoff: Int) =
        FandomWikitext.parseLead(title, fixture(name), cutoff)

    /** Every value the sheet would show, lowercased, as one blob to search for leaks. */
    private fun allText(seed: FandomWikitext.SeedCharacter): String =
        seed.facts.joinToString(" | ") { it.value }.lowercase()

    @Test
    fun `the real article yields a usable sheet at chapter 1308`() {
        val seed = parse("sunny-lead.txt", "Sunny", cutoff = 1308)
        val fields = seed.facts.map { it.field }.toSet()

        assertTrue("expected identity fields, got $fields", fields.any { it.startsWith("identity:") })
        assertNotNull(seed.facts.firstOrNull { it.field == "description" })
        assertTrue("nothing was withheld — the chapter gate cannot have run", seed.withheld > 0)
    }

    @Test
    fun `late-story reveals are absent at chapter 1308`() {
        val text = allText(parse("sunny-lead.txt", "Sunny", cutoff = 1308))
        // Each of these is cited on the live article to a chapter well past 1308.
        listOf("lord of shadows", "dark lord", "crown of twilight", "supreme").forEach { spoiler ->
            assertTrue("'$spoiler' leaked into a chapter-1308 sheet", !text.contains(spoiler))
        }
    }

    @Test
    fun `an early listener sees far less than a late one`() {
        val early = parse("sunny-lead.txt", "Sunny", cutoff = 20)
        val late = parse("sunny-lead.txt", "Sunny", cutoff = 3000)
        assertTrue(
            "cutoff 20 produced ${early.facts.size} facts, cutoff 3000 produced ${late.facts.size}",
            early.facts.size < late.facts.size
        )
        assertTrue(allText(early).length < allText(late).length)
    }

    @Test
    fun `no presentation wrapper survives into a value`() {
        val text = allText(parse("sunny-lead.txt", "Sunny", cutoff = 3000))
        listOf("scrollbox", "mw-collapsible", "content=", "{{", "}}", "[[", "]]", "<ref", "<div", "<gallery")
            .forEach { junk ->
                assertTrue("raw markup '$junk' survived into a value", !text.contains(junk))
            }
    }

    @Test
    fun `no fact is empty or absurdly long`() {
        val seed = parse("sunny-lead.txt", "Sunny", cutoff = 3000)
        seed.facts.forEach { fact ->
            assertTrue("empty value for ${fact.field}", fact.value.isNotBlank())
            assertTrue("${fact.field} is ${fact.value.length} chars", fact.value.length <= 320)
        }
    }

    @Test
    fun `a second real article parses the same way`() {
        val seed = parse("nephis-lead.txt", "Nephis", cutoff = 1308)
        assertTrue(seed.facts.isNotEmpty())
        val text = allText(seed)
        assertTrue(!text.contains("scrollbox"))
        assertTrue(!text.contains("{{"))
        assertEquals(
            "each field should appear as a revision chain, never duplicated at one chapter",
            seed.facts.size,
            seed.facts.map { it.field to it.chapter }.distinct().size
        )
    }

    @Test
    fun `every cited fact is anchored at or before the cutoff`() {
        val cutoff = 1308
        parse("sunny-lead.txt", "Sunny", cutoff).facts.forEach { fact ->
            val chapter = fact.chapter ?: return@forEach
            assertTrue("${fact.field} anchored at chapter $chapter, past the $cutoff cutoff", chapter <= cutoff)
        }
    }
}
