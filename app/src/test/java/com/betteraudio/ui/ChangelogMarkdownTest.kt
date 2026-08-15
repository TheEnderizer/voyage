package com.betteraudio.ui

import com.betteraudio.ui.components.parseChangelog
import com.betteraudio.ui.components.parseChangelogBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The changelog parser behind every release-notes surface (Settings → About, the Updates card,
 * and the launch-time update prompt). Pure text in, structure out — no Compose involved.
 */
class ChangelogMarkdownTest {

    private val entry = """
        Voyage works sideways. Every screen now has a real landscape
        layout instead of the portrait one stretched.

        You can also change the app icon: six colours.

        ### Added
        - **Landscape everywhere.** The library grid adds columns
          instead of inflating the covers.
        - Two landscape player styles.

        ### Fixed
        - A finished book could restart in the wrong place.
    """.trimIndent()

    @Test
    fun `summary paragraphs are kept and split on blank lines`() {
        val body = parseChangelogBody(entry)
        assertEquals(2, body.summary.size)
        assertTrue(body.summary[0].startsWith("Voyage works sideways."))
        // Wrapped source lines are rejoined into one paragraph.
        assertTrue(body.summary[0].endsWith("portrait one stretched."))
        assertEquals("You can also change the app icon: six colours.", body.summary[1])
    }

    @Test
    fun `categories and bullets are parsed, wrapped bullets rejoined`() {
        val body = parseChangelogBody(entry)
        assertEquals(listOf("Added", "Fixed"), body.categories.map { it.name })
        assertEquals(2, body.categories[0].entries.size)
        assertEquals(
            "**Landscape everywhere.** The library grid adds columns instead of inflating the covers.",
            body.categories[0].entries[0]
        )
        assertEquals(1, body.categories[1].entries.size)
    }

    @Test
    fun `a body with no summary or no categories still parses`() {
        assertTrue(parseChangelogBody("").isEmpty)
        assertEquals(1, parseChangelogBody("Just a sentence.").summary.size)
        assertEquals(0, parseChangelogBody("### Fixed\n- One thing.").summary.size)
    }

    @Test
    fun `whole file parses newest-first and drops empty versions`() {
        val file = """
            # Changelog (Beta)

            ## [Unreleased]

            ## [1.10.0b] - 2026-08-15

            A summary line.

            ### Added
            - Landscape.

            ## [1.9.19b] - 2026-08-13

            ### Changed
            - Further work.
        """.trimIndent()

        val versions = parseChangelog(file)
        // [Unreleased] has no body, so it is dropped rather than rendered as an empty card.
        assertEquals(listOf("1.10.0b", "1.9.19b"), versions.map { it.version })
        assertEquals("2026-08-15", versions[0].date)
        assertEquals(listOf("A summary line."), versions[0].body.summary)
        assertEquals("Landscape.", versions[0].body.categories[0].entries[0])
        assertEquals("Changed", versions[1].body.categories[0].name)
    }
}
