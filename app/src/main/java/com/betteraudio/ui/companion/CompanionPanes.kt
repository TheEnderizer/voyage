package com.betteraudio.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.companion.CompanionEntityState
import com.betteraudio.companion.RevealDigest
import com.betteraudio.companion.model.PackScrap
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.PressFeel

/**
 * The deck's panes — band 3 (docs/companion-redesign.html §02).
 *
 * All four are theme-neutral: every fill, shape and text colour they use comes from
 * [CompanionDeckStyle], so there is exactly one implementation of "what a cast grid is" and the
 * two looks cannot drift. That is the lesson `InfoPageScaffold` already banked for Book Info and
 * the Series page.
 *
 * Each pane takes the whole deck state rather than a hand-picked slice. That is deliberate at this
 * size: a pane is a private leaf of one screen, not a reusable component, and threading eleven
 * parameters through so it can pretend otherwise buys nothing.
 */

// ── Cast ────────────────────────────────────────────────────────────────────────────────────

/**
 * The landing pane: everyone the reveal cursor has let through, as faces.
 *
 * A grid rather than the old horizontal `CastStrip` because a strip answers "who is on screen
 * right now" and a cast answers "who is in this story" — the second question needs everyone
 * visible at once, and a rail of nine forces you to scrub to find someone you already met.
 *
 * [GridCells.Adaptive] rather than a fixed three columns: the same pane is the right half of the
 * landscape deck, where three columns of 96dp would leave half the width empty.
 */
@Composable
fun CastPane(
    entities: List<CompanionEntityState>,
    sheets: Map<String, EntitySheetModel.Sheet>,
    packDir: String?,
    digest: List<RevealDigest.Item>,
    editing: Boolean,
    contentPadding: PaddingValues,
    onSelect: (String) -> Unit,
    onAddCharacter: () -> Unit,
    onGoToTimeline: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Which faces are new since the listener last looked. Straight from the digest rather than
    // from firstRevealedMs, so the dot means "you have not seen this yet", not "this is recent" —
    // the digest is already filtered by the receiver's own notify threshold.
    val fresh = remember(digest) { digest.map { it.entity.entityId }.toSet() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 92.dp),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (digest.isNotEmpty()) {
            item(key = "digest", span = { GridItemSpan(maxLineSpan) }) {
                DeckBanner(
                    title = "${digest.size} change${if (digest.size == 1) "" else "s"} since you last looked",
                    action = "Timeline",
                    onClick = onGoToTimeline
                )
            }
        }
        item(key = "count", span = { GridItemSpan(maxLineSpan) }) {
            // "Revealed" is a consumption word. In edit mode this list is the author's whole cast,
            // including people the reveal cursor is still hiding, so calling it "revealed" would
            // be a lie about the one thing this feature must never lie about.
            DeckLabel(if (editing) "Cast · ${entities.size}" else "Revealed · ${entities.size}")
        }
        gridItems(entities, key = { it.entity.entityId }) { state ->
            CastTile(
                state = state,
                subtitle = sheets[state.entity.entityId]?.subtitle
                    ?: state.entity.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                packDir = packDir,
                isNew = state.entity.entityId in fresh,
                onClick = { onSelect(state.entity.entityId) }
            )
        }
        if (editing) {
            item(key = "add") { AddTile(onClick = onAddCharacter) }
        }
    }
}

@Composable
private fun CastTile(
    state: CompanionEntityState,
    subtitle: String,
    packDir: String?,
    isNew: Boolean,
    onClick: () -> Unit
) {
    PressFeel(Feel.Select) {
        Column(
            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                CompanionPortrait(
                    state = state,
                    // The tile is width-driven, so the portrait is sized from the tile rather than
                    // the other way round — matchParentSize would fight the shape's corner radius,
                    // which is computed from the slot's own dp size.
                    size = 92.dp,
                    packDir = packDir,
                    modifier = Modifier.align(Alignment.Center)
                )
                if (isNew) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.entity.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = deckOn(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = deckOnMuted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** The one tile that only exists in edit mode — adding a character happens in the cast, not in a
 *  list of characters somewhere else. */
@Composable
private fun AddTile(onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(deckPortraitShape(92.dp))
                .background(deckCardColor()),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, "Add character", Modifier.size(26.dp), tint = deckOnMuted())
        }
        Spacer(Modifier.height(6.dp))
        Text("Add", style = MaterialTheme.typography.labelMedium, color = deckOnMuted())
    }
}

// ── Character ───────────────────────────────────────────────────────────────────────────────

/**
 * One character, pushed *inside* the Cast pane rather than opened as a fifth destination.
 *
 * That is what keeps back meaning one thing (§04): character → cast → close, one predictive-back
 * chain, no stacked modals to unwind.
 *
 * Bonds are tappable when the named character is themselves revealed, which turns this pane into
 * the relationship graph §3 of the pack spec describes without needing a GRAPH board.
 */
@Composable
fun CharacterPane(
    sheet: EntitySheetModel.Sheet,
    packDir: String?,
    entity: CompanionEntityState,
    editing: Boolean,
    contentPadding: PaddingValues,
    onSelectEntity: (String) -> Unit,
    onPickPortrait: () -> Unit,
    onAddFact: () -> Unit,
    onDeleteFact: (String) -> Unit,
    onDeleteEntity: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = entityAccent(sheet.name)
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    CompanionPortrait(entity = entity.entity, size = 72.dp, packDir = packDir)
                    // Edit mode puts the portrait control ON the portrait. There is no "media"
                    // field in a form anywhere; the way to change a face is to tap the face.
                    if (editing) {
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .size(26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(onClick = onPickPortrait),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Image, "Set portrait",
                                Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = sheet.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = deckOn()
                    )
                    Text(
                        text = sheet.subtitle
                            ?: sheet.kind.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = accent
                    )
                }
                if (editing) {
                    DeckIconAction(Icons.Default.Delete, "Remove character", onDeleteEntity)
                }
            }
        }

        if (sheet.badges.isNotEmpty()) {
            item(key = "badges") {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Badges carry their own delete in edit mode. They are the one part of the
                    // sheet that is not a row, so without this an `identity:Rank` fact would be
                    // the only value on the deck you could add but never remove where you see it.
                    for (badge in sheet.badges) {
                        StatPill(
                            label = badge.label,
                            value = badge.value,
                            onDelete = badge.factId
                                ?.takeIf { editing }
                                ?.let { id -> { onDeleteFact(id) } }
                        )
                    }
                }
            }
        }

        sheet.summary?.let { summary ->
            item(key = "summary") {
                Spacer(Modifier.height(12.dp))
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = deckOnMuted())
            }
        }

        for (section in sheet.sections) {
            item(key = "h-${section.title}") {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = section.title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
                Spacer(Modifier.height(6.dp))
            }
            items(section.entries, key = { "${section.title}-${it.label}" }) { entry ->
                EntryRow(entry, editing, onSelectEntity, onDeleteFact)
            }
        }

        if (editing) {
            item(key = "addFact") {
                Spacer(Modifier.height(18.dp))
                DeckActionRow(Icons.Default.Add, "Add a fact", onAddFact)
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: EntitySheetModel.Entry,
    editing: Boolean,
    onSelectEntity: (String) -> Unit,
    onDeleteFact: (String) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            when (entry) {
                is EntitySheetModel.Entry.Text -> Column(Modifier.padding(vertical = 5.dp)) {
                    Text(entry.label, style = MaterialTheme.typography.labelSmall, color = deckOnMuted())
                    Text(entry.value, style = MaterialTheme.typography.bodyMedium, color = deckOn())
                }
                is EntitySheetModel.Entry.Chips -> Column(Modifier.padding(vertical = 5.dp)) {
                    Text(
                        "${entry.label} · ${entry.values.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = deckOnMuted()
                    )
                    Spacer(Modifier.height(4.dp))
                    // Hand-wrapped rather than pulled in as a FlowRow dependency for one screen —
                    // see chunkToRows.
                    for (row in chunkToRows(entry.values)) {
                        Row(
                            Modifier.padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) { for (value in row) DeckChip(value) }
                    }
                }
                is EntitySheetModel.Entry.Bond -> {
                    val clickable = entry.entityId != null
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .then(
                                if (clickable) Modifier.clickable { onSelectEntity(entry.entityId!!) }
                                else Modifier
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompanionNamePortrait(entry.label, 34.dp, dim = !clickable)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = deckOn()
                            )
                            Text(entry.value, style = MaterialTheme.typography.bodySmall, color = deckOnMuted())
                        }
                    }
                }
            }
        }
        if (editing && entry.factId != null) {
            DeckIconAction(Icons.Default.Delete, "Delete ${entry.label}") { onDeleteFact(entry.factId!!) }
        }
    }
}

// ── Timeline ────────────────────────────────────────────────────────────────────────────────

/**
 * Where the reveal cursor lives, and where the digest badge lands.
 *
 * The old sheet dropped a listener who had been away for 40 minutes straight onto a cast grid with
 * no idea what had moved; the digest existed but had nowhere to be. Here it is a destination, so
 * "3 changes" answers itself.
 *
 * Quick capture (§9.1) sits at the top in edit mode rather than in a separate authoring surface:
 * capture is something you do *while listening*, which is when this pane is open.
 */
@Composable
fun TimelinePane(
    digest: List<RevealDigest.Item>,
    revealedMs: Long,
    bookPositionMs: Long,
    scraps: List<PackScrap>,
    editing: Boolean,
    contentPadding: PaddingValues,
    onCatchUp: () -> Unit,
    onQuickCapture: () -> Unit,
    onConvertScrap: (PackScrap) -> Unit,
    onDeleteScrap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val behind = bookPositionMs > revealedMs + CATCH_UP_MIN_GAP_MS
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item(key = "cursor") {
            DeckBanner(
                title = if (behind) "The companion is behind you" else "Revealed to ${formatDeckHm(revealedMs)}",
                subtitle = if (behind)
                    "Showing ${formatDeckHm(revealedMs)} of ${formatDeckHm(bookPositionMs)} listened"
                else null,
                action = if (behind) "Catch up" else null,
                onClick = onCatchUp
            )
        }
        if (editing) {
            item(key = "capture") {
                Spacer(Modifier.height(10.dp))
                DeckActionRow(Icons.Default.Add, "Capture a note here", onQuickCapture)
            }
            if (scraps.isNotEmpty()) {
                item(key = "scrapsLabel") {
                    Spacer(Modifier.height(14.dp))
                    DeckLabel("Captured notes · ${scraps.size}")
                }
                items(scraps, key = { it.scrapId }) { scrap ->
                    ScrapRow(scrap, onConvert = { onConvertScrap(scrap) }, onDelete = { onDeleteScrap(scrap.scrapId) })
                }
            }
        }
        item(key = "digestLabel") {
            Spacer(Modifier.height(14.dp))
            DeckLabel(if (digest.isEmpty()) "Nothing new" else "Since you last looked")
        }
        if (digest.isEmpty()) {
            item(key = "digestEmpty") {
                Text(
                    "Everything this pack knows up to here is already on the Cast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = deckOnMuted(),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
        }
        items(digest, key = { it.fact.factId }) { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(7.dp)
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.entity.name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = deckOn()
                    )
                    Text(
                        "${item.fact.field.substringAfter(':')} · ${item.fact.value}",
                        style = MaterialTheme.typography.bodySmall,
                        color = deckOnMuted()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDeckHm(item.resolvedMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = deckOnMuted()
                )
            }
        }
    }
}

@Composable
private fun ScrapRow(scrap: PackScrap, onConvert: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(deckPanelShape())
            .background(deckCardColor())
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                scrap.note.ifBlank { "(no note)" },
                style = MaterialTheme.typography.bodySmall,
                color = deckOn(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                formatDeckHm(scrap.anchor.globalMs ?: 0L),
                style = MaterialTheme.typography.labelSmall,
                color = deckOnMuted()
            )
        }
        DeckIconAction(Icons.Default.AutoAwesome, "Turn into a fact", onConvert)
        DeckIconAction(Icons.Default.Delete, "Delete note", onDelete)
    }
}

// ── Pack ────────────────────────────────────────────────────────────────────────────────────

/**
 * The pack itself: who made it, what revision it is, and everything that acts on the document as a
 * whole rather than on one character.
 *
 * These used to be the old editor sheet's header icons and scattered text buttons. Collecting them
 * behind a spine destination is what let the deck's own header shrink to three controls.
 */
@Composable
fun PackPane(
    state: CompanionViewModel.UiState,
    author: CompanionAuthorViewModel.UiState,
    contentPadding: PaddingValues,
    onCreatePack: () -> Unit,
    onPregenerate: () -> Unit,
    onShare: () -> Unit,
    onFork: () -> Unit,
    onCreateMap: () -> Unit,
    onDropConflict: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        if (!state.hasPack) {
            item(key = "none") {
                Text(
                    "No companion pack for this book yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = deckOnMuted()
                )
                Spacer(Modifier.height(14.dp))
                DeckActionRow(Icons.Default.Add, "Create an empty pack", onCreatePack)
                DeckActionRow(Icons.Default.AutoAwesome, "Pregenerate from a wiki", onPregenerate)
            }
            return@LazyColumn
        }

        item(key = "meta") {
            DeckLabel("Pack")
            Spacer(Modifier.height(8.dp))
            MetaRow("Title", state.pack?.title.orEmpty().ifBlank { "Untitled" })
            MetaRow("Revision", state.pack?.revision?.toString() ?: "—")
            MetaRow("Author", if (author.isOwn) "You" else "Received")
            MetaRow("Cast", (author.doc?.entities?.size ?: state.entities.size).toString())
        }

        if (author.conflicts.isNotEmpty()) {
            item(key = "conflictsLabel") {
                Spacer(Modifier.height(18.dp))
                DeckLabel("Needs review · ${author.conflicts.size}")
            }
            items(author.conflicts, key = { it.op.opId }) { conflict ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(deckPanelShape())
                        .background(deckCardColor())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(conflict.label, style = MaterialTheme.typography.bodyMedium, color = deckOn())
                        Text(
                            if (conflict.kind == com.betteraudio.companion.PackEditsApplier.ConflictKind.UPSTREAM_DELETED)
                                "Removed upstream — your change is kept, unattached"
                            else "Also changed upstream — keeping your version",
                            style = MaterialTheme.typography.labelSmall,
                            color = deckOnMuted()
                        )
                    }
                    Text(
                        "Take theirs",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDropConflict(conflict.op.opId) }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }

        item(key = "actions") {
            Spacer(Modifier.height(18.dp))
            DeckLabel("Actions")
            Spacer(Modifier.height(6.dp))
            DeckActionRow(Icons.Default.AutoAwesome, "Pregenerate from a wiki", onPregenerate)
            if (author.mapBoard == null) {
                DeckActionRow(Icons.Default.Map, "Create a map board", onCreateMap)
            }
            DeckActionRow(Icons.Default.Share, "Share this pack", onShare)
            if (!author.isOwn) {
                DeckActionRow(Icons.Default.ContentCopy, "Duplicate as my own", onFork)
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = deckOnMuted(), modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = deckOn())
    }
}

// ── shared leaves ───────────────────────────────────────────────────────────────────────────

@Composable
fun DeckLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = deckOnMuted(),
        modifier = modifier
    )
}

/** The digest strip and the reveal-cursor banner — one component, because they are the same
 *  object saying two different things. */
@Composable
fun DeckBanner(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(deckPanelShape())
            .background(deckCardColor())
            .then(if (action != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = deckOn()
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = deckOnMuted())
            }
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun DeckChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = deckOn(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(deckChipShape())
            .background(deckCardHighColor())
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
fun StatPill(label: String, value: String, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier
            .clip(deckChipShape())
            .background(deckCardHighColor())
            .padding(start = 10.dp, end = if (onDelete != null) 4.dp else 10.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = deckOnMuted())
        Spacer(Modifier.width(6.dp))
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = deckOn()
        )
        if (onDelete != null) {
            Spacer(Modifier.width(2.dp))
            Icon(
                Icons.Default.Close,
                "Delete $label",
                Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onDelete)
                    .padding(4.dp),
                tint = deckOnMuted()
            )
        }
    }
}

@Composable
fun DeckActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(deckPanelShape())
            .background(deckCardColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(19.dp), tint = deckOnMuted())
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = deckOn())
    }
}

@Composable
fun DeckIconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, label, Modifier.size(18.dp), tint = deckOnMuted())
    }
}

/** Shown in place of a pane when there is nothing in it yet. */
@Composable
fun DeckEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = deckOnMuted(),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Greedy wrap by character budget — an approximation of text width that costs no measurement pass,
 * carried over verbatim from the Immersive sheet this deck replaces. 34 is tuned to a phone at
 * default font scale; overshooting merely ellipsises a chip.
 */
internal fun chunkToRows(values: List<String>, budget: Int = 34): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var used = 0
    for (value in values) {
        val cost = value.length + 3
        if (row.isNotEmpty() && used + cost > budget) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        row += value
        used += cost
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

/**
 * How far ahead of the cursor the listener has to be before the deck offers to catch it up.
 *
 * Five minutes, not one: the automatic engine credits nothing for a tick that contained a seek, so
 * it legitimately trails genuine listening by up to one 30s tick, and every skip-forward adds
 * another. A tight threshold would make the banner blink in and out during ordinary listening,
 * which is the nagging chrome §8.1 rules out. The gap this exists for is hours, not minutes.
 */
internal const val CATCH_UP_MIN_GAP_MS = 5 * 60_000L
