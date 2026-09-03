package com.betteraudio.companion.fandom

/**
 * Turns one Fandom article's lead-section wikitext into draft [SeedFact]s (docs/companion-packs.md
 * §9.4). Pure — no network, no Android — because this is the part that is genuinely hard to get
 * right and therefore the part that has to be testable against real captured wikitext.
 *
 * ### Spoilers are filtered by citation, not by keyword
 *
 * The obvious way to build this feature is a denylist of scary words ("dies", "betrays",
 * "revealed to be"). That approach is both leaky and unprincipled — it cannot tell you *when*
 * something stops being a spoiler, which is the only question that actually matters to a reveal
 * cursor.
 *
 * Fandom wikis answer that question directly: a well-maintained article cites the chapter for
 * nearly every claim it makes, as `<ref>Chapter 172 Memory Market</ref>` or `{{c|Chapter 761}}`.
 * So the filter here is arithmetic — **a line whose earliest citation is past the listener's
 * cutoff chapter is dropped**, and a line that survives is anchored at the chapter it cites, which
 * is exactly the input [com.betteraudio.companion.model.FactAnchor] wants. A pack seeded this way
 * is not "spoiler-free because we hoped so"; it is spoiler-free on the same measured basis as a
 * hand-authored pack, and it keeps revealing itself correctly as the listener goes on.
 *
 * The residual risk is stated plainly rather than hidden: an **uncited** line cannot be gated, so
 * uncited values are dropped unless the parameter is in [SAFE_UNCITED] — facts like eye colour
 * that are true from the character's first scene. Wikis that cite nothing therefore yield very
 * little here, which is the correct failure mode (empty, not wrong).
 */
object FandomWikitext {

    /**
     * One proposed fact. [chapter] is the story chapter it was cited to — null for an uncited
     * [SAFE_UNCITED] value or the lead description, which the caller anchors at the start instead.
     */
    data class SeedFact(val field: String, val value: String, val chapter: Int?)

    /** Everything worth seeding from one article. [name] is the article title as the wiki spells it. */
    data class SeedCharacter(
        val name: String,
        val facts: List<SeedFact>,
        /** Citations found past the cutoff — surfaced in the UI as "N spoilers withheld", which is
         *  the honest way to show that the filter did something rather than that the wiki was thin. */
        val withheld: Int
    )

    // ── policy ────────────────────────────────────────────────────────────

    /** Infobox parameters that are a spoiler *by their very presence* — no citation can redeem
     *  "cause of death", because reading the field name already tells you the character dies. */
    private val SPOILER_PARAMS = setOf(
        "vital_status", "status", "death_chap", "death_chapter", "cause_of_death", "death",
        "died", "deceased", "fate", "killed_by", "killedby", "last_appearance", "lastappearance",
        "final_appearance", "novel", "manga", "anime", "debut"
    )

    /** Parameters allowed through without a citation: true from the character's first appearance,
     *  so there is no chapter at which they would have been a spoiler. */
    private val SAFE_UNCITED = setOf(
        "gender", "race", "species", "hair_color", "hair", "eye_color", "eyes", "skin_color",
        "height", "age", "birth", "nationality", "home", "homeworld"
    )

    /** Wiki parameter (digits stripped) → companion field name, using the `group:Label` convention
     *  [com.betteraudio.ui.companion.EntitySheetModel] renders. `true` = a multi-value list. */
    private val FIELD_MAP: Map<String, Pair<String, Boolean>> = mapOf(
        "alias" to ("identity:Also known as" to true),
        "aliases" to ("identity:Also known as" to true),
        "nickname" to ("identity:Also known as" to true),
        "title" to ("identity:Titles" to true),
        "titles" to ("identity:Titles" to true),
        "true_name" to ("identity:True name" to false),
        "race" to ("identity:Race" to false),
        "species" to ("identity:Race" to false),
        "gender" to ("identity:Gender" to false),
        "age" to ("identity:Age" to false),
        "hair_color" to ("identity:Hair" to false),
        "hair" to ("identity:Hair" to false),
        "eye_color" to ("identity:Eyes" to false),
        "eyes" to ("identity:Eyes" to false),
        "height" to ("identity:Height" to false),
        "nationality" to ("identity:Nationality" to false),
        "occupation" to ("identity:Role" to true),
        "role" to ("identity:Role" to true),
        "affiliation" to ("identity:Affiliation" to true),
        "affiliations" to ("identity:Affiliation" to true),
        "rank" to ("identity:Rank" to false),
        "class" to ("identity:Class" to false),
        "core" to ("identity:Soul cores" to false),
        "soul_sea" to ("identity:Soul sea" to false),
        "aspect" to ("power:Aspect" to false),
        "aspect_rank" to ("power:Aspect rank" to false),
        "aspect_abilities" to ("power:Aspect abilities" to true),
        "aspect_legacy" to ("power:Aspect legacy" to false),
        "innate_ability" to ("power:Innate ability" to false),
        "flaw" to ("power:Flaw" to false),
        "attributes" to ("power:Attributes" to true),
        "sorcery" to ("power:Sorcery" to false),
        "abilities" to ("power:Abilities" to true),
        "powers" to ("power:Abilities" to true),
        "magic" to ("power:Abilities" to true),
        "weapon" to ("arsenal:Weapons" to true),
        "weapons" to ("arsenal:Weapons" to true),
        "memories" to ("arsenal:Memories" to true),
        "shadows" to ("arsenal:Shadows" to true),
        "equipment" to ("arsenal:Equipment" to true),
        "relatives" to ("bond:Family" to true),
        "family" to ("bond:Family" to true),
        "friends" to ("bond:Friends" to true),
        "allies" to ("bond:Allies" to true),
        "enemies" to ("bond:Enemies" to true),
        "love_interest" to ("bond:Love interest" to true),
        "teacher" to ("bond:Teacher" to false),
        "master" to ("bond:Master" to false)
    )

    /**
     * Truncates to [MAX_VALUE_LENGTH] at the last list separator (or failing that, the last word
     * break) so a clipped value ends on a whole item — "…Café & Memory Boutique" rather than
     * "…Advanced S", which reads as corrupted data rather than as a long list.
     */
    private fun clip(value: String): String {
        if (value.length <= MAX_VALUE_LENGTH) return value
        val head = value.take(MAX_VALUE_LENGTH)
        val cut = head.lastIndexOf(", ").takeIf { it > MAX_VALUE_LENGTH / 2 }
            ?: head.lastIndexOf(' ').takeIf { it > MAX_VALUE_LENGTH / 2 }
            ?: head.length
        return head.take(cut).trimEnd(',', ' ') + "…"
    }

    /** Longest a single seeded value may get. Long enough for a real list, short enough that no
     *  meaningful amount of wiki prose is copied into a shareable pack (see §9.4's licensing note). */
    private const val MAX_VALUE_LENGTH = 300

    /** Cap on how many times one field may revise itself, so a character with 15 cited aliases
     *  contributes a readable history instead of 15 near-identical facts. */
    private const val MAX_REVISIONS_PER_FIELD = 6

    // ── citations ─────────────────────────────────────────────────────────

    private val CHAPTER_CITE = Regex("""[Cc]hapter\s+(\d{1,4})""")

    /** `<ref name=":1285" />` — a convention where the ref is named after the chapter it cites,
     *  which is the only citation a *reused* ref carries at its use site. */
    private val NAMED_REF_CHAPTER = Regex("""<ref\s+name\s*=\s*"?:(\d{1,4})"?""")

    /** Every chapter number cited anywhere in [fragment]. */
    fun chaptersIn(fragment: String): List<Int> {
        val out = LinkedHashSet<Int>()
        CHAPTER_CITE.findAll(fragment).forEach { out += it.groupValues[1].toInt() }
        NAMED_REF_CHAPTER.findAll(fragment).forEach { out += it.groupValues[1].toInt() }
        return out.toList()
    }

    // ── markup ────────────────────────────────────────────────────────────

    private val REF_SELF_CLOSING = Regex("""<ref[^>]*/>""")
    private val REF_PAIR = Regex("""<ref.*?</ref>""", RegexOption.DOT_MATCHES_ALL)
    private val GALLERY = Regex("""<gallery.*?</gallery>""", RegexOption.DOT_MATCHES_ALL)
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val WRAPPER_TEMPLATE = Regex(
        """\{\{\s*(scrollbox|scroll box|collapse|collapsible|hidden)\s*\|\s*(content\s*=)?""",
        RegexOption.IGNORE_CASE
    )
    private val DIV = Regex("""</?div[^>]*>""")
    private val ASIDE_TEMPLATE = Regex("""\{\{c\|.*?\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val QUOTE_TEMPLATE = Regex("""\{\{\s*quote\|.*?\}\}""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val SIMPLE_TEMPLATE = Regex("""\{\{[^{}]*\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val PIPED_LINK = Regex("""\[\[[^\]|]*\|([^\]]*)\]\]""")
    private val PLAIN_LINK = Regex("""\[\[([^\]]*)\]\]""")
    private val INLINE_TAG = Regex("""</?(s|b|i|u|small|span|div|br|sup|sub|nowiki|poem|code)[^>]*>""")
    private val WHITESPACE = Regex("""\s+""")
    private val STRANDED_PUNCTUATION = Regex("""\s+([,;:.])""")

    /**
     * Removes wrappers that are presentation only. Split out from [stripMarkup] because it has to
     * run *before* a value is split into bullet lines: `{{ScrollBox|content=` opens on one line and
     * closes many lines later, so splitting first would leave every line holding an unbalanced
     * template fragment that no amount of later cleanup can attribute correctly.
     */
    fun unwrap(text: String): String = text
        .replace(GALLERY, "")
        .replace(COMMENT, "")
        .replace(WRAPPER_TEMPLATE, "")
        .replace(DIV, "\n")

    /** Wikitext → plain text. */
    fun stripMarkup(text: String): String {
        var s = text
        s = s.replace(REF_SELF_CLOSING, "").replace(REF_PAIR, "")
        s = unwrap(s)
        s = s.replace(ASIDE_TEMPLATE, "").replace(QUOTE_TEMPLATE, "")
        repeat(3) { s = s.replace(SIMPLE_TEMPLATE, "") }
        // Whatever braces survive are halves of a wrapper the line split separated from its mate.
        s = s.replace("{{", "").replace("}}", "")
        s = s.replace(PIPED_LINK, "$1").replace(PLAIN_LINK, "$1")
        s = s.replace(INLINE_TAG, " ")
        s = s.replace("'''''", "").replace("'''", "").replace("''", "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
        s = s.replace(WHITESPACE, " ")
        // Removing <small>''(or Sunny)''</small> leaves the comma that followed it stranded.
        s = s.replace(STRANDED_PUNCTUATION, "$1")
        return s.trim().trim(*" *,;:|=".toCharArray()).trim()
    }

    // ── infobox ───────────────────────────────────────────────────────────

    /** [body] is the template's inside; [endIndex] is where it closed, so the caller can take the
     *  lead prose that follows it. */
    data class Infobox(val body: String, val endIndex: Int)

    /**
     * Finds the first `{{... infobox ...}}` template and returns its body, brace-balanced so a
     * nested `{{ScrollBox}}` doesn't end the scan early.
     */
    fun findInfobox(wikitext: String): Infobox? {
        var i = wikitext.indexOf("{{")
        while (i != -1) {
            val head = wikitext.substring(i + 2, minOf(i + 80, wikitext.length)).lowercase()
            if (head.contains("infobox")) {
                var depth = 0
                var j = i
                while (j < wikitext.length) {
                    when {
                        wikitext.startsWith("{{", j) -> { depth++; j += 2 }
                        wikitext.startsWith("}}", j) -> {
                            depth--; j += 2
                            if (depth == 0) return Infobox(wikitext.substring(i + 2, j - 2), j)
                        }
                        else -> j++
                    }
                }
                return Infobox(wikitext.substring(i + 2), wikitext.length)
            }
            i = wikitext.indexOf("{{", i + 2)
        }
        return null
    }

    /** Splits an infobox body on `|` at brace/bracket depth 0, so `[[A|B]]` and nested templates
     *  stay in one piece. */
    fun splitTopLevel(body: String): List<String> {
        val parts = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        var i = 0
        while (i < body.length) {
            val two = if (i + 1 < body.length) body.substring(i, i + 2) else ""
            when {
                two == "{{" || two == "[[" -> { depth++; buf.append(two); i += 2 }
                two == "}}" || two == "]]" -> { depth = maxOf(0, depth - 1); buf.append(two); i += 2 }
                body[i] == '|' && depth == 0 -> { parts.add(buf.toString()); buf.setLength(0); i++ }
                else -> { buf.append(body[i]); i++ }
            }
        }
        parts.add(buf.toString())
        return parts
    }

    // ── the parse ─────────────────────────────────────────────────────────

    private val BULLET_SPLIT = Regex("""\n\s*\*+|<br\s*/?>""")
    private val TRAILING_DIGITS = Regex("""\d+$""")
    private val SENTENCE_SPLIT = Regex("""(?<=\.)\s+""")

    private data class GatedLine(val text: String, val chapter: Int?)

    /**
     * Splits one infobox value into bullet lines and drops those first cited past [cutoffChapter].
     * Gating per *line* rather than per value is what makes a 15-entry alias list usable: the
     * entries earned at chapter 1 and chapter 2455 are individually datable, and only the second
     * is a spoiler.
     */
    private fun gateLines(
        value: String,
        cutoffChapter: Int,
        allowUncited: Boolean
    ): Pair<List<GatedLine>, Int> {
        val kept = mutableListOf<GatedLine>()
        var withheld = 0
        for (raw in unwrap(value).split(BULLET_SPLIT)) {
            if (raw.isBlank()) continue
            val earliest = chaptersIn(raw).minOrNull()
            if (earliest == null) {
                // An uncited line inside an otherwise-cited list is undatable, and undatable is
                // not the same as safe: the live Shadow Slave article lists "Lord of Shadows"
                // among Sunny's aliases with no ref at all, and letting it through on the
                // strength of its *neighbours* being cited handed a chapter-1308 listener the
                // single biggest reveal in the book. Only a parameter that cannot be a spoiler
                // at any point in the story ([SAFE_UNCITED]) may skip the gate.
                if (allowUncited) kept.add(GatedLine(stripMarkup(raw), null).takeIf { it.text.length > 1 } ?: continue)
                else withheld++
                continue
            }
            if (earliest > cutoffChapter) {
                withheld++
                continue
            }
            val text = stripMarkup(raw)
            if (text.length > 1) kept.add(GatedLine(text, earliest))
        }
        return kept to withheld
    }

    /**
     * Builds the revision history of one list field: a fact at each distinct citation chapter
     * carrying the value *as it stood then*. This is the whole reason the seed is worth anchoring
     * at all — the alias list genuinely grows across the story, and emitting it as one fact at one
     * chapter would either spoil later entries or hide earlier ones.
     *
     * Uncited lines join from the first revision (nothing dates them later).
     */
    private fun revisionsFor(field: String, lines: List<GatedLine>, isList: Boolean): List<SeedFact> {
        if (lines.isEmpty()) return emptyList()
        if (!isList) {
            val best = lines.first()
            return listOf(SeedFact(field, clip(best.text), best.chapter))
        }
        val undated = lines.filter { it.chapter == null }
        val dated = lines.filter { it.chapter != null }.sortedBy { it.chapter }
        if (dated.isEmpty()) {
            val value = undated.map { it.text }.distinct().joinToString(", ")
            return listOf(SeedFact(field, clip(value), null))
        }
        val out = mutableListOf<SeedFact>()
        var previous: String? = null
        for (chapter in dated.mapNotNull { it.chapter }.distinct()) {
            val soFar = undated.map { it.text } + dated.filter { it.chapter!! <= chapter }.map { it.text }
            val value = clip(soFar.distinct().joinToString(", "))
            if (value == previous || value.isEmpty()) continue
            previous = value
            out.add(SeedFact(field, value, chapter))
        }
        // Keep the first revision (when the field first became true) plus the most recent ones.
        return if (out.size <= MAX_REVISIONS_PER_FIELD) out
        else listOf(out.first()) + out.takeLast(MAX_REVISIONS_PER_FIELD - 1)
    }

    /**
     * Parses one article's lead section.
     *
     * @param cutoffChapter the last chapter the listener has heard. Everything cited later is
     *   withheld.
     * @param sourceUrl recorded as a `source` fact — attribution, since Fandom text is CC BY-SA.
     */
    fun parseLead(
        title: String,
        wikitext: String,
        cutoffChapter: Int,
        sourceUrl: String? = null
    ): SeedCharacter {
        val facts = mutableListOf<SeedFact>()
        val claimed = mutableSetOf<String>()
        var withheld = 0

        val infobox = findInfobox(wikitext)
        if (infobox != null) {
            for (part in splitTopLevel(infobox.body)) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val key = part.substring(0, eq).trim().lowercase()
                val value = part.substring(eq + 1)
                // `rank1`/`rank3` are the same field shown on different article tabs — one field.
                val base = key.replace(TRAILING_DIGITS, "")
                if (key in SPOILER_PARAMS || base in SPOILER_PARAMS) continue
                val (field, isList) = FIELD_MAP[base] ?: FIELD_MAP[key] ?: continue
                val allowUncited = base in SAFE_UNCITED || key in SAFE_UNCITED
                val cited = chaptersIn(value).isNotEmpty()
                if (!cited && !allowUncited) continue
                val (lines, skipped) = gateLines(value, cutoffChapter, allowUncited)
                withheld += skipped
                if (lines.isEmpty()) continue
                // Numbered variants map onto one field; the first one to produce a value wins.
                if (!claimed.add(field)) continue
                facts += revisionsFor(field, lines, isList)
            }
        }

        val tail = if (infobox != null) wikitext.substring(infobox.endIndex) else wikitext
        val (description, descriptionWithheld) = leadDescription(tail, cutoffChapter)
        withheld += descriptionWithheld
        if (description != null) facts.add(SeedFact("description", description, null))
        if (sourceUrl != null) facts.add(SeedFact("source", sourceUrl, null))

        return SeedCharacter(name = title, facts = facts, withheld = withheld)
    }

    /**
     * The opening sentences of the article, gated the same way — a lead paragraph routinely ends
     * with a sentence about how the character's arc resolves.
     */
    private fun leadDescription(tail: String, cutoffChapter: Int): Pair<String?, Int> {
        var withheld = 0
        val acc = mutableListOf<String>()
        // Split the RAW text so each sentence keeps the <ref> that dates it.
        for (sentence in tail.split(SENTENCE_SPLIT)) {
            if (sentence.isBlank()) continue
            if (sentence.trimStart().startsWith("==")) break // past the lead into a real section
            val earliest = chaptersIn(sentence).minOrNull()
            if (earliest != null && earliest > cutoffChapter) {
                withheld++
                continue
            }
            val clean = stripMarkup(sentence)
            if (clean.isEmpty()) continue
            acc.add(clean)
            if (acc.sumOf { it.length } > 240) break
        }
        val text = acc.joinToString(" ").take(320).trim()
        return (if (text.length > 20) text else null) to withheld
    }
}
