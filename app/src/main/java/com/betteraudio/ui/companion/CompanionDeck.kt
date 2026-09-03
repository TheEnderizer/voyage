package com.betteraudio.ui.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.companion.model.BoardKind
import com.betteraudio.companion.model.PackScrap
import com.betteraudio.ui.haptics.Feel
import com.betteraudio.ui.haptics.PressFeel
import com.betteraudio.ui.isLandscapeWindow
import com.betteraudio.ui.player.morphFrom
import com.betteraudio.ui.theme.MotionTokens
import com.betteraudio.ui.theme.rememberPredictiveBackProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The deck's four destinations. This list *is* the navigation model — there is nowhere else to go
 * and nothing else that navigates.
 */
enum class CompanionDestination(val label: String) {
    CAST("Cast"),
    MAP("Map"),
    TIMELINE("Timeline"),
    PACK("Pack")
}

/**
 * The companion, as a full-screen deck that keeps the audio controls.
 *
 * Design: `docs/companion-redesign.html`. Data model, reveal semantics and pack format:
 * `docs/companion-packs.md`.
 *
 * ### What this replaces, and why
 *
 * A `ModalBottomSheet` over the player, which stacked a *second* modal sheet on itself to edit
 * anything and a full-screen `Dialog` on top of that for the map. Four consequences, all of them
 * downstream of picking a sheet because a sheet was the cheapest surface to reach from the player:
 * it covered the transport at exactly the moment you reached for it, navigation was scattered
 * across four surfaces, back meant something different depending on how deep the stack was, and
 * the two themes had drifted into two different products.
 *
 * ### Shape
 *
 * Four bands — header, spine, pane, dock — laid out in a column in portrait and in a
 * rail-plus-pane row in landscape. The bands themselves are identical in both orientations and
 * both themes; see [CompanionDeckStyle] for the per-theme half and why it is a token file rather
 * than two frame implementations.
 *
 * ### Not a nav destination
 *
 * Composed as the last child of the player's own root `Box`, exactly like `ChapterOverlay` and for
 * exactly the reason `docs/companion-packs.md` §8 gives: the player is not a route (it is a
 * draggable `PlayerSheet` with its own nested NavHost drawn *above* the top-level one), so
 * navigating the top-level host here would not even put the deck in front of it — and tearing the
 * player down would take with it the very elements the morph has to travel from.
 *
 * ### Edit is a mode, not a destination
 *
 * There is no fifth spine entry for editing. The header carries one toggle, and while it is on
 * every editable thing grows an affordance *where it is displayed*: a portrait gains a camera
 * badge, a fact row gains a delete, the cast grid gains an Add tile, Timeline gains quick capture.
 * The old editor sheet was a mirror-image list of the same data in another surface, which is what
 * made changing anything a two-surface operation.
 */
@Composable
fun CompanionDeck(
    /**
     * 0 = closed, 1 = fully open. A driver rather than a `Boolean` because the deck does not
     * appear, it *arrives*: the dock travels from [morph]'s source rect while the player's own
     * transport hands off to it over the same number. The player screen owns the animation so it
     * can read that same value for the hand-off (see [companionMorphSource]).
     */
    progress: State<Float>,
    bookId: Long,
    /**
     * Null when the deck is opened from somewhere with no playback to keep — Book Info's and the
     * Series page's overflow menus, where the book in question may not even be the one loaded.
     * The dock band is simply omitted rather than faked: a transport that does not control what
     * you are listening to is worse than no transport, and those entry points open the companion
     * to *read* it, not to listen alongside it.
     */
    transport: CompanionTransport?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null at the entry points with no player underneath them — see [CompanionDeckDialog]. */
    morph: CompanionMorphAnchors? = null,
    viewModel: CompanionViewModel = hiltViewModel(),
    authorViewModel: CompanionAuthorViewModel = hiltViewModel()
) {
    // Composed for as long as there is anything to draw, closing included — a surface that is
    // only *composed* while open can appear and disappear but can never travel.
    val visible by remember { derivedStateOf { progress.value > 0.001f } }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val author by authorViewModel.uiState.collectAsStateWithLifecycle()
    val seedState by authorViewModel.seedState.collectAsStateWithLifecycle()
    val selectedEntityId by viewModel.selectedEntityId.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var destination by rememberSaveable { mutableStateOf(CompanionDestination.CAST) }
    var editing by rememberSaveable { mutableStateOf(false) }

    // Dialog state. All hosted here rather than inside the panes so that a dialog outlives the
    // pane that opened it — switching spine destination with a dialog open would otherwise
    // dismiss it mid-answer.
    var showCreatePack by remember { mutableStateOf(false) }
    var showAddEntity by remember { mutableStateOf(false) }
    var showSeed by remember { mutableStateOf(false) }
    var showShareChoice by remember { mutableStateOf(false) }
    var showQuickCapture by remember { mutableStateOf(false) }
    var showCatchUp by remember { mutableStateOf(false) }
    var showAddPin by remember { mutableStateOf(false) }
    var addingFactFor by remember { mutableStateOf<String?>(null) }
    var convertingScrap by remember { mutableStateOf<PackScrap?>(null) }
    var portraitFor by remember { mutableStateOf<String?>(null) }
    // Which map is showing, and which one an incoming image belongs to. Saveable so switching to
    // another pane and back does not reset you to map 1 of 4.
    var selectedBoardId by rememberSaveable { mutableStateOf<String?>(null) }
    var mapImageFor by remember { mutableStateOf<String?>(null) }
    var renamingBoard by remember { mutableStateOf<String?>(null) }
    var showAddMap by remember { mutableStateOf(false) }

    LaunchedEffect(visible, bookId) {
        if (visible && bookId != -1L) {
            viewModel.bind(bookId)
            authorViewModel.bind(bookId)
        }
    }
    // "The user looked" is opening the deck, not a badge appearing (§8.1) — so the digest clears
    // here, and the Timeline pane's "since you last looked" list is the snapshot taken at that
    // moment rather than a live view that empties itself while being read.
    LaunchedEffect(visible, state.hasPack) {
        if (visible && state.hasPack) viewModel.markSeen()
    }
    LaunchedEffect(Unit) {
        authorViewModel.shareEvent.collect { file -> shareCompanionPack(context, file) }
    }
    // Leaving edit mode when the deck closes: an editing toggle that survives a close reopens onto
    // a surface covered in affordances the user did not ask for this time.
    LaunchedEffect(visible) { if (!visible) editing = false }

    val back: () -> Unit = {
        when {
            selectedEntityId != null -> viewModel.selectEntity(null)
            destination != CompanionDestination.CAST -> destination = CompanionDestination.CAST
            else -> onDismiss()
        }
    }
    // One predictive-back chain for the whole deck: character → cast → close. The gesture nudges
    // the deck before committing, the same "peek" every other overlay in the app gives.
    val backProgress = rememberPredictiveBackProgress(enabled = visible, onCommit = back)

    val portraitPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val entityId = portraitFor
        portraitFor = null
        if (uri == null || entityId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            } ?: return@launch
            // Extension from the MIME type, not from the Uri: a picked photo's Uri is usually an
            // opaque content:// id with no filename at all.
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            authorViewModel.setEntityPortrait(entityId, "portrait.$ext", bytes)
        }
    }

    // A separate launcher from the portrait one: they write to different places and a shared
    // launcher would need a mode flag whose only job is to remember which of two things the user
    // was doing, which is exactly the kind of state that goes wrong.
    val mapImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val target = mapImageFor
        mapImageFor = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    .getOrNull()
            } ?: return@launch
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            // A board with no picture yet gets its base art; one that already has a picture gets a
            // version anchored where the listener is. That is the whole "the map evolves" gesture,
            // and it needs no mode switch: the first image is what the map has always looked like,
            // and every one after it is a change that happened at a point in the story.
            val board = author.mapBoards.firstOrNull { it.boardId == target }
            authorViewModel.setBoardImage(
                boardId = target,
                fileName = "map.$ext",
                bytes = bytes,
                atCurrentPosition = board?.artMedia != null
            )
        }
    }

    if (visible) {
        Box(
            modifier
                .fillMaxSize()
                .graphicsLayer {
                    val b = backProgress.value
                    scaleX = 1f - 0.06f * b
                    scaleY = 1f - 0.06f * b
                    alpha = 1f - 0.25f * b
                }
        ) {
            // The ground arrives on its own, earlier curve: the room dims before the furniture has
            // finished moving, which is what lets the travelling dock read against something
            // instead of against the player's own controls.
            DeckGround(Modifier.graphicsLayer { alpha = (progress.value / 0.55f).coerceIn(0f, 1f) })

            // Which cast the deck is showing. Editing swaps to the author's unfiltered view for
            // the same reason the map does: you cannot edit what the reveal cursor is hiding, and
            // an author is by definition ahead of their own cursor. Reading it back through the
            // consumption list is what made the old editor a separate surface.
            val cast = if (editing) author.entities else state.entities
            val sheets = remember(cast) {
                cast.associate { it.entity.entityId to EntitySheetModel.build(it, cast) }
            }
            val selected = cast.firstOrNull { it.entity.entityId == selectedEntityId }
            val landscape = isLandscapeWindow()

            val header: @Composable (Modifier, Boolean) -> Unit = { m, stacked ->
                DeckHeader(
                    title = when {
                        selected != null -> selected.entity.name
                        else -> state.pack?.title.takeUnless { it.isNullOrBlank() } ?: "Companion"
                    },
                    subtitle = when {
                        selected != null -> destination.label.uppercase()
                        state.hasPack -> "REV ${state.pack?.revision ?: 1} · " +
                            if (author.isOwn) "YOURS" else "RECEIVED"
                        else -> "NO PACK"
                    },
                    showBack = selected != null,
                    editing = editing,
                    canEdit = state.hasPack,
                    onBack = back,
                    onToggleEdit = { editing = !editing },
                    onClose = onDismiss,
                    stacked = stacked,
                    modifier = m
                )
            }

            val spine: @Composable (Modifier, Boolean) -> Unit = { m, vertical ->
                DeckSpine(
                    destinations = CompanionDestination.entries,
                    selected = destination,
                    onSelect = {
                        destination = it
                        // Leaving Cast drops the open character: coming back to a spine
                        // destination should show that destination, not the sub-page you were on
                        // three taps ago.
                        if (it != CompanionDestination.CAST) viewModel.selectEntity(null)
                    },
                    modifier = m,
                    vertical = vertical
                )
            }

            val pane: @Composable (Modifier, PaddingValues) -> Unit = { m, pad ->
                AnimatedContent(
                    targetState = destination to selectedEntityId,
                    // Cross-fade only. The pane changes under a spine the user's finger is still
                    // on, and a slide would fight the gesture that caused it.
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "deckPane",
                    modifier = m
                ) { (dest, entityId) ->
                    val current = cast.firstOrNull { it.entity.entityId == entityId }
                    when {
                        !state.hasPack && dest != CompanionDestination.PACK ->
                            DeckEmpty("No companion pack for this book yet — open Pack to make one.")

                        dest == CompanionDestination.CAST && current != null ->
                            CharacterPane(
                                sheet = sheets[entityId] ?: EntitySheetModel.build(current, cast),
                                packDir = state.packDir,
                                entity = current,
                                editing = editing,
                                contentPadding = pad,
                                onSelectEntity = { viewModel.selectEntity(it) },
                                onPickPortrait = {
                                    portraitFor = current.entity.entityId
                                    portraitPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onAddFact = { addingFactFor = current.entity.entityId },
                                onDeleteFact = { authorViewModel.deleteFact(it) },
                                onDeleteEntity = {
                                    authorViewModel.deleteEntity(current.entity.entityId)
                                    viewModel.selectEntity(null)
                                }
                            )

                        dest == CompanionDestination.CAST ->
                            // The empty state is a *consumption* state. In edit mode the grid
                            // renders even with nobody in it, because its Add tile is the only way
                            // the first character gets created.
                            if (cast.isEmpty() && !editing) {
                                DeckEmpty(
                                    if (state.bookPositionMs > state.revealedMs + CATCH_UP_MIN_GAP_MS)
                                        "Nothing revealed yet — the companion is still at the start of the book. Open Timeline to catch it up."
                                    else "Nothing revealed yet — keep listening."
                                )
                            } else {
                                CastPane(
                                    entities = cast,
                                    sheets = sheets,
                                    packDir = state.packDir,
                                    digest = state.digest,
                                    editing = editing,
                                    contentPadding = pad,
                                    onSelect = { viewModel.selectEntity(it) },
                                    onAddCharacter = { showAddEntity = true },
                                    onGoToTimeline = { destination = CompanionDestination.TIMELINE }
                                )
                            }

                        dest == CompanionDestination.MAP -> {
                            // Editing shows the author's view — every map, every pin ever placed
                            // and every version of the art — because you cannot edit what the
                            // reveal cursor is hiding, and an author is by definition ahead of
                            // their own cursor.
                            val boards = if (editing) author.mapBoards
                                         else state.boards.filter { it.kind == BoardKind.MAP }
                            val current = boards.firstOrNull { it.boardId == selectedBoardId }
                                ?: boards.firstOrNull()
                            val id = current?.boardId
                            MapPane(
                                boards = boards,
                                boardId = id,
                                pins = id?.let {
                                    if (editing) author.pinsByBoard[it] else state.pinsByBoard[it]
                                }.orEmpty(),
                                // In edit mode the newest version wins outright; in consumption the
                                // ViewModel has already picked the one the listener has reached.
                                artPath = if (editing) {
                                    author.artTimelineByBoard[id].orEmpty().lastOrNull()?.first?.media
                                        ?: current?.artMedia
                                } else id?.let { state.artByBoard[it] },
                                artTimeline = author.artTimelineByBoard[id].orEmpty(),
                                packDir = state.packDir,
                                editing = editing,
                                contentPadding = pad,
                                onSelectBoard = { selectedBoardId = it },
                                onMovePin = { entityId, x, y -> authorViewModel.setPinLocation(entityId, x, y) },
                                onDeletePin = { authorViewModel.deletePin(it) },
                                onAddPin = { showAddPin = true },
                                onCreateBoard = { showAddMap = true },
                                onAddMap = { showAddMap = true },
                                onRenameBoard = { renamingBoard = id },
                                onDeleteBoard = {
                                    id?.let { authorViewModel.deleteBoard(it) }
                                    selectedBoardId = null
                                },
                                onSetImage = {
                                    if (id != null) {
                                        mapImageFor = id
                                        mapImagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                },
                                onDeleteArt = { artId -> id?.let { authorViewModel.deleteBoardArt(it, artId) } }
                            )
                        }

                        dest == CompanionDestination.TIMELINE ->
                            TimelinePane(
                                digest = state.digest,
                                revealedMs = state.revealedMs,
                                bookPositionMs = state.bookPositionMs,
                                scraps = author.unconvertedScraps,
                                editing = editing,
                                contentPadding = pad,
                                onCatchUp = { showCatchUp = true },
                                onQuickCapture = { showQuickCapture = true },
                                onConvertScrap = { convertingScrap = it },
                                onDeleteScrap = { authorViewModel.deleteScrap(it) }
                            )

                        else ->
                            PackPane(
                                state = state,
                                author = author,
                                contentPadding = pad,
                                onCreatePack = { showCreatePack = true },
                                onPregenerate = { showSeed = true; authorViewModel.openSeed() },
                                onShare = { showShareChoice = true },
                                onFork = { authorViewModel.forkPack() },
                                onCreateMap = { authorViewModel.createMapBoard() },
                                onDropConflict = { authorViewModel.dropConflictOp(it) }
                            )
                    }
                }
            }

            // Header, spine and pane share one late slice and one rise, so they arrive as a
            // single incoming layer rather than three things fading independently.
            val chrome = Modifier.graphicsLayer {
                val p = ((progress.value - 0.42f) / 0.58f).coerceIn(0f, 1f)
                alpha = p
                translationY = 10.dp.toPx() * (1f - p)
            }
            val dockMorph = if (morph != null) {
                // byWidth: the transport row and the dock are both near-full-width, so the ratio
                // is ~1 and the travel is almost pure translation — the controls slide down into
                // the dock rather than being scaled into it, which is what they actually do.
                Modifier.morphFrom(source = morph.transport, progress = progress, byWidth = true)
            } else Modifier

            if (landscape) {
                LandscapeDeck(header, spine, pane, transport, chrome, dockMorph)
            } else {
                PortraitDeck(header, spine, pane, transport, chrome, dockMorph)
            }
        }
    }

    // ── dialogs ─────────────────────────────────────────────────────────────────────────────

    if (showCreatePack) {
        CreatePackDialog(
            defaultTitle = author.bookTitle.ifBlank { "Companion" },
            onConfirm = { authorViewModel.createPack(it); showCreatePack = false },
            onDismiss = { showCreatePack = false }
        )
    }
    if (showAddEntity) {
        AddEntityDialog(
            onConfirm = { kind, name -> authorViewModel.addEntity(kind, name); showAddEntity = false },
            onDismiss = { showAddEntity = false }
        )
    }
    addingFactFor?.let { entityId ->
        AddFactDialog(
            // Either list: the dialog can be opened from the author's cast or the revealed one.
            entityName = (author.entities + state.entities)
                .firstOrNull { it.entity.entityId == entityId }?.entity?.name.orEmpty(),
            onConfirm = { field, value, importance ->
                authorViewModel.addFact(entityId, field, value, importance, useCurrentPosition = true)
                addingFactFor = null
            },
            onDismiss = { addingFactFor = null }
        )
    }
    if (showQuickCapture) {
        QuickCaptureDialog(
            onConfirm = { authorViewModel.addQuickCapture(it); showQuickCapture = false },
            onDismiss = { showQuickCapture = false }
        )
    }
    convertingScrap?.let { scrap ->
        ConvertScrapDialog(
            scrap = scrap,
            existingEntities = author.doc?.entities.orEmpty(),
            onConfirm = { entityId, newName, kind, field, importance ->
                authorViewModel.convertScrap(scrap.scrapId, entityId, newName, kind, field, importance)
                convertingScrap = null
            },
            onDismiss = { convertingScrap = null }
        )
    }
    if (showShareChoice) {
        ShareChoiceDialog(
            onShareDataOnly = { authorViewModel.sharePack(full = false); showShareChoice = false },
            onShareFull = { authorViewModel.sharePack(full = true); showShareChoice = false },
            onDismiss = { showShareChoice = false }
        )
    }
    if (showSeed) {
        FandomSeedDialog(
            state = seedState,
            onFetch = { slug, cutoff, limit -> authorViewModel.runSeedPreview(slug, cutoff, limit) },
            onToggle = { authorViewModel.toggleSeedSelection(it) },
            onToggleMap = { authorViewModel.toggleSeedMap() },
            onApply = { authorViewModel.applySeed() },
            onDismiss = { showSeed = false; authorViewModel.closeSeed() }
        )
    }
    if (showAddPin) {
        val board = author.mapBoards.firstOrNull { it.boardId == selectedBoardId }
            ?: author.mapBoards.firstOrNull()
        val pinned = board?.let { author.pinsByBoard[it.boardId].orEmpty() }
            .orEmpty().map { it.entity.entityId }.toSet()
        AddPinDialog(
            candidates = author.doc?.entities.orEmpty().filterNot { it.entityId in pinned },
            packDir = state.packDir,
            onPick = { authorViewModel.addPinFor(it.entityId); showAddPin = false },
            onDismiss = { showAddPin = false }
        )
    }
    if (showAddMap) {
        NameDialog(
            title = "Add a map",
            label = "Name",
            initial = "",
            confirm = "Add",
            onConfirm = { authorViewModel.addMapBoard(it.ifBlank { null }); showAddMap = false },
            onDismiss = { showAddMap = false }
        )
    }
    renamingBoard?.let { id ->
        NameDialog(
            title = "Rename map",
            label = "Name",
            initial = author.mapBoards.firstOrNull { it.boardId == id }?.title.orEmpty(),
            confirm = "Save",
            onConfirm = { authorViewModel.renameBoard(id, it); renamingBoard = null },
            onDismiss = { renamingBoard = null }
        )
    }
    if (showCatchUp) {
        CatchUpDialog(
            targetMs = state.bookPositionMs,
            preview = { viewModel.previewSetRevealPoint(it) },
            onConfirm = { viewModel.confirmSetRevealPoint(state.bookPositionMs) },
            onUndo = { viewModel.undoSetRevealPoint() },
            onDismiss = { showCatchUp = false }
        )
    }
}

// ── layouts ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PortraitDeck(
    header: @Composable (Modifier, Boolean) -> Unit,
    spine: @Composable (Modifier, Boolean) -> Unit,
    pane: @Composable (Modifier, PaddingValues) -> Unit,
    transport: CompanionTransport?,
    chrome: Modifier,
    dockMorph: Modifier
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        header(Modifier.fillMaxWidth().then(chrome), false)
        spine(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
                .height(SPINE_HEIGHT).then(chrome),
            false
        )
        pane(
            Modifier.weight(1f).fillMaxWidth().then(chrome),
            PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
        )
        if (transport != null) {
            CompanionDock(
                transport = transport,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 10.dp)
                    .navigationBarsPadding()
                    .then(dockMorph)
            )
        }
    }
}

/**
 * Landscape: a rail carrying the header, the spine and the dock, and the pane beside it.
 *
 * The rail exists so the two things that must never move — where you navigate from, and where the
 * transport is — stay in one column while the pane changes beside them. Stacking the portrait
 * layout into a landscape window instead would leave a 44dp spine and a 72dp dock eating half of a
 * ~340dp-tall window, with the cast squeezed into what was left.
 */
@Composable
private fun LandscapeDeck(
    header: @Composable (Modifier, Boolean) -> Unit,
    spine: @Composable (Modifier, Boolean) -> Unit,
    pane: @Composable (Modifier, PaddingValues) -> Unit,
    transport: CompanionTransport?,
    chrome: Modifier,
    dockMorph: Modifier
) {
    Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.width(RAIL_WIDTH).fillMaxSize()) {
            header(Modifier.fillMaxWidth().then(chrome), true)
            spine(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .height(SPINE_HEIGHT * CompanionDestination.entries.size)
                    .then(chrome),
                true
            )
            Spacer(Modifier.weight(1f))
            if (transport != null) {
                CompanionDock(
                    transport = transport,
                    compact = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                        .padding(bottom = 10.dp).then(dockMorph)
                )
            }
        }
        pane(
            Modifier.weight(1f).fillMaxSize().then(chrome),
            PaddingValues(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
        )
    }
}

// ── header ──────────────────────────────────────────────────────────────────────────────────

/**
 * Band 1: three controls, never more.
 *
 * The old sheet's header carried a map button, an edit button and a close, and the map button was
 * navigation hiding in the chrome. Everything that navigates now lives in the spine, which leaves
 * this band with the pack's identity, the edit toggle and the way out.
 */
@Composable
private fun DeckHeader(
    title: String,
    subtitle: String,
    showBack: Boolean,
    editing: Boolean,
    canEdit: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onClose: () -> Unit,
    /**
     * Landscape's rail is 176dp wide, and a title beside two icon buttons inside that leaves about
     * eight characters — "Shadow…" over "REV 3 · YO…", which tells the reader nothing. Stacked,
     * the identity gets the full rail width and the controls get their own line under it.
     */
    stacked: Boolean = false,
    modifier: Modifier = Modifier
) {
    val identity: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = deckOn(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = deckOnMuted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    val controls: @Composable () -> Unit = {
        if (showBack) {
            HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Feel.Dismiss, onBack)
        }
        if (canEdit) {
            // The toggle states what it will do next, not what mode you are in — an "Edit" icon
            // that stays "Edit" while editing is the classic ambiguous toggle.
            HeaderIcon(
                if (editing) Icons.Default.EditOff else Icons.Default.Edit,
                if (editing) "Done editing" else "Edit companion",
                Feel.ToggleOn,
                onToggleEdit,
                active = editing
            )
        }
        HeaderIcon(Icons.Default.Close, "Close companion", Feel.Dismiss, onClose)
    }

    if (stacked) {
        Column(modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp)) {
            identity(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { controls() }
        }
    } else {
        Row(
            modifier.padding(start = 18.dp, end = 6.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBack) {
                HeaderIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Feel.Dismiss, onBack)
            }
            identity(Modifier.weight(1f))
            if (canEdit) {
                HeaderIcon(
                    if (editing) Icons.Default.EditOff else Icons.Default.Edit,
                    if (editing) "Done editing" else "Edit companion",
                    Feel.ToggleOn,
                    onToggleEdit,
                    active = editing
                )
            }
            HeaderIcon(Icons.Default.Close, "Close companion", Feel.Dismiss, onClose)
        }
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    feel: Feel,
    onClick: () -> Unit,
    active: Boolean = false
) {
    PressFeel(feel) {
        Box(
            Modifier
                .padding(2.dp)
                .size(38.dp)
                .clip(CircleShape)
                .then(
                    if (active) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                label,
                Modifier.size(19.dp),
                tint = if (active) MaterialTheme.colorScheme.onPrimary else deckOn()
            )
        }
    }
}

/** One spine segment's extent — its height in the vertical rail, the row's height in portrait. */
private val SPINE_HEIGHT = 42.dp

/**
 * Landscape rail width.
 *
 * Sized off its widest contents rather than picked: the dock's compact body needs a 32dp cover, a
 * label and three transport buttons, and the spine needs "Timeline" on one line at labelMedium.
 * Narrower and the dock wraps; wider and the pane loses a grid column on a 640dp window.
 */
private val RAIL_WIDTH = 176.dp

/**
 * The deck, hosted in its own full-screen window.
 *
 * Book Info and the Series page reach the companion from an overflow menu, and neither of them is
 * a `Box` that a full-bleed sibling could reliably cover — they are themselves overlays with their
 * own morph-driven layout. A dialog window sidesteps that entirely and costs nothing here, because
 * the reason the player hosts the deck *in its own tree* is the morph, and there is no transport
 * to morph from at these entry points.
 */
@Composable
fun CompanionDeckDialog(bookId: Long, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize()) {
            CompanionDeck(
                // No travel here: there is no player underneath to travel from.
                progress = remember { mutableStateOf(1f) },
                bookId = bookId,
                transport = null,
                onDismiss = onDismiss
            )
        }
    }
}
