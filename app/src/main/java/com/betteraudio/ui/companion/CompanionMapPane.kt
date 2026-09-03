package com.betteraudio.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.betteraudio.companion.ResolvedPin
import com.betteraudio.companion.model.ArtTone
import com.betteraudio.companion.model.BoardArt
import com.betteraudio.companion.model.CANVAS_UNITS
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.companion.model.PackEntity
import java.io.File
import kotlin.math.roundToInt

/**
 * The map, as a pane.
 *
 * It used to be a full-screen `Dialog` launched from a text button inside a bottom sheet — a third
 * surface stacked on the two the companion already had, with its own close button, its own edge
 * cases and no way back except unwinding the whole stack. As a spine destination it is reached the
 * same way everything else is, back means the same thing it means everywhere else, and the dock
 * stays put underneath it so the transport does not vanish just because you looked at the map.
 *
 * Two things it gains by being here rather than in a dialog:
 *
 * - **Pins are portraits.** A pin used to be an anonymous accent dot with a caption; a face on a
 *   map is legible at a glance and connects the board to the cast without a legend.
 * - **Board art renders.** [PackBoard.artMedia] was parsed and carried by the model but never
 *   drawn, because nothing could resolve a pack-relative media path. [packDir] resolves it now,
 *   and the flat [ArtTone] fill stays as the ground beneath a board with no art.
 */
@Composable
fun MapPane(
    /** Every map in the pack. More than one is normal — see [CompanionAuthorViewModel.UiState.mapBoards]. */
    boards: List<PackBoard>,
    boardId: String?,
    pins: List<ResolvedPin>,
    /** Pack-relative path of the backdrop to draw: already resolved against the reveal cursor in
     *  consumption, against the whole timeline while editing. */
    artPath: String?,
    /** This board's revisions with their positions, oldest first. Only used in edit mode. */
    artTimeline: List<Pair<BoardArt, Long>>,
    packDir: String?,
    editing: Boolean,
    contentPadding: PaddingValues,
    onSelectBoard: (String) -> Unit,
    onMovePin: (entityId: String, xUnits: Float, yUnits: Float) -> Unit,
    onDeletePin: (elementId: String) -> Unit,
    onAddPin: () -> Unit,
    onCreateBoard: () -> Unit,
    onAddMap: () -> Unit,
    onRenameBoard: () -> Unit,
    onDeleteBoard: () -> Unit,
    onSetImage: () -> Unit,
    onDeleteArt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val board = boards.firstOrNull { it.boardId == boardId } ?: boards.firstOrNull()
    if (board == null) {
        Column(modifier.padding(contentPadding)) {
            DeckEmpty("This pack has no map yet.")
            if (editing) {
                Spacer(Modifier.height(8.dp))
                DeckActionRow(Icons.Default.Add, "Add a map", onCreateBoard)
            }
        }
        return
    }

    var selected by remember { mutableStateOf<ResolvedPin?>(null) }
    val art = remember(artPath, packDir) {
        val rel = artPath?.takeIf { it.isNotBlank() } ?: return@remember null
        val dir = packDir ?: return@remember null
        File(dir, rel).takeIf { it.isFile }
    }

    Column(modifier.padding(contentPadding)) {
        // The switcher only appears when there is something to switch between — one map should
        // look like one map, not like a tab strip with a single tab.
        if (boards.size > 1 || editing) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                boards.forEachIndexed { index, candidate ->
                    BoardChip(
                        label = candidate.title?.takeIf { it.isNotBlank() }
                            ?: if (boards.size == 1) "Map" else "Map ${index + 1}",
                        selected = candidate.boardId == board.boardId,
                        onClick = { onSelectBoard(candidate.boardId) }
                    )
                }
                if (editing) BoardChip(label = "+ Add", selected = false, onClick = onAddMap)
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DeckLabel(
                (board.title?.takeIf { it.isNotBlank() } ?: "Board") +
                    " · ${pins.size} pin${if (pins.size == 1) "" else "s"}",
                Modifier.weight(1f)
            )
            if (editing) {
                DeckIconAction(Icons.Default.Image, "Set map image", onSetImage)
                DeckIconAction(Icons.Default.Add, "Add a pin", onAddPin)
                DeckIconAction(Icons.Default.DriveFileRenameOutline, "Rename map", onRenameBoard)
                DeckIconAction(Icons.Default.Delete, "Delete map", onDeleteBoard)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxSize()
                .clip(deckPanelShape())
                .background(groundFor(board.artTone))
        ) {
            if (art != null) {
                AsyncImage(
                    model = art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val scaleX = with(density) { maxWidth.toPx() } / CANVAS_UNITS
                val scaleY = with(density) { maxHeight.toPx() } / CANVAS_UNITS
                var draggingId by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableStateOf(Offset.Zero) }

                pins.forEach { pin ->
                    val dragging = draggingId == pin.elementId
                    val baseX = pin.xUnits * scaleX
                    val baseY = pin.yUnits * scaleY
                    val x = baseX + if (dragging) dragOffset.x else 0f
                    val y = baseY + if (dragging) dragOffset.y else 0f
                    val halfPx = with(density) { PIN_SIZE.toPx() } / 2f

                    Box(
                        Modifier
                            .offset { IntOffset((x - halfPx).roundToInt(), (y - halfPx).roundToInt()) }
                            .size(PIN_SIZE)
                            .then(
                                if (editing) Modifier.pointerInput(pin.elementId) {
                                    detectDragGestures(
                                        onDragStart = { draggingId = pin.elementId; dragOffset = Offset.Zero },
                                        onDragEnd = {
                                            // A drag shorter than the slop is a tap that wobbled —
                                            // treat it as selection, not as a move, or every
                                            // inspect would silently re-anchor the pin.
                                            val moved = dragOffset.getDistance() >
                                                with(density) { 6.dp.toPx() }
                                            if (moved) {
                                                onMovePin(
                                                    pin.entity.entityId,
                                                    ((baseX + dragOffset.x) / scaleX).coerceIn(0f, CANVAS_UNITS),
                                                    ((baseY + dragOffset.y) / scaleY).coerceIn(0f, CANVAS_UNITS)
                                                )
                                            } else {
                                                selected = pin
                                            }
                                            draggingId = null
                                        },
                                        onDragCancel = { draggingId = null }
                                    ) { change, amount -> change.consume(); dragOffset += amount }
                                } else Modifier.clickable { selected = pin }
                            )
                    ) {
                        PinMarker(pin.entity, packDir)
                    }
                }
            }

            if (art == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (editing) "No image yet — tap the picture icon above."
                        else "No map image for this point in the story yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = deckOnMuted(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            selected?.let { pin ->
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .fillMaxWidth()
                        .clip(deckPanelShape())
                        .background(deckCardHighColor())
                        .padding(horizontal = 13.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pin.entity.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = deckOn()
                        )
                        Text(
                            "at ${pin.locationFact.value}",
                            style = MaterialTheme.typography.labelSmall,
                            color = deckOnMuted()
                        )
                    }
                    if (editing) {
                        DeckIconAction(Icons.Default.Delete, "Remove pin") {
                            onDeletePin(pin.elementId)
                            selected = null
                        }
                    }
                    DeckIconAction(Icons.Default.Close, "Dismiss") { selected = null }
                }
            }
        }

        // The revision strip. Editor-only: for a listener the map simply *is* whatever it is at
        // their position, and showing them a row of future states would hand over every spoiler
        // the reveal cursor exists to withhold.
        if (editing && (artTimeline.isNotEmpty() || board.artMedia != null)) {
            Spacer(Modifier.height(8.dp))
            DeckLabel("Versions")
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (board.artMedia != null) {
                    BoardChip(label = "From the start", selected = false, onClick = {})
                }
                artTimeline.forEach { (revision, atMs) ->
                    BoardChip(
                        label = revision.label?.takeIf { it.isNotBlank() }
                            ?: "From ${formatDeckHm(atMs)}",
                        selected = false,
                        onClick = { onDeleteArt(revision.artId) },
                        trailingClose = true
                    )
                }
            }
            Text(
                "Set an image while listening to add a version from that point on.",
                style = MaterialTheme.typography.labelSmall,
                color = deckOnMuted(),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun BoardChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailingClose: Boolean = false
) {
    Row(
        Modifier
            .clip(deckChipShape())
            .background(if (selected) MaterialTheme.colorScheme.primary else deckCardColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else deckOn(),
            maxLines = 1
        )
        if (trailingClose) {
            Spacer(Modifier.width(5.dp))
            Icon(Icons.Default.Close, "Delete version", Modifier.size(13.dp), tint = deckOnMuted())
        }
    }
}

@Composable
private fun PinMarker(entity: PackEntity, packDir: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        CompanionPortrait(entity = entity, size = PIN_SIZE, packDir = packDir)
        Spacer(Modifier.height(2.dp))
        // requiredWidth, not width: the pin's own Box is PIN_SIZE square (it sizes the marker, not
        // the caption), so an inherited constraint truncates every name past ~4 characters — "The
        // Forgotten Shore", "The Bastion" and "The Outskirts" all rendered as "The". Ignoring the
        // incoming constraint lets the label overhang the marker, which is what a map pin wants.
        Text(
            text = entity.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .requiredWidth(110.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** Chooser for a board with no art of its own — [ArtTone] is the author's hint about which side
 *  the surrounding chrome should read as. */
private fun groundFor(tone: ArtTone?): Color = when (tone) {
    ArtTone.LIGHT -> Color(0xFFEDEBE4)
    ArtTone.DARK, null -> Color(0xFF14171C)
    ArtTone.NEUTRAL -> Color(0xFF3A3A3A)
}

/** Pins are portraits now, so they are sized like the smallest legible face rather than like a dot. */
private val PIN_SIZE = 34.dp

/** Sheet listing everyone not yet on the board, for the add-a-pin flow. */
@Composable
fun AddPinDialog(
    candidates: List<PackEntity>,
    packDir: String?,
    onPick: (PackEntity) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.betteraudio.ui.components.appDialogColor(),
        title = { Text("Add a pin") },
        text = {
            if (candidates.isEmpty()) {
                Text("Everyone in the cast is already on the map.")
            } else {
                LazyColumn {
                    items(candidates, key = { it.entityId }) { entity ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onPick(entity) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompanionPortrait(entity = entity, size = 32.dp, packDir = packDir)
                            Spacer(Modifier.width(12.dp))
                            Text(entity.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            com.betteraudio.ui.haptics.HapticTextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
