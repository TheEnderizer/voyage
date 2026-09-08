package com.betteraudio.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
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
 *
 * **It pans and zooms.** A map is the one surface in the deck where the whole point is looking
 * closer at part of it: a fantasy map squeezed into a phone-width pane is a picture of a map, not a
 * map you can read. Pinch to zoom, drag to pan, double-tap to go in and back out again. The
 * transform is applied to the artwork through a `graphicsLayer` and to the pins **arithmetically**
 * (see [mapToScreen]) rather than by transforming a shared parent — which is what keeps a pin the
 * same size and the same comfortable tap target at every zoom, instead of a marker that balloons
 * when you zoom in and becomes unhittable when you zoom out.
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
            // How far in, and where. Reset when the board changes — the previous map's zoom means
            // nothing on a different image, and arriving at a new board already panned into an
            // empty corner reads as a broken screen.
            var zoom by remember(board.boardId) { mutableStateOf(1f) }
            var pan by remember(board.boardId) { mutableStateOf(Offset.Zero) }

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                val viewW = with(density) { maxWidth.toPx() }
                val viewH = with(density) { maxHeight.toPx() }
                // The artwork's own aspect ratio, reported by the loaded image. Null until it
                // lands, which is why every use below falls back to the pane.
                var artAspect by remember(board.boardId) { mutableStateOf<Float?>(null) }

                // Where the whole image actually sits inside the pane.
                //
                // This used to be the pane itself, with the image drawn at ContentScale.Crop — so
                // anything outside the pane's aspect ratio was simply cut off the map and could
                // never be reached, at any zoom. A map is the one kind of picture where the edges
                // are the point: a cropped coastline is missing coastline, not a tighter framing.
                // Fit shows all of it, and pins are anchored to the FITTED RECT rather than to the
                // pane, so a marker stays on the place it names instead of drifting into the
                // letterbox as the pane's shape changes.
                val aspect = artAspect
                val artW = if (aspect == null) viewW else minOf(viewW, viewH * aspect)
                val artH = if (aspect == null) viewH else minOf(viewH, viewW / aspect)
                val artLeft = (viewW - artW) / 2f
                val artTop = (viewH - artH) / 2f
                // Pixels per authored unit. NOT named scaleX/scaleY: those are also the names of
                // GraphicsLayerScope's own properties, and a local shadows the receiver inside the
                // graphicsLayer block below, so `scaleX = zoom` would assign to this val instead.
                val unitX = artW / CANVAS_UNITS
                val unitY = artH / CANVAS_UNITS
                val artOrigin = Offset(artLeft, artTop)

                // At 1x the map exactly fills the pane, so there is nothing to pan to; past that,
                // the pane may not leave the artwork. Applied on every change rather than only on
                // release, so the map cannot be flung into empty space and left there.
                fun clampPan(next: Offset, z: Float): Offset = Offset(
                    next.x.coerceIn(viewW * (1f - z), 0f),
                    next.y.coerceIn(viewH * (1f - z), 0f)
                )

                fun setZoom(next: Float, focus: Offset) {
                    val z = next.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
                    // Keep the point under the fingers under the fingers: the content point at the
                    // focus is (focus - pan) / zoom, and it must land back on focus at the new
                    // zoom. Without this a pinch drifts toward the top-left corner.
                    val content = (focus - pan) / zoom
                    pan = clampPan(focus - content * z, z)
                    zoom = z
                }

                if (art != null) {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        onState = { st ->
                            if (st is AsyncImagePainter.State.Success) {
                                val sz = st.painter.intrinsicSize
                                if (sz.width > 0f && sz.height > 0f) artAspect = sz.width / sz.height
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                                // Top-left, so the arithmetic above (and mapToScreen) is a plain
                                // scale-then-translate rather than one about a moving centre.
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                    )
                }

                // Under the pins in the stack, so a pin still takes its own touches first; this
                // catches everything else. detectTransformGestures reports pan for a single
                // pointer too, so one finger drags the map and two pinch it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(board.boardId) {
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                if (zoomChange != 1f) setZoom(zoom * zoomChange, centroid)
                                pan = clampPan(pan + panChange, zoom)
                            }
                        }
                        .pointerInput(board.boardId) {
                            detectTapGestures(
                                // In to a readable magnification, and back out again — the whole
                                // "let me look at that corner" round trip without a pinch.
                                onDoubleTap = { at ->
                                    if (zoom > 1.05f) { zoom = 1f; pan = Offset.Zero }
                                    else setZoom(DOUBLE_TAP_MAP_ZOOM, at)
                                }
                            )
                        }
                )

                var draggingId by remember { mutableStateOf<String?>(null) }
                var dragOffset by remember { mutableStateOf(Offset.Zero) }

                pins.forEach { pin ->
                    val dragging = draggingId == pin.elementId
                    // Pins live in screen space, transformed by hand. Putting them inside the same
                    // graphicsLayer as the artwork would scale the markers with it — a face the
                    // size of a thumbnail at 4x, and a target too small to hit at 1x.
                    val base = mapToScreen(pin.xUnits, pin.yUnits, unitX, unitY, artOrigin, zoom, pan)
                    val x = base.x + if (dragging) dragOffset.x else 0f
                    val y = base.y + if (dragging) dragOffset.y else 0f
                    val halfPx = with(density) { PIN_SIZE.toPx() } / 2f

                    Box(
                        Modifier
                            .offset { IntOffset((x - halfPx).roundToInt(), (y - halfPx).roundToInt()) }
                            .size(PIN_SIZE)
                            .then(
                                if (editing) Modifier.pointerInput(pin.elementId, zoom, pan) {
                                    detectDragGestures(
                                        onDragStart = { draggingId = pin.elementId; dragOffset = Offset.Zero },
                                        onDragEnd = {
                                            // A drag shorter than the slop is a tap that wobbled —
                                            // treat it as selection, not as a move, or every
                                            // inspect would silently re-anchor the pin.
                                            val moved = dragOffset.getDistance() >
                                                with(density) { 6.dp.toPx() }
                                            if (moved) {
                                                // Back out through the same transform the pin was
                                                // drawn with, so a drag at 3x moves the pin by
                                                // what it looks like it moved by, not by three
                                                // times that.
                                                val units = screenToMap(
                                                    base + dragOffset, unitX, unitY, artOrigin, zoom, pan
                                                )
                                                onMovePin(
                                                    pin.entity.entityId,
                                                    units.x.coerceIn(0f, CANVAS_UNITS),
                                                    units.y.coerceIn(0f, CANVAS_UNITS)
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

/** Zoom limits. 1x is "the whole board fits the pane", which is the only sensible floor: below it
 *  the map would sit in a margin of nothing. The ceiling is where a phone-sized pane still shows
 *  enough context to know where you are. */
private const val MIN_MAP_ZOOM = 1f
private const val MAX_MAP_ZOOM = 5f
/** Where a double-tap lands. Far enough in to read a label, near enough out to keep your bearings. */
private const val DOUBLE_TAP_MAP_ZOOM = 2.5f

/** A pin's authored position, in pixels on screen under the current [zoom]/[pan]. The artwork is
 *  transformed by a `graphicsLayer` with a top-left origin, so this is the same scale-then-translate
 *  it performs — which is what keeps pins glued to the art through a pinch. */
private fun mapToScreen(
    xUnits: Float, yUnits: Float,
    scaleX: Float, scaleY: Float,
    /** Top-left of the fitted artwork inside the pane — the letterbox offset. */
    origin: Offset,
    zoom: Float, pan: Offset
): Offset = Offset(
    (origin.x + xUnits * scaleX) * zoom + pan.x,
    (origin.y + yUnits * scaleY) * zoom + pan.y
)

/** [mapToScreen] inverted: where a screen point sits in the board's authored unit space. */
private fun screenToMap(
    point: Offset,
    scaleX: Float, scaleY: Float,
    origin: Offset,
    zoom: Float, pan: Offset
): Offset = Offset(
    ((point.x - pan.x) / zoom - origin.x) / scaleX,
    ((point.y - pan.y) / zoom - origin.y) / scaleY
)

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
