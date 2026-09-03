package com.betteraudio.ui.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.companion.fandom.ChapterNumbering
import com.betteraudio.companion.fandom.FandomSeedService

/**
 * "Pregenerate from wiki" (docs/companion-packs.md §9.4) — fetches a draft cast from the book's
 * Fandom community so a new pack starts with something in it.
 *
 * Three things this dialog deliberately puts in front of the listener rather than deciding for
 * them:
 *  - **The chapter cutoff**, because it is the spoiler boundary and the detection behind it can be
 *    wrong (a book whose chapters aren't numbered gives no answer at all). It is pre-filled and
 *    editable, never hidden.
 *  - **The wiki**, since Fandom retired its cross-wiki search and the subdomain is a guess.
 *  - **Which characters**, ticked individually — the whole result is a draft to be edited, and
 *    the review step is where that starts.
 */
@Composable
fun FandomSeedDialog(
    state: CompanionAuthorViewModel.SeedState,
    onFetch: (slug: String, cutoffChapter: Int, limit: Int) -> Unit,
    onToggle: (String) -> Unit,
    onToggleMap: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    val detected = state.detected
    var slug by remember(detected) { mutableStateOf(detected?.wiki?.slug.orEmpty()) }
    var chapter by remember(detected) {
        mutableStateOf(detected?.cutoffChapter?.toString().orEmpty())
    }
    var limit by remember { mutableIntStateOf(10) }
    val chapterNumber = chapter.trim().toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state.applied != null) "Cast added" else "Pregenerate cast") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when {
                    state.applied != null -> Text(
                        "Added ${state.applied} fact${if (state.applied == 1) "" else "s"}" +
                            (if (state.appliedImages > 0)
                                " and ${state.appliedImages} image${if (state.appliedImages == 1) "" else "s"}"
                             else "") +
                            " to the pack. Everything is a draft — edit or delete anything that isn't right."
                    )

                    state.preview != null -> PreviewBody(
                        preview = state.preview,
                        selected = state.selected,
                        includeMap = state.includeMap,
                        onToggle = onToggle,
                        onToggleMap = onToggleMap
                    )

                    else -> SetupBody(
                        detected = detected,
                        slug = slug,
                        onSlug = { slug = it },
                        chapter = chapter,
                        onChapter = { chapter = it.filter { c -> c.isDigit() }.take(4) },
                        limit = limit,
                        onLimit = { limit = it }
                    )
                }

                if (state.error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (state.loading) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading the wiki…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.applied != null -> TextButton(onClick = onDismiss) { Text("Done") }
                state.preview != null -> TextButton(
                    onClick = onApply,
                    enabled = !state.loading && state.selected.isNotEmpty()
                ) { Text("Add ${state.selected.size}") }
                else -> TextButton(
                    onClick = { onFetch(slug, chapterNumber ?: 0, limit) },
                    enabled = !state.loading && slug.isNotBlank() && chapterNumber != null
                ) { Text("Fetch") }
            }
        },
        dismissButton = {
            if (state.applied == null) TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SetupBody(
    detected: FandomSeedService.Detected?,
    slug: String,
    onSlug: (String) -> Unit,
    chapter: String,
    onChapter: (String) -> Unit,
    limit: Int,
    onLimit: (Int) -> Unit
) {
    Text(
        text = "Pulls the main characters from a Fandom wiki and adds them as a draft you can edit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = slug,
        onValueChange = onSlug,
        label = { Text("Wiki") },
        supportingText = {
            Text(detected?.wiki?.siteName ?: "the part before .fandom.com")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = chapter,
        onValueChange = onChapter,
        label = { Text("I'm at chapter") },
        supportingText = {
            Text(
                when (detected?.positioning) {
                    ChapterNumbering.Kind.NUMBERED ->
                        "Nothing the wiki cites after this chapter will be included."
                    ChapterNumbering.Kind.ORDINAL ->
                        "Chapters aren't numbered in this book's titles — counted in order instead."
                    else ->
                        "This book has no chapter list, so facts will be placed where you're listening now."
                }
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))

    Text("How many characters", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(5, 10, 20).forEach { option ->
            FilterChip(
                selected = limit == option,
                onClick = { onLimit(option) },
                label = { Text("$option") }
            )
        }
    }
}

@Composable
private fun PreviewBody(
    preview: FandomSeedService.Preview,
    selected: Set<String>,
    includeMap: Boolean,
    onToggle: (String) -> Unit,
    onToggleMap: () -> Unit
) {
    Text(
        text = "${preview.characters.size} from ${preview.wiki.siteName} · ${preview.factCount} facts" +
            if (preview.portraitCount > 0) " · ${preview.portraitCount} portraits" else "",
        style = MaterialTheme.typography.bodyMedium
    )
    if (preview.withheldCount > 0) {
        Text(
            text = "${preview.withheldCount} withheld as spoilers past chapter ${preview.cutoffChapter}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(8.dp))

    // The map sits above the cast rather than beside it: it is one thing, it is the pack's
    // backdrop rather than a member of the cast, and putting it in the same ticked list would
    // make "Add 7" ambiguous about whether the seventh is a person.
    preview.map?.let { map ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = includeMap, onCheckedChange = { onToggleMap() })
            SeedThumb(map.url, wide = true)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = "Map",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${map.width}×${map.height} · becomes the map board",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
    }

    preview.characters.forEach { candidate ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = candidate.name in selected,
                onCheckedChange = { onToggle(candidate.name) }
            )
            candidate.portrait?.let { SeedThumb(it.url, wide = false) }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = candidate.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = summarise(candidate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A thumbnail of what the wiki is offering, loaded straight from its URL.
 *
 * Worth the network round trip in a review dialog: the whole point of this step is that the seed
 * is a draft to be judged, and "does this picture belong to this character" is a judgement nobody
 * can make from a filename. Nothing is written to the pack until Add is tapped.
 */
@Composable
private fun SeedThumb(url: String, wide: Boolean) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(start = 2.dp)
            .size(width = if (wide) 52.dp else 34.dp, height = 34.dp)
            .clip(if (wide) RoundedCornerShape(6.dp) else RoundedCornerShape(percent = 50))
    )
}

/** "8 facts · Rank, Titles, Aspect" — enough to judge whether a row is worth keeping. */
private fun summarise(candidate: FandomSeedService.Candidate): String {
    val labels = candidate.facts
        .map { it.field.substringAfter(':') }
        .filter { it != "description" && it != "source" }
        .distinct()
        .take(3)
    val head = "${candidate.facts.size} fact${if (candidate.facts.size == 1) "" else "s"}"
    return if (labels.isEmpty()) head else "$head · ${labels.joinToString(", ")}"
}
