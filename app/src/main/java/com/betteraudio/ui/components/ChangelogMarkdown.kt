package com.betteraudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * The changelog's own markdown, parsed and rendered — shared by every surface that shows release
 * notes, so the same entry looks the same wherever it appears: Settings → About (the bundled
 * `res/raw/changelog_*.txt`), Settings → Updates' "latest release" card, and the update prompt
 * shown at launch (`ui/update/UpdateAvailableScreen`), the last two both fed the GitHub release
 * body — which is that same changelog entry, published verbatim.
 *
 * Deliberately not a general markdown library: this renders exactly the shapes the changelog
 * actually uses (`## [version] - date`, `### Category`, `- ` bullets, free-text summary
 * paragraphs, `**bold**`/`*italic*`/`` `code` `` inline) and passes anything else through as
 * plain text. A malformed entry degrades to readable text rather than to an exception.
 */

// ── Parsed model ─────────────────────────────────────────────────────────────

/** One `### Category` and its bullets. */
data class ChangelogCategory(val name: String, val entries: List<String>)

/**
 * Everything under one version heading: the leading [summary] paragraphs, then the [categories].
 *
 * [summary] is the low-detail opening tier every entry carries — free text between the version
 * heading and the first `###`. It is parsed and shown, not skipped: it is the part a reader
 * deciding whether to tap Install is meant to read first.
 */
data class ChangelogBody(val summary: List<String>, val categories: List<ChangelogCategory>) {
    val isEmpty: Boolean get() = summary.isEmpty() && categories.isEmpty()
}

data class ChangelogEntry(val version: String, val date: String, val body: ChangelogBody)

// ── Parsing ──────────────────────────────────────────────────────────────────

/**
 * Parse a single entry's body — no `## [version]` heading involved. This is the shape a GitHub
 * release body has, since the notes published for a release are one changelog entry with its
 * heading stripped.
 */
fun parseChangelogBody(raw: String): ChangelogBody {
    val summary = mutableListOf<String>()
    val categories = mutableListOf<ChangelogCategory>()
    var catName: String? = null
    var entries = mutableListOf<String>()
    val paragraph = StringBuilder()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            summary.add(paragraph.toString().trim())
            paragraph.clear()
        }
    }
    fun flushCategory() {
        if (catName != null && entries.isNotEmpty()) categories.add(ChangelogCategory(catName!!, entries.toList()))
        catName = null
        entries = mutableListOf()
    }

    raw.lineSequence().forEach { line ->
        val t = line.trim()
        when {
            t.startsWith("### ") -> {
                flushParagraph()
                flushCategory()
                catName = t.removePrefix("### ").trim()
            }
            t.startsWith("- ") -> entries.add(t.removePrefix("- ").trim())
            // A blank line ends whatever was accumulating: a summary paragraph, or a bullet's
            // wrapped continuation. Without this every summary paragraph would run together.
            t.isEmpty() -> flushParagraph()
            t.startsWith("#") -> { /* a stray heading level this renderer doesn't style */ }
            // Continuation of the previous wrapped bullet…
            entries.isNotEmpty() -> entries[entries.lastIndex] = entries.last() + " " + t
            // …or, before any category has opened, a line of the summary.
            catName == null -> {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(t)
            }
        }
    }
    flushParagraph()
    flushCategory()
    return ChangelogBody(summary.toList(), categories.toList())
}

/**
 * Parse a whole Keep-a-Changelog file into per-version entries, newest first (document order).
 * Versions with an empty body — `## [Unreleased]` with nothing under it — are dropped.
 */
fun parseChangelog(raw: String): List<ChangelogEntry> {
    val out = mutableListOf<ChangelogEntry>()
    var version: String? = null
    var date = ""
    val chunk = StringBuilder()

    fun flush() {
        val v = version ?: return
        val body = parseChangelogBody(chunk.toString())
        if (!body.isEmpty) out.add(ChangelogEntry(v, date, body))
        chunk.clear()
    }

    raw.lineSequence().forEach { line ->
        val t = line.trim()
        if (t.startsWith("## ")) {
            flush()
            // "## [1.5.0b] - 2026-06-27"
            version = Regex("""\[(.+?)]""").find(t)?.groupValues?.get(1) ?: t.removePrefix("## ").trim()
            date = t.substringAfter("] -", "").trim().ifBlank { t.substringAfter(" - ", "").trim() }
        } else if (version != null) {
            chunk.appendLine(line)
        }
    }
    flush()
    return out
}

/**
 * Inline markdown → styled text: `**bold**`, `*italic*`, `` `code` ``. An unclosed marker is
 * emitted literally rather than swallowing the rest of the line, so a stray asterisk in prose
 * can never blank out an entry.
 */
fun inlineMarkdown(raw: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < raw.length) {
        when {
            raw.startsWith("**", i) -> {
                val end = raw.indexOf("**", i + 2)
                if (end < 0) { append(raw.substring(i)); i = raw.length }
                else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(raw.substring(i + 2, end)) }
                    i = end + 2
                }
            }
            raw[i] == '*' -> {
                val end = raw.indexOf('*', i + 1)
                if (end < 0) { append(raw.substring(i)); i = raw.length }
                else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            raw[i] == '`' -> {
                val end = raw.indexOf('`', i + 1)
                if (end < 0) { append(raw.substring(i)); i = raw.length }
                else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(raw.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            else -> {
                // Run of plain text up to the next marker — appended in one go so the common
                // case doesn't build the string a character at a time.
                val next = (i + 1 until raw.length).firstOrNull { raw[it] == '*' || raw[it] == '`' } ?: raw.length
                append(raw.substring(i, next))
                i = next
            }
        }
    }
}

// ── Rendering ────────────────────────────────────────────────────────────────

/**
 * One entry's body: summary paragraphs, then a block per category. [textStyle]/[headingStyle] let
 * a caller size it for its surface — the update prompt gives it a full screen, the Settings cards
 * a good deal less — without any of them re-deciding what the structure is.
 */
@Composable
fun ChangelogBodyView(
    body: ChangelogBody,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodySmall,
    headingStyle: TextStyle = MaterialTheme.typography.labelMedium,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        body.summary.forEach { para ->
            Text(inlineMarkdown(para), style = textStyle, color = textColor)
        }
        body.categories.forEach { cat ->
            CategoryBlock(cat, textStyle = textStyle, headingStyle = headingStyle, textColor = textColor)
        }
    }
}

@Composable
private fun CategoryBlock(
    cat: ChangelogCategory,
    textStyle: TextStyle,
    headingStyle: TextStyle,
    textColor: Color,
) {
    val (icon, tint) = categoryStyle(cat.name)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(cat.name.uppercase(), style = headingStyle, fontWeight = FontWeight.Bold, color = tint)
        }
        cat.entries.forEach { entry ->
            Row(Modifier.padding(start = 4.dp)) {
                Box(
                    Modifier.padding(top = 7.dp).size(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(Modifier.width(8.dp))
                Text(inlineMarkdown(entry), style = textStyle, color = textColor)
            }
        }
    }
}

@Composable
private fun categoryStyle(name: String): Pair<ImageVector, Color> = when {
    name.startsWith("Add", true)   -> Icons.Default.Add to MaterialTheme.colorScheme.primary
    name.startsWith("Chang", true) -> Icons.Default.Edit to MaterialTheme.colorScheme.tertiary
    name.startsWith("Fix", true)   -> Icons.Default.BugReport to MaterialTheme.colorScheme.error
    name.startsWith("Secur", true) -> Icons.Default.Lock to MaterialTheme.colorScheme.error
    else                           -> Icons.Default.Edit to MaterialTheme.colorScheme.onSurfaceVariant
}
