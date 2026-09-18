package com.betteraudio.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.PressFeel
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
 *
 * ### The map is the pane, and the two looks disagree about what that means
 *
 * Everything that used to stack *around* the artwork — a board chip strip above it, a title row
 * with four editor icons, a "Versions" strip below it and a sentence explaining the strip — is now
 * chrome floating **on** the map: the plate at the top carries the board's name, its pin count and
 * the switcher, the editor's four actions are a rail down the free edge, and versions and zoom are
 * chips at the bottom. On a phone that is worth roughly 150dp of map.
 *
 * Where they part company is the container, and [mapIsFullBleed] is the switch:
 *
 * - **Material You contains it.** A `surfaceContainer` card at the app's own 28dp cover radius,
 *   inset from the screen, with the artwork fitted inside — so the rounded tonal edge is visible
 *   all the way round the picture instead of the map running into a hard corner.
 * - **Immersive lets it be the ground.** No card and no inset: the artwork takes the whole screen,
 *   the deck's cover backdrop steps aside, and the header, spine and dock float on the map as glass
 *   (see `PortraitDeck`'s overlaid branch). The letterbox either side of a fitted map is filled by
 *   a blurred, dimmed copy of the map itself — the same trick the player's cover backdrop uses — so
 *   the picture reaches every edge without being cropped. Readability for the chrome comes from two
 *   [mapVeil] gradients at the ends, which are eased rather than linear precisely so the darkening
 *   has no visible edge where it stops.
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
    /**
     * Where the chrome may sit. In the contained look this is also the card's outer margin; in the
     * full-bleed look the *artwork* ignores it (it is the ground, it goes to the edges) and only
     * the floating controls respect it, which is what keeps the map plate clear of the header and
     * the bottom chips clear of the dock.
     */
    contentPadding: PaddingValues,
    /** Entity whose pin was just added and has not been placed yet — see [PinMarker]'s hint. */
    hintEntityId: String?,
    onSelectBoard: (String) -> Unit,
    onMovePin: (entityId: String, xUnits: Float, yUnits: Float) -> Unit,
    onEditPinTime: (ResolvedPin) -> Unit,
    onDeletePin: (elementId: String) -> Unit,
    /** Coordinates, not a bare event: a pin has to land somewhere, and the only place that reads as
     *  "here" is the middle of what the author is currently looking at. Only the pane knows that. */
    onAddPin: (xUnits: Float, yUnits: Float) -> Unit,
    onCreateBoard: () -> Unit,
    onAddMap: () -> Unit,
    onRenameBoard: () -> Unit,
    onDeleteBoard: () -> Unit,
    onSetImage: () -> Unit,
    onEditArtTime: (BoardArt) -> Unit,
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

    val art = remember(artPath, packDir) {
        val rel = artPath?.takeIf { it.isNotBlank() } ?: return@remember null
        val dir = packDir ?: return@remember null
        File(dir, rel).takeIf { it.isFile }
    }

    val fullBleed = mapIsFullBleed(art != null)

    if (fullBleed) {
        Box(modifier.fillMaxSize()) {
            MapStage(
                board = board, boards = boards, pins = pins, art = art, artTimeline = artTimeline,
                packDir = packDir, editing = editing, fullBleed = true,
                chromePadding = contentPadding, hintEntityId = hintEntityId,
                onSelectBoard = onSelectBoard, onMovePin = onMovePin,
                onEditPinTime = onEditPinTime, onDeletePin = onDeletePin,
                onAddPin = onAddPin, onAddMap = onAddMap, onRenameBoard = onRenameBoard,
                onDeleteBoard = onDeleteBoard, onSetImage = onSetImage,
                onEditArtTime = onEditArtTime, onDeleteArt = onDeleteArt
            )
        }
    } else {
        Box(
            modifier
                .padding(contentPadding)
                .fillMaxSize()
                .clip(deckMapCardShape())
                .background(if (art == null) groundFor(board.artTone) else deckCardColor())
        ) {
            MapStage(
                board = board, boards = boards, pins = pins, art = art, artTimeline = artTimeline,
                packDir = packDir, editing = editing, fullBleed = false,
                // Inside the card, and far enough in to clear a 28dp corner.
                chromePadding = PaddingValues(CARD_CHROME_INSET),
                hintEntityId = hintEntityId,
                onSelectBoard = onSelectBoard, onMovePin = onMovePin,
                onEditPinTime = onEditPinTime, onDeletePin = onDeletePin,
                onAddPin = onAddPin, onAddMap = onAddMap, onRenameBoard = onRenameBoard,
                onDeleteBoard = onDeleteBoard, onSetImage = onSetImage,
                onEditArtTime = onEditArtTime, onDeleteArt = onDeleteArt
            )
        }
    }
}

/**
 * The map itself and everything floating on it — one implementation for both containers, because
 * the artwork, the gesture, the pins and the chrome are identical in the two looks and only their
 * fills and their outer frame are not.
 */
@Composable
private fun MapStage(
    board: PackBoard,
    boards: List<PackBoard>,
    pins: List<ResolvedPin>,
    art: File?,
    artTimeline: List<Pair<BoardArt, Long>>,
    packDir: String?,
    editing: Boolean,
    fullBleed: Boolean,
    chromePadding: PaddingValues,
    hintEntityId: String?,
    onSelectBoard: (String) -> Unit,
    onMovePin: (entityId: String, xUnits: Float, yUnits: Float) -> Unit,
    onEditPinTime: (ResolvedPin) -> Unit,
    onDeletePin: (elementId: String) -> Unit,
    onAddPin: (xUnits: Float, yUnits: Float) -> Unit,
    onAddMap: () -> Unit,
    onRenameBoard: () -> Unit,
    onDeleteBoard: () -> Unit,
    onSetImage: () -> Unit,
    onEditArtTime: (BoardArt) -> Unit,
    onDeleteArt: (String) -> Unit
) {
    var selected by remember(board.boardId) { mutableStateOf<ResolvedPin?>(null) }
    var showBoards by remember { mutableStateOf(false) }
    var showVersions by remember { mutableStateOf(false) }
    val veilInk = deckVeilInk()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val viewW = with(density) { maxWidth.toPx() }
        val viewH = with(density) { maxHeight.toPx() }
        // The artwork's own aspect ratio, reported by the loaded image. Null until it lands, which
        // is why every use below falls back to the pane.
        var artAspect by remember(board.boardId) { mutableStateOf<Float?>(null) }

        // Where the whole image actually sits inside the view.
        //
        // This used to be the pane itself, with the image drawn at ContentScale.Crop — so anything
        // outside the pane's aspect ratio was simply cut off the map and could never be reached, at
        // any zoom. A map is the one kind of picture where the edges are the point: a cropped
        // coastline is missing coastline, not a tighter framing. Fit shows all of it, and pins are
        // anchored to the FITTED RECT rather than to the view, so a marker stays on the place it
        // names instead of drifting into the letterbox as the view's shape changes.
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

        // How far in, and where. Reset when the board changes — the previous map's zoom means
        // nothing on a different image, and arriving at a new board already panned into an empty
        // corner reads as a broken screen.
        var zoom by remember(board.boardId) { mutableStateOf(1f) }
        var pan by remember(board.boardId) { mutableStateOf(Offset.Zero) }

        // At 1x the map exactly fills the view, so there is nothing to pan to; past that, the view
        // may not leave the artwork. Applied on every change rather than only on release, so the
        // map cannot be flung into empty space and left there.
        fun clampPan(next: Offset, z: Float): Offset = Offset(
            next.x.coerceIn(viewW * (1f - z), 0f),
            next.y.coerceIn(viewH * (1f - z), 0f)
        )

        fun setZoom(next: Float, focus: Offset) {
            val z = next.coerceIn(MIN_MAP_ZOOM, MAX_MAP_ZOOM)
            // Keep the point under the fingers under the fingers: the content point at the focus is
            // (focus - pan) / zoom, and it must land back on focus at the new zoom. Without this a
            // pinch drifts toward the top-left corner.
            val content = (focus - pan) / zoom
            pan = clampPan(focus - content * z, z)
            zoom = z
        }

        // The letterbox filler, full-bleed only: the same picture, cropped to cover, blurred and
        // dimmed under a flat ink wash. Immersive's own vocabulary — it is what the player does
        // with a cover — and it is what lets a fitted map reach the screen edges without being
        // cropped. Static rather than following the transform: it is ambience, and once you are
        // zoomed past 1x the sharp copy covers it entirely anyway. `Modifier.blur` is a no-op below
        // API 31, where the wash and the veils carry it on their own.
        if (fullBleed && art != null) {
            AsyncImage(
                model = art,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(BACKDROP_BLUR)
            )
            Box(Modifier.fillMaxSize().background(veilInk.copy(alpha = BACKDROP_WASH)))
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

        // Under the pins in the stack, so a pin still takes its own touches first; this catches
        // everything else. detectTransformGestures reports pan for a single pointer too, so one
        // finger drags the map and two pinch it.
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
                        // In to a readable magnification, and back out again — the whole "let me
                        // look at that corner" round trip without a pinch.
                        onDoubleTap = { at ->
                            if (zoom > 1.05f) { zoom = 1f; pan = Offset.Zero }
                            else setZoom(DOUBLE_TAP_MAP_ZOOM, at)
                        }
                    )
                }
        )

        // The veils, full-bleed only, and above the artwork but below the pins: a marker that
        // happens to sit high or low on the map should still be legible, while the artwork under
        // the header and the dock should not compete with them.
        if (fullBleed) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight * VEIL_TOP_FRACTION)
                    .align(Alignment.TopCenter)
                    .background(mapVeil(veilInk, VEIL_TOP_ALPHA, fromTop = true))
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight * VEIL_BOTTOM_FRACTION)
                    .align(Alignment.BottomCenter)
                    .background(mapVeil(veilInk, VEIL_BOTTOM_ALPHA, fromTop = false))
            )
        }

        var draggingId by remember { mutableStateOf<String?>(null) }
        var dragOffset by remember { mutableStateOf(Offset.Zero) }
        val grabSlopPx = with(density) { PIN_GRAB_SLOP.toPx() }
        val touchHalfPx = with(density) { PIN_TOUCH.toPx() } / 2f

        pins.forEach { pin ->
            val dragging = draggingId == pin.elementId
            // Pins live in screen space, transformed by hand. Putting them inside the same
            // graphicsLayer as the artwork would scale the markers with it — a face the size of a
            // thumbnail at 4x, and a target too small to hit at 1x.
            val base = mapToScreen(pin.xUnits, pin.yUnits, unitX, unitY, artOrigin, zoom, pan)
            val x = base.x + if (dragging) dragOffset.x else 0f
            val y = base.y + if (dragging) dragOffset.y else 0f

            Box(
                Modifier
                    .offset { IntOffset((x - touchHalfPx).roundToInt(), (y - touchHalfPx).roundToInt()) }
                    // The tap target is bigger than the marker, and deliberately not the same
                    // object as it. The face is 34dp; a finger is not, and the caption under the
                    // face draws outside the pin bounds and so was never touchable at all, which is
                    // where most of the misses went — you press the name and nothing happens.
                    .size(PIN_TOUCH)
                    .then(
                        if (editing) Modifier.pointerInput(pin.elementId, base, unitX, unitY, artOrigin, zoom, pan) {
                            // One gesture handler, and it takes the pointer the instant it lands.
                            //
                            // This used to be `clickable` in front of `detectDragGestures`, and it
                            // was genuinely hard to move a pin: a drag reports nothing at all until
                            // it passes the system touch slop, so the first part of every drag went
                            // nowhere, and the pan/zoom handler underneath — which sees the same
                            // pointer — was free to read those same early moves as a map pan and
                            // take the gesture away. The pin moved when you won that race and did
                            // not when you lost it, which is what "press and drag several times to
                            // get it moving" is.
                            //
                            // Consuming the down settles it: while a finger is on a pin in edit
                            // mode, that finger belongs to the pin. `detectTransformGestures`
                            // abandons a gesture whose changes are consumed, so the map holds
                            // still. There is no slop to clear — the first millimetre of movement
                            // already moves the marker — and [PIN_GRAB_SLOP] afterwards only
                            // decides whether what happened was a move or a tap that wobbled.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                draggingId = pin.elementId
                                dragOffset = Offset.Zero
                                var travel = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) {
                                        change?.consume()
                                        break
                                    }
                                    val delta = change.positionChange()
                                    if (delta != Offset.Zero) {
                                        travel += delta
                                        dragOffset = travel
                                    }
                                    change.consume()
                                }
                                if (travel.getDistance() > grabSlopPx) {
                                    // Back out through the same transform the pin was drawn with,
                                    // so a drag at 3x moves the pin by what it looks like it moved
                                    // by, not by three times that.
                                    val units = screenToMap(
                                        base + travel, unitX, unitY, artOrigin, zoom, pan
                                    )
                                    onMovePin(
                                        pin.entity.entityId,
                                        units.x.coerceIn(0f, CANVAS_UNITS),
                                        units.y.coerceIn(0f, CANVAS_UNITS)
                                    )
                                } else {
                                    // A press that did not travel is an inspect, not a move — which
                                    // is also the only way to reach the card carrying "Remove pin"
                                    // and the placement position.
                                    selected = pin
                                }
                                draggingId = null
                                dragOffset = Offset.Zero
                            }
                        } else Modifier.clickable { selected = pin }
                    )
            ) {
                // The marker is TALLER than its tap target and must be allowed to say so.
                //
                // The inner Box is PIN_SIZE square because that is the anchor the offset arithmetic
                // is written against; the marker inside it is a portrait plus a caption plus (once)
                // a placement hint, which is two or three times that. Measured against the 34dp
                // box, the Column hands its first child the whole height and the rest nothing, and
                // the result is a flattened smear where the face should be — which is exactly what
                // it did on device. `unbounded` lets the column measure at its natural size, and
                // TopCenter keeps its first child — the portrait — sitting precisely on the anchor,
                // so the pin still points at the place it names.
                Box(Modifier.align(Alignment.Center).size(PIN_SIZE)) {
                    Box(Modifier.wrapContentSize(align = Alignment.TopCenter, unbounded = true)) {
                        PinMarker(
                            entity = pin.entity,
                            packDir = packDir,
                            selected = selected?.elementId == pin.elementId,
                            awaitingPlacement = pin.entity.entityId == hintEntityId
                        )
                    }
                }
            }
        }

        if (art == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (editing) "No image yet — tap the picture icon."
                    else "No map image for this point in the story yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = deckOnMuted(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }

        // ── chrome ──────────────────────────────────────────────────────────────────────────
        //
        // One padded overlay for everything that floats, so the two containers only have to
        // disagree about one number.
        Box(Modifier.fillMaxSize().padding(chromePadding)) {

            MapPlate(
                title = board.title?.takeIf { it.isNotBlank() } ?: "Map",
                pinCount = pins.size,
                boardIndex = boards.indexOfFirst { it.boardId == board.boardId },
                boardCount = boards.size,
                // With one map and nothing to add, the plate is a label rather than a control.
                switchable = boards.size > 1 || editing,
                onClick = { showBoards = true },
                modifier = Modifier.align(Alignment.TopStart)
            )

            if (editing) {
                Column(
                    Modifier.align(Alignment.TopEnd),
                    verticalArrangement = Arrangement.spacedBy(RAIL_GAP)
                ) {
                    MapRailButton(Icons.Default.Image, "Set map image", onSetImage)
                    MapRailButton(
                        icon = Icons.Default.PersonAdd,
                        label = "Add a pin",
                        onClick = {
                            // The centre of the view, in authored units — "here", which is the only
                            // placement that does not need explaining.
                            val centre = screenToMap(
                                Offset(viewW / 2f, viewH / 2f), unitX, unitY, artOrigin, zoom, pan
                            )
                            onAddPin(
                                centre.x.coerceIn(0f, CANVAS_UNITS),
                                centre.y.coerceIn(0f, CANVAS_UNITS)
                            )
                        }
                    )
                    MapRailButton(Icons.Default.DriveFileRenameOutline, "Rename map", onRenameBoard)
                    MapRailButton(
                        Icons.Default.Delete, "Delete map", onDeleteBoard,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            // The bottom row steps aside for the selected-pin card rather than stacking above it:
            // both want the same corner, and a pin you have just tapped is what you are looking at.
            if (selected == null) {
                if (editing && (artTimeline.isNotEmpty() || board.artMedia != null)) {
                    MapChip(
                        icon = Icons.Default.Layers,
                        label = "Versions · ${artTimeline.size + if (board.artMedia != null) 1 else 0}",
                        onClick = { showVersions = true },
                        modifier = Modifier.align(Alignment.BottomStart)
                    )
                }
                // Only above 1x: at rest there is nothing to reset, and a permanent "1.0x" chip is
                // a readout of the absence of a state.
                if (zoom > 1.02f) {
                    MapChip(
                        icon = Icons.Default.ZoomOutMap,
                        label = zoomLabel(zoom),
                        onClick = { zoom = 1f; pan = Offset.Zero },
                        iconTrailing = true,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }

            selected?.let { pin ->
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .shadow(SELECTED_ELEVATION, deckMapCardChromeShape(), clip = false)
                        .clip(deckMapCardChromeShape())
                        .background(deckMapChromeColor())
                        .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompanionPortrait(entity = pin.entity, size = 36.dp, packDir = packDir)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            pin.entity.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = deckOn(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        // The entity's kind, NOT `locationFact.value` — that value is the pin's
                        // position in canvas units (see PackBoardResolver), so the card used to
                        // read "at 464,228". Coordinates are how the pin is stored, never
                        // something a listener has any use for.
                        //
                        // *When* they are there is a different matter, and it was the one thing an
                        // author could not find out: a placement is a fact anchored wherever the
                        // listener stood when the pin was dragged, and nothing ever showed it.
                        val kindLabel = pin.entity.kind.name.lowercase()
                            .replaceFirstChar { it.uppercase() }
                        val placedAt = pin.locationFact.anchor.globalMs
                        Text(
                            if (editing && placedAt != null)
                                "$kindLabel · here from ${formatDeckHm(placedAt)}"
                            else kindLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = deckOnMuted(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (editing) {
                        DeckIconAction(Icons.Default.Schedule, "Change when") { onEditPinTime(pin) }
                        DeckIconAction(Icons.Default.Delete, "Remove pin") {
                            onDeletePin(pin.elementId)
                            selected = null
                        }
                    }
                    DeckIconAction(Icons.Default.Close, "Dismiss") { selected = null }
                }
            }
        }
    }

    if (showBoards) {
        MapBoardsDialog(
            boards = boards,
            currentId = board.boardId,
            editing = editing,
            onPick = { onSelectBoard(it); showBoards = false },
            onAddMap = { showBoards = false; onAddMap() },
            onDismiss = { showBoards = false }
        )
    }
    if (showVersions) {
        MapVersionsDialog(
            hasBaseArt = board.artMedia != null,
            timeline = artTimeline,
            onEditTime = { showVersions = false; onEditArtTime(it) },
            onDelete = { onDeleteArt(it) },
            onDismiss = { showVersions = false }
        )
    }
}

/** Zoom limits. 1x is "the whole board fits the view", which is the only sensible floor: below it
 *  the map would sit in a margin of nothing. The ceiling is where a phone-sized view still shows
 *  enough context to know where you are. */
private const val MIN_MAP_ZOOM = 1f
private const val MAX_MAP_ZOOM = 5f
/** Where a double-tap lands. Far enough in to read a label, near enough out to keep your bearings. */
private const val DOUBLE_TAP_MAP_ZOOM = 2.5f

/** How far the floating chrome sits inside the Material You card. Enough to clear its 28dp corner:
 *  a plate flush to the edge of a rounded card cuts the corner off visually. */
private val CARD_CHROME_INSET = 14.dp
private val RAIL_GAP = 8.dp
private val SELECTED_ELEVATION = 12.dp

/** Blur radius for the full-bleed letterbox filler. No-op below API 31 (see [MapStage]). */
private val BACKDROP_BLUR = 32.dp
/** How much ink goes over that filler. Heavy: it is a suggestion of the map's colour behind the
 *  real one, not a second copy competing with it. */
private const val BACKDROP_WASH = 0.58f

/**
 * The veils' extents and strengths.
 *
 * Both are a generous fraction of the screen rather than "just enough to cover the chrome" — see
 * [mapVeil]: a short veil has to be steep, and steep is exactly what you see as a line. The bottom
 * one is the taller and heavier of the two because the dock sits on it, and a dock is a wider, more
 * text-dense object than the header.
 */
private const val VEIL_TOP_FRACTION = 0.30f
private const val VEIL_TOP_ALPHA = 0.90f
private const val VEIL_BOTTOM_FRACTION = 0.34f
private const val VEIL_BOTTOM_ALPHA = 0.94f

private fun zoomLabel(zoom: Float): String = "${(zoom * 10f).roundToInt() / 10f}×"

/** A pin's authored position, in pixels on screen under the current [zoom]/[pan]. The artwork is
 *  transformed by a `graphicsLayer` with a top-left origin, so this is the same scale-then-translate
 *  it performs — which is what keeps pins glued to the art through a pinch. */
private fun mapToScreen(
    xUnits: Float, yUnits: Float,
    scaleX: Float, scaleY: Float,
    /** Top-left of the fitted artwork inside the view — the letterbox offset. */
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

/**
 * The board's identity, and the map switcher folded into it.
 *
 * The switcher used to be a horizontally scrolling strip of chips above the artwork, permanently
 * spending a row of height on a control that most packs never need — one map should look like one
 * map, not like a tab strip with a single tab. Here the name is always the name and tapping it is
 * how you get to the others, which is also where you would look for them.
 */
@Composable
private fun MapPlate(
    title: String,
    pinCount: Int,
    boardIndex: Int,
    boardCount: Int,
    switchable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(deckMapChromeShape())
            .background(deckMapChromeColor())
            .then(if (switchable) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 14.dp, end = if (switchable) 10.dp else 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = deckOn(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(pinCount)
                    append(if (pinCount == 1) " pin" else " pins")
                    if (boardCount > 1) append(" · map ${boardIndex + 1} of $boardCount")
                },
                style = MaterialTheme.typography.labelSmall,
                color = deckOnMuted(),
                maxLines = 1
            )
        }
        if (switchable) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = deckOnMuted())
        }
    }
}

/** One of the editor's four actions, as a button floating on the artwork. */
@Composable
private fun MapRailButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color? = null
) {
    PressFeel(Feel.Tap) {
        Box(
            Modifier
                .size(RAIL_BUTTON)
                .clip(deckMapChromeShape())
                .background(deckMapChromeColor())
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, Modifier.size(20.dp), tint = tint ?: deckOn())
        }
    }
}

private val RAIL_BUTTON = 40.dp

/** Versions, and the zoom readout: a label with an icon, floating on the artwork. */
@Composable
private fun MapChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconTrailing: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .clip(deckMapChromeShape())
            .background(deckMapChromeColor())
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        if (!iconTrailing) Icon(icon, null, Modifier.size(15.dp), tint = deckOnMuted())
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = deckOn(),
            maxLines = 1
        )
        if (iconTrailing) Icon(icon, null, Modifier.size(15.dp), tint = deckOnMuted())
    }
}

/**
 * A pin.
 *
 * [selected] rings the marker in the accent rather than only lighting up the card at the bottom of
 * the screen: the card says *which* pin, but the map is where you are looking.
 *
 * [awaitingPlacement] is the newly-added pin. A pin arrives in the middle of the view because it
 * has to arrive somewhere, and a face that appears in the centre of a map with no explanation reads
 * as a bug rather than as an invitation — so it is dashed, haloed, and captioned with the one thing
 * to do next. It stops being any of those the moment it is dragged.
 */
@Composable
private fun PinMarker(
    entity: PackEntity,
    packDir: String?,
    selected: Boolean,
    awaitingPlacement: Boolean
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            Modifier
                .size(PIN_SIZE)
                .then(
                    if (selected || awaitingPlacement) {
                        Modifier
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.22f))
                            .padding(1.dp)
                    } else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            CompanionPortrait(entity = entity, size = PIN_SIZE, packDir = packDir)
        }
        Spacer(Modifier.height(2.dp))
        // requiredWidth, not width: the pin's own Box is PIN_SIZE square (it sizes the marker, not
        // the caption), so an inherited constraint truncates every name past ~4 characters — "The
        // Forgotten Shore", "The Bastion" and "The Outskirts" all rendered as "The". Ignoring the
        // incoming constraint lets the label overhang the marker, which is what a map pin wants.
        Text(
            text = entity.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else deckOn(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .requiredWidth(CAPTION_WIDTH)
                .clip(deckChipShape())
                .background(if (selected) accent else deckPinCaptionColor())
                .padding(horizontal = 7.dp, vertical = 1.dp)
        )
        // Under the name rather than over the face: the portrait has to stay the column's FIRST
        // child so it lands on the anchor (see the marker's wrapper), and a chip hanging below also
        // cannot cover a pin sitting just above this one.
        if (awaitingPlacement) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Drag ${entity.name} into place",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                modifier = Modifier
                    .requiredWidth(HINT_WIDTH)
                    .clip(deckChipShape())
                    .background(accent)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
                textAlign = TextAlign.Center
            )
        }
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

/** The pin touch area, which is not the pin size. Comfortably past the 48dp minimum target while
 *  staying small enough that two pins a face apart are still separately hittable. */
private val PIN_TOUCH = 52.dp

/** How far a press has to travel before it counts as having moved the pin rather than inspected
 *  it. Small, because the gesture is captured on touch-down and nothing else is competing for it —
 *  this only has to survive the wobble of a finger lifting. */
private val PIN_GRAB_SLOP = 5.dp
private val CAPTION_WIDTH = 110.dp
private val HINT_WIDTH = 150.dp

/** The map switcher, and the way to a new map. Was a chip strip permanently occupying a row above
 *  the artwork; see [MapPlate]. */
@Composable
private fun MapBoardsDialog(
    boards: List<PackBoard>,
    currentId: String,
    editing: Boolean,
    onPick: (String) -> Unit,
    onAddMap: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.betteraudio.ui.components.appDialogColor(),
        title = { Text(if (boards.size > 1) "Maps" else "Map") },
        text = {
            LazyColumn {
                itemsIndexed(boards, key = { _, b -> b.boardId }) { index, candidate ->
                    val isCurrent = candidate.boardId == currentId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(candidate.boardId) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            candidate.title?.takeIf { it.isNotBlank() }
                                ?: if (boards.size == 1) "Map" else "Map ${index + 1}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isCurrent) {
                            Icon(
                                Icons.Default.Check, "Showing",
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (editing) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onAddMap)
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Add, null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Add a map",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
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

/**
 * The board's revisions.
 *
 * Editor-only, for the reason the strip it replaces was: for a listener the map simply *is*
 * whatever it is at their position, and showing them a row of future states would hand over every
 * spoiler the reveal cursor exists to withhold. It is a dialog rather than a permanent strip
 * because it is consulted rarely and cost about 70dp of map to sit there.
 */
@Composable
private fun MapVersionsDialog(
    hasBaseArt: Boolean,
    timeline: List<Pair<BoardArt, Long>>,
    onEditTime: (BoardArt) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.betteraudio.ui.components.appDialogColor(),
        title = { Text("Map versions") },
        text = {
            Column {
                Text(
                    "Set an image while listening to add a version from that point on. " +
                        "Tap a version to move it somewhere else in the book.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (hasBaseArt) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "From the start",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                timeline.forEach { (revision, atMs) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onEditTime(revision) }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                revision.label?.takeIf { it.isNotBlank() }
                                    ?: "From ${formatDeckHm(atMs)}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            // A labelled version says what it is and nothing about where it is, so
                            // the position goes underneath rather than replacing the label.
                            if (!revision.label.isNullOrBlank()) {
                                Text(
                                    "From ${formatDeckHm(atMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        DeckIconAction(Icons.Default.Delete, "Delete version") { onDelete(revision.artId) }
                    }
                }
            }
        },
        confirmButton = {
            com.betteraudio.ui.haptics.HapticTextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

/** Sheet listing everyone not yet on the board, for the add-a-pin flow. */
@Composable
fun AddPinDialog(
    candidates: List<PackEntity>,
    packDir: String?,
    onPick: (PackEntity) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
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
