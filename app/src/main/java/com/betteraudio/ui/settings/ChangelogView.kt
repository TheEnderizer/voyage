package com.betteraudio.ui.settings

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ── Parsed model ─────────────────────────────────────────────────────────────
data class ChangelogEntry(val version: String, val date: String, val categories: List<ChangelogCategory>)
data class ChangelogCategory(val name: String, val entries: List<String>)

/**
 * Parse a Keep-a-Changelog body: `## [x.y.z] - date` version headers, `### Added/Changed/...`
 * category headers, and `- ` bullet entries. Lines that wrap across multiple source lines are
 * joined back into one entry. Returns versions in document order (newest first).
 */
fun parseChangelog(raw: String): List<ChangelogEntry> {
    val versions = mutableListOf<ChangelogEntry>()
    var curVersion: String? = null
    var curDate = ""
    var curCategories = mutableListOf<ChangelogCategory>()
    var curCatName: String? = null
    var curEntries = mutableListOf<String>()

    fun flushCategory() {
        if (curCatName != null && curEntries.isNotEmpty()) {
            curCategories.add(ChangelogCategory(curCatName!!, curEntries.toList()))
        }
        curCatName = null
        curEntries = mutableListOf()
    }
    fun flushVersion() {
        flushCategory()
        if (curVersion != null) {
            versions.add(ChangelogEntry(curVersion!!, curDate, curCategories.toList()))
        }
        curVersion = null; curDate = ""; curCategories = mutableListOf()
    }

    raw.lineSequence().forEach { line ->
        val t = line.trim()
        when {
            t.startsWith("## ") -> {
                flushVersion()
                // "## [1.5.0b] - 2026-06-27"
                val ver = Regex("""\[(.+?)]""").find(t)?.groupValues?.get(1) ?: t.removePrefix("## ").trim()
                val date = t.substringAfter("] -", "").trim().ifBlank { t.substringAfter(" - ", "").trim() }
                curVersion = ver
                curDate = date
            }
            t.startsWith("### ") -> {
                flushCategory()
                curCatName = t.removePrefix("### ").trim()
            }
            t.startsWith("- ") -> curEntries.add(t.removePrefix("- ").trim())
            t.isEmpty() -> { /* keep grouping */ }
            curEntries.isNotEmpty() && !t.startsWith("#") -> {
                // continuation of the previous wrapped bullet
                curEntries[curEntries.lastIndex] = curEntries.last() + " " + t
            }
        }
    }
    flushVersion()
    return versions
}

@Composable
fun ChangelogView(raw: String, modifier: Modifier = Modifier, maxVersions: Int = 6) {
    val versions = remember(raw) { parseChangelog(raw) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        versions.take(maxVersions).forEach { v -> VersionCard(v) }
    }
}

@Composable
private fun VersionCard(v: ChangelogEntry) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                    Text(
                        v.version,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (v.date.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(v.date, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            v.categories.forEach { cat -> CategoryBlock(cat) }
        }
    }
}

@Composable
private fun CategoryBlock(cat: ChangelogCategory) {
    val (icon, tint) = categoryStyle(cat.name)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(16.dp), tint = tint)
            Spacer(Modifier.width(6.dp))
            Text(cat.name.uppercase(), style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold, color = tint)
        }
        cat.entries.forEach { entry ->
            Row(Modifier.padding(start = 4.dp)) {
                Box(
                    Modifier.padding(top = 7.dp).size(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant)
                )
                Spacer(Modifier.width(8.dp))
                Text(entry, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun categoryStyle(name: String): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when {
    name.startsWith("Add", true)     -> Icons.Default.Add to MaterialTheme.colorScheme.primary
    name.startsWith("Chang", true)   -> Icons.Default.Edit to MaterialTheme.colorScheme.tertiary
    name.startsWith("Fix", true)     -> Icons.Default.BugReport to MaterialTheme.colorScheme.error
    name.startsWith("Secur", true)   -> Icons.Default.Lock to MaterialTheme.colorScheme.error
    else                             -> Icons.Default.Edit to MaterialTheme.colorScheme.onSurfaceVariant
}
