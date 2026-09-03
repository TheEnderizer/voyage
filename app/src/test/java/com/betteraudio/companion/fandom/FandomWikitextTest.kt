package com.betteraudio.companion.fandom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wikitext in these fixtures is trimmed from real Shadow Slave wiki articles — the ScrollBox
 * wrappers, the `<ref name=":1285" />` back-references, the numbered `rank1`/`rank3` tab variants
 * and the strikethrough superseded titles are all shapes the live wiki actually uses, not
 * hypotheticals.
 */
class FandomWikitextTest {

    private fun fieldsOf(seed: FandomWikitext.SeedCharacter): Map<String, String> =
        // last revision wins, mirroring how the reveal cursor resolves a field
        seed.facts.associate { it.field to it.value }

    // ── the spoiler gate ──────────────────────────────────────────────────

    @Test
    fun `a line cited after the cutoff is withheld`() {
        val wikitext = """
            {{Character infobox
            | alias=
            *Kid<ref>Chapter 98 Uninvited Guests</ref>
            *Lord of Shadows<ref>Chapter 2247 Throne of Shadows</ref>
            }}
        """.trimIndent()
        val seed = FandomWikitext.parseLead("Sunny", wikitext, cutoffChapter = 1308)
        val value = fieldsOf(seed).getValue("identity:Also known as")
        assertTrue(value.contains("Kid"))
        assertTrue("a chapter-2247 alias must not reach a chapter-1308 listener", !value.contains("Lord of Shadows"))
        assertEquals(1, seed.withheld)
    }

    @Test
    fun `a fact is anchored at the chapter that cites it`() {
        val wikitext = """
            {{Character infobox
            | true_name=Lost from Light<ref>Chapter 745 Freedom of Choice</ref>
            }}
        """.trimIndent()
        val seed = FandomWikitext.parseLead("Sunny", wikitext, cutoffChapter = 1308)
        val fact = seed.facts.first { it.field == "identity:True name" }
        assertEquals(745, fact.chapter)
    }

    @Test
    fun `a ref named after its chapter still dates the line`() {
        // <ref name=":1285" /> is a *reuse* of a ref defined elsewhere on the page — at the use
        // site the chapter number in the name is the only citation present.
        val wikitext = """
            {{Character infobox
            | alias=
            *Lord<ref name=":1285" />
            }}
        """.trimIndent()
        val early = FandomWikitext.parseLead("Sunny", wikitext, cutoffChapter = 1000)
        assertTrue(early.facts.none { it.field == "identity:Also known as" })
        assertEquals(1, early.withheld)

        val later = FandomWikitext.parseLead("Sunny", wikitext, cutoffChapter = 1400)
        assertEquals("Lord", fieldsOf(later).getValue("identity:Also known as"))
    }

    @Test
    fun `a death parameter is dropped even when it is cited early`() {
        // The field NAME is the spoiler: knowing a character has a "cause of death" at all is the
        // reveal, so no citation can license it.
        val wikitext = """
            {{Character infobox
            | vital_status=Deceased<ref>Chapter 12 Something</ref>
            | cause_of_death=Killed by the Sovereign<ref>Chapter 12 Something</ref>
            | race=Human
            }}
        """.trimIndent()
        val fields = fieldsOf(FandomWikitext.parseLead("X", wikitext, cutoffChapter = 9999))
        assertNull(fields["identity:Status"])
        assertTrue(fields.keys.none { it.contains("death", ignoreCase = true) })
        assertEquals("Human", fields["identity:Race"])
    }

    @Test
    fun `an uncited value survives only for a parameter that cannot be a spoiler`() {
        val wikitext = """
            {{Character infobox
            | eye_color=Grey
            | affiliation=The Faceless Sovereign's inner circle
            }}
        """.trimIndent()
        val fields = fieldsOf(FandomWikitext.parseLead("X", wikitext, cutoffChapter = 9999))
        assertEquals("Grey", fields["identity:Eyes"])
        assertNull("an undatable affiliation cannot be gated, so it must not be seeded", fields["identity:Affiliation"])
    }

    // ── markup ────────────────────────────────────────────────────────────

    @Test
    fun `a ScrollBox wrapper does not leak into the value`() {
        // Regression: the wrapper opens on one line and closes many lines later, so splitting the
        // value into bullets first leaves "{{ScrollBox |content=" glued to the first entry.
        val wikitext = """
            {{Character infobox
            | alias=
            {{ScrollBox
            |content=
            *Sunny {{c|Nickname}}<ref>Chapter 1 Nightmare Begins</ref>
            *Doofus {{c|by [[Effie]]}}<ref>Chapter 127 Abandon All Hope</ref>
            }}
            }}
        """.trimIndent()
        val value = fieldsOf(FandomWikitext.parseLead("Sunny", wikitext, cutoffChapter = 1308))
            .getValue("identity:Also known as")
        assertTrue("ScrollBox leaked: $value", !value.contains("ScrollBox", ignoreCase = true))
        assertTrue(!value.contains("content="))
        assertEquals("Sunny, Doofus", value)
    }

    @Test
    fun `links lose their targets and inline tags are stripped`() {
        val wikitext = """
            {{Character infobox
            | height=<nowiki>5'5 - 5'6</nowiki>
            | title=<s>[[Sleeper]] Sunless</s><ref>Chapter 17 A</ref>
            }}
        """.trimIndent()
        val fields = fieldsOf(FandomWikitext.parseLead("X", wikitext, cutoffChapter = 9999))
        assertEquals("5'5 - 5'6", fields["identity:Height"])
        assertEquals("Sleeper Sunless", fields["identity:Titles"])
    }

    @Test
    fun `findInfobox is brace-balanced across nested templates`() {
        val wikitext = "{{Some infobox\n| a={{ScrollBox|content=x}}\n| b=y\n}}\nLead prose here."
        val box = FandomWikitext.findInfobox(wikitext)!!
        assertTrue(box.body.contains("b=y"))
        assertEquals("Lead prose here.", wikitext.substring(box.endIndex).trim())
    }

    @Test
    fun `splitTopLevel keeps piped links and nested templates whole`() {
        val parts = FandomWikitext.splitTopLevel("a=[[Page|Label]]|b={{T|x|y}}|c=3")
        assertEquals(listOf("a=[[Page|Label]]", "b={{T|x|y}}", "c=3"), parts)
    }

    // ── shape of the result ───────────────────────────────────────────────

    @Test
    fun `numbered tab variants collapse onto one field`() {
        // rank1/rank3 are the same field on two article tabs, not two fields.
        val wikitext = """
            {{Character infobox
            | rank1=Sleeper<ref>Chapter 10 A</ref>
            | rank3=Supreme<ref>Chapter 2247 B</ref>
            }}
        """.trimIndent()
        val seed = FandomWikitext.parseLead("X", wikitext, cutoffChapter = 1308)
        assertEquals(listOf("identity:Rank"), seed.facts.map { it.field }.filter { it.startsWith("identity") })
    }

    @Test
    fun `a list field revises itself across the story instead of landing all at once`() {
        val wikitext = """
            {{Character infobox
            | alias=
            *Kid<ref>Chapter 10 A</ref>
            *Devil<ref>Chapter 825 B</ref>
            }}
        """.trimIndent()
        val facts = FandomWikitext.parseLead("X", wikitext, cutoffChapter = 1308)
            .facts.filter { it.field == "identity:Also known as" }
        assertEquals(2, facts.size)
        assertEquals("Kid" to 10, facts[0].value to facts[0].chapter)
        assertEquals("Kid, Devil" to 825, facts[1].value to facts[1].chapter)
    }

    @Test
    fun `the lead description drops a sentence that cites a future chapter`() {
        val wikitext = """
            {{Character infobox
            | race=Human
            }}
            '''Sunless''' is the main character of [[Shadow Slave]].
            He eventually becomes the Lord of Shadows.<ref>Chapter 2247 Throne of Shadows</ref>
        """.trimIndent()
        val seed = FandomWikitext.parseLead("Sunny", wikitext, cutoffChapter = 1308)
        val description = fieldsOf(seed).getValue("description")
        assertTrue(description.startsWith("Sunless is the main character"))
        assertTrue("future sentence leaked: $description", !description.contains("Lord of Shadows"))
    }

    @Test
    fun `the source url is recorded for attribution`() {
        val wikitext = "{{Character infobox\n| race=Human\n}}"
        val seed = FandomWikitext.parseLead(
            "Sunny", wikitext, cutoffChapter = 10, sourceUrl = "https://shadowslave.fandom.com/wiki/Sunny"
        )
        assertEquals("https://shadowslave.fandom.com/wiki/Sunny", fieldsOf(seed)["source"])
    }

    @Test
    fun `an article with no infobox still yields its lead`() {
        val seed = FandomWikitext.parseLead(
            "Gorn", "'''Gorn''' is a veteran Awakened who guards the caravan road.", cutoffChapter = 50
        )
        assertTrue(fieldsOf(seed).getValue("description").startsWith("Gorn is a veteran"))
    }

    @Test
    fun `an article that cites nothing usable yields nothing rather than everything`() {
        // The correct failure mode for a wiki that does not cite chapters: empty, not unfiltered.
        val wikitext = """
            {{Character infobox
            | affiliation=The rebellion
            | title=Warden of the Deep
            }}
        """.trimIndent()
        val seed = FandomWikitext.parseLead("X", wikitext, cutoffChapter = 1)
        assertTrue(seed.facts.none { it.field.startsWith("identity:") })
    }
}
