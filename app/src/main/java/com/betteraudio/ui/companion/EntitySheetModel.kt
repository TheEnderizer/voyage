package com.betteraudio.ui.companion

import com.betteraudio.companion.CompanionEntityState
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.PackFact

/**
 * Turns one entity's resolved fields into the shape a character sheet actually renders
 * (docs/companion-packs.md §8) — a crest, a stat line, and a handful of titled sections.
 *
 * ### Why a field-name convention rather than a schema change
 *
 * [com.betteraudio.companion.model.PackFact.field] is deliberately an open string, and
 * [CompanionEntityState.fields] is one fact per field name. That is enough to express everything a
 * rich sheet needs, as long as authors agree on how to name fields — so a pack marks a field's
 * section with a **`group:Label` prefix**:
 *
 * | field                     | renders as                                    |
 * |---------------------------|-----------------------------------------------|
 * | `identity:Rank`           | a badge in the stat line                      |
 * | `identity:True name`      | the subtitle under the name                   |
 * | `power:Flaw`              | a row in **Power**                             |
 * | `arsenal:Memories`        | chips in **Arsenal**                           |
 * | `bond:Nephis`             | a tappable relationship row in **Bonds**       |
 * | `description`             | the summary paragraph                          |
 * | anything else             | a row in **Details**                           |
 *
 * The alternative — new typed columns on `PackFact` — would have been a pack-format break for
 * something the format already supports, and would have to be repeated for every future section
 * kind. The cost of the convention is that it is a convention: a pack that ignores it still
 * renders, its fields simply land in **Details**, which is why the fallback is a real section and
 * not a crash or a dropped value.
 *
 * Multi-value fields are a comma-joined string in one fact, not one fact per item, so that a list
 * that *grows over the story* (Sunny's Memories) is one field revising itself — which is what the
 * reveal cursor and the digest are built around — instead of N fields appearing independently.
 *
 * Every value here is already spoiler-filtered by [com.betteraudio.companion.EntityStateResolver];
 * this file only re-shapes what it was handed.
 */
object EntitySheetModel {

    /** Fields that are data for something else and would be noise as text — the map board reads
     *  `location` (see [com.betteraudio.companion.PackBoardResolver.DEFAULT_LOCATION_FIELD]), and
     *  it renders as raw canvas units, so it is never a sheet row. */
    private val HIDDEN = setOf("location")

    /** `identity:` labels that belong in the stat line rather than in Details, in display order. */
    private val BADGE_LABELS = listOf(
        "Rank", "Class", "Soul cores", "Shadow cores", "Soul core", "Shadow core",
        "Soul fragments", "Shadow fragments", "Type", "Tier"
    )

    /** Subtitle preference, best first. */
    private val SUBTITLE_LABELS = listOf("True name", "Also known as", "Role")

    private const val GROUP_IDENTITY = "identity"
    private const val GROUP_POWER = "power"
    private const val GROUP_ARSENAL = "arsenal"
    private const val GROUP_BOND = "bond"

    /** Longest a comma-separated part may be and still fit in a pill. */
    private const val MAX_CHIP_LENGTH = 34

    /**
     * Longest a value may be and still work as the subtitle under the name.
     *
     * A subtitle is one accent-coloured line identifying the character ("Lost from Light"). A
     * seeded pack can put a nine-entry alias list in the same field, and rendering that as the
     * subtitle buries the name under a wall of accent text. Anything longer falls through to a
     * normal row, where a list becomes chips and reads correctly.
     */
    private const val MAX_SUBTITLE_LENGTH = 60

    data class Badge(val label: String, val value: String, val factId: String? = null)

    /**
     * Every rendered row also carries [factId] — the id of the fact it was built from.
     *
     * That is the whole "editable descriptor": the deck edits a value *where it is displayed*
     * (docs/companion-redesign.html §04), which means the row has to know which fact it is. Without
     * it, editing had to happen in a parallel list somewhere else — which is exactly what the old
     * `CompanionEditorSheet` was, and exactly why it was a second modal stacked on the first.
     *
     * Nullable because a row is not always one fact: [Chips] built from a comma-joined value is one
     * fact split for display, and a [Bond] whose target is not a revealed entity has a fact but no
     * one to point at. Callers treat null as "not editable here", never as an error.
     */
    sealed interface Entry {
        val label: String
        val factId: String?

        data class Text(
            override val label: String,
            val value: String,
            override val factId: String? = null
        ) : Entry

        data class Chips(
            override val label: String,
            val values: List<String>,
            override val factId: String? = null
        ) : Entry

        /** A relationship. [entityId] is null when the pack names someone who is not an entity in
         *  it (or who is not revealed yet) — the row still renders, it just is not tappable. */
        data class Bond(
            override val label: String,
            val value: String,
            val entityId: String?,
            override val factId: String? = null
        ) : Entry
    }

    data class Section(val title: String, val entries: List<Entry>)

    data class Sheet(
        val entityId: String,
        val name: String,
        val kind: EntityKind,
        val subtitle: String?,
        val badges: List<Badge>,
        val summary: String?,
        /** Fact id behind [summary], so the summary paragraph is editable in place like any row. */
        val summaryFactId: String?,
        val sections: List<Section>
    )

    /**
     * @param known every entity currently revealed, used only to resolve `bond:Name` to a tappable
     *   id. Pass the same already-filtered list the cast strip is showing — passing the whole pack
     *   would make an unrevealed character tappable and leak that they exist.
     */
    fun build(state: CompanionEntityState, known: List<CompanionEntityState>): Sheet {
        val idsByName = known.associate { it.entity.name.lowercase() to it.entity.entityId }

        var summary: String? = null
        var summaryFactId: String? = null
        val subtitles = HashMap<String, String>()
        val badges = HashMap<String, Pair<String, String>>()
        val power = mutableListOf<Entry>()
        val arsenal = mutableListOf<Entry>()
        val bonds = mutableListOf<Entry>()
        val details = mutableListOf<Entry>()

        for ((field, fact: PackFact) in state.fields) {
            if (field in HIDDEN) continue
            val split = field.indexOf(':')
            val group = if (split > 0) field.substring(0, split).lowercase() else ""
            val label = if (split > 0) field.substring(split + 1).trim() else field
            val value = fact.value.trim()
            // `label.trim(':')` catches a field that is nothing but punctuation (":", "::"), which
            // would otherwise render as a row with a colon for a name.
            if (label.trim(':').isEmpty() || value.isEmpty()) continue

            when {
                group == GROUP_IDENTITY && label in SUBTITLE_LABELS &&
                    value.length <= MAX_SUBTITLE_LENGTH -> subtitles[label] = value
                group == GROUP_IDENTITY && label in BADGE_LABELS -> badges[label] = value to fact.factId
                group == GROUP_IDENTITY -> details += entryFor(label, value, fact.factId)
                group == GROUP_POWER -> power += entryFor(label, value, fact.factId)
                group == GROUP_ARSENAL -> arsenal += Entry.Chips(label, splitList(value), fact.factId)
                group == GROUP_BOND ->
                    bonds += Entry.Bond(label, value, idsByName[label.lowercase()], fact.factId)
                field == "description" -> { summary = value; summaryFactId = fact.factId }
                else -> details += Entry.Text(
                    label.replaceFirstChar { it.uppercase() }, value, fact.factId
                )
            }
        }

        val sections = buildList {
            if (power.isNotEmpty()) add(Section("Power", power))
            if (arsenal.isNotEmpty()) add(Section("Arsenal", arsenal))
            if (bonds.isNotEmpty()) add(Section("Bonds", bonds.sortedBy { it.label }))
            if (details.isNotEmpty()) add(Section("Details", details))
        }

        return Sheet(
            entityId = state.entity.entityId,
            name = state.entity.name,
            kind = state.entity.kind,
            subtitle = SUBTITLE_LABELS.firstNotNullOfOrNull { subtitles[it] },
            badges = BADGE_LABELS.mapNotNull { l -> badges[l]?.let { Badge(l, it.first, it.second) } },
            summary = summary,
            summaryFactId = summaryFactId,
            sections = sections
        )
    }

    /**
     * A `power:` value may be either a list of named things ("Fated, Flame of Divinity, Blood
     * Weave") or a sentence that happens to contain commas ("Strength, speed, agility, endurance
     * and resilience, all raised at once"). Length alone does not separate them — both split into
     * short-enough parts — but **capitalisation does**: the things this power system names are
     * proper nouns, and prose is not. So chips require every part to start capitalised, which is
     * also a rule an author can follow deliberately to choose the rendering.
     *
     * `arsenal:` skips this check entirely: it is declared to be a list.
     */
    private fun entryFor(label: String, value: String, factId: String?): Entry {
        val parts = splitList(value)
        val allNames = parts.all { part ->
            part.length <= MAX_CHIP_LENGTH && part.first().let { it.isUpperCase() || it.isDigit() }
        }
        return if (parts.size >= 2 && allNames) Entry.Chips(label, parts, factId)
               else Entry.Text(label, value, factId)
    }

    private fun splitList(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
