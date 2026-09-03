package com.betteraudio.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.companion.CompanionAuthorRepository
import com.betteraudio.companion.CompanionEditsRepository
import com.betteraudio.companion.CompanionEntityState
import com.betteraudio.companion.EntityStateResolver
import com.betteraudio.companion.CompanionExportService
import com.betteraudio.companion.PackBoardResolver
import com.betteraudio.companion.PackEditsApplier
import com.betteraudio.companion.fandom.FandomSeedService
import com.betteraudio.companion.ResolvedPin
import com.betteraudio.companion.model.BoardKind
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackScrap
import com.betteraudio.companion.model.Presentation
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.CompanionDataStore
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.playback.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject

/**
 * Drives the timeline editor (docs/companion-packs.md §9.1, P3) — quick capture during playback,
 * and entity/fact CRUD once a pack exists. Deliberately a **separate** ViewModel from
 * [CompanionViewModel]: that one only ever surfaces what the reveal cursor allows a *listener* to
 * see, and reusing it here would mean either leaking unrevealed facts into consumption UI state or
 * threading an "author mode" flag through every one of its derivations. The author sees everything
 * in the pack regardless of `revealedMs` — that's the whole point of authoring ahead of where
 * you've listened.
 *
 * Polls rather than streams the pack doc: authoring is a foreground, user-initiated editing
 * session (open the sheet, make some edits, close it), not something that needs to reflect a
 * concurrent writer the way the reveal cursor's 30s tick does — so [reload] is called after every
 * mutation instead of wiring a Flow over disk reads.
 */
@HiltViewModel
class CompanionAuthorViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val companionPackDao: CompanionPackDao,
    private val companionDataStore: CompanionDataStore,
    private val authorRepository: CompanionAuthorRepository,
    private val editsRepository: CompanionEditsRepository,
    private val exportService: CompanionExportService,
    private val seedService: FandomSeedService,
    private val audiobookRepository: AudiobookRepository,
    private val playerController: PlayerController,
    private val settings: SettingsStore
) : ViewModel() {

    data class UiState(
        val bookId: Long = -1L,
        val bookTitle: String = "",
        val loading: Boolean = true,
        val packId: String? = null,
        /** False when this pack was received (§9.2) — mutations route through
         *  [CompanionEditsRepository] (an op appended to `edits.json`) instead of
         *  [CompanionAuthorRepository] (a direct `pack.json` rewrite). See [CompanionAuthorViewModel] kdoc. */
        val isOwn: Boolean = true,
        /** Base + local edits already folded together via [PackEditsApplier] when [isOwn] is false
         *  — the author view always shows the pack as it would actually render, edits included. */
        val doc: CompanionPackDoc? = null,
        val scraps: List<PackScrap> = emptyList(),
        /** Local edits that might disagree with the current base (§9.2) — recomputed fresh on
         *  every [reload] from the raw base + raw edits, so it's always current, never stale
         *  state left over from an import dialog the user already dismissed. Always empty for an
         *  owned pack (nothing to conflict with). */
        val conflicts: List<com.betteraudio.companion.PackEditsApplier.Conflict> = emptyList(),
        /**
         * Every entity in the pack, resolved at `revealedMs = Long.MAX_VALUE` — the author's view.
         *
         * The deck's Cast and Character panes read this instead of [CompanionViewModel]'s list
         * while edit mode is on, and that is not a convenience: the consumption list is
         * spoiler-filtered by construction, so an author who adds a character anchored at the
         * current position and has a reveal cursor of zero (the normal case — the cursor only
         * advances by *listening*) would watch their own edit vanish the moment they made it.
         *
         * This is exactly the gap the old separate editor sheet existed to paper over, and it is
         * why that sheet could not simply be deleted: the deck had to grow an author's view of
         * the same panes rather than a second surface listing the same data.
         */
        val entities: List<CompanionEntityState> = emptyList(),
        /** `boardId` → its art revisions with resolved positions, oldest first. The editor's
         *  revision strip. Unfiltered, like everything else on this state. */
        val artTimelineByBoard: Map<String, List<Pair<com.betteraudio.companion.model.BoardArt, Long>>> = emptyMap(),
        /** Pins resolved at `revealedMs = Long.MAX_VALUE` — the author sees every pin ever placed
         *  regardless of the reveal cursor, same rationale as seeing every entity/fact (class kdoc). */
        val pinsByBoard: Map<String, List<ResolvedPin>> = emptyMap()
    ) {
        val hasPack: Boolean get() = packId != null
        val unconvertedScraps: List<PackScrap> get() = scraps.filterNot { it.converted }
        /** Every map in the pack, in authoring order. Some stories need more than one — a world
         *  map and a city map are different documents, not two states of the same one (which is
         *  what [com.betteraudio.companion.model.BoardArt] is for). */
        val mapBoards: List<com.betteraudio.companion.model.PackBoard>
            get() = doc?.boards.orEmpty().filter { it.kind == BoardKind.MAP }
        val mapBoard: com.betteraudio.companion.model.PackBoard? get() = mapBoards.firstOrNull()
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Emits the exported file once [sharePack] finishes — the sheet collects this and fires the
     *  actual `ACTION_SEND` intent, keeping Android `Intent`/`Context` usage out of the ViewModel. */
    private val _shareEvent = MutableSharedFlow<File>()
    val shareEvent: SharedFlow<File> = _shareEvent.asSharedFlow()

    private var boundBookId: Long = -1L

    fun bind(bookId: Long) {
        if (boundBookId == bookId && !_uiState.value.loading) return
        boundBookId = bookId
        viewModelScope.launch { reload(bookId) }
    }

    /** Book-global ms to anchor a new capture at: the live playback position if this book is the
     *  one currently playing, else its saved resume position, else the very start. */
    private suspend fun currentAnchorMs(bookId: Long): Long {
        val playback = playerController.playbackState.value
        if (playback.bookId == bookId) {
            return playerController.positionState.value.bookPositionMs
        }
        return audiobookRepository.getRevealedMs(bookId)
    }

    private suspend fun reload(bookId: Long) {
        _uiState.update { it.copy(loading = true) }
        val book = bookDao.getBookOnce(bookId)
        if (book == null) {
            _uiState.value = UiState(bookId = bookId, loading = false)
            return
        }
        val targetKey = BookDataPaths.relPath(book.folderPath, settings.currentLibraryFolder)
        // Any enabled pack for this book, own or received — matches CompanionViewModel's own
        // "one active pack" selection (§2). Editing a received pack is real P6 functionality, not
        // something this screen should hide behind an isOwn filter (that would make the pencil
        // icon silently do nothing for the common "friend sent me a pack" case).
        val pack = companionPackDao.getForTargetOnce(targetKey).firstOrNull { it.enabled }
        if (pack == null) {
            _uiState.value = UiState(bookId = bookId, bookTitle = book.displayTitle, loading = false)
            return
        }
        // A pack folder deleted from outside the app leaves an enabled registry row pointing at
        // nothing. Left alone it renders as a document-less pack — and, if it was received, one
        // whose owner-only actions are all hidden. Prune it and fall back to the no-pack state,
        // which is what the consumption sheet is already showing.
        if (authorRepository.dropIfOrphaned(bookId, pack.packId)) {
            _uiState.value = UiState(bookId = bookId, bookTitle = book.displayTitle, loading = false)
            return
        }
        val baseDoc = companionDataStore.readBookPack(bookId, pack.packId)
        val rawEdits = if (!pack.isOwn) companionDataStore.readBookPackEdits(bookId, pack.packId) else null
        val doc = if (baseDoc != null && rawEdits != null) PackEditsApplier.apply(baseDoc, rawEdits) else baseDoc
        val conflicts = if (baseDoc != null && rawEdits != null) PackEditsApplier.detectConflicts(baseDoc, rawEdits) else emptyList()
        val scraps = authorRepository.listScraps(bookId, pack.packId)
        val pinsByBoard = doc?.boards.orEmpty().associate { b ->
            b.boardId to PackBoardResolver.resolvePins(doc!!, b, book, revealedMs = Long.MAX_VALUE)
        }
        val artTimelineByBoard = doc?.boards.orEmpty().associate { b ->
            b.boardId to PackBoardResolver.artTimeline(b, book)
        }
        // Resolved states first, then every entity the resolver dropped.
        //
        // EntityStateResolver keys off *facts*, because in consumption a character is introduced
        // by the first thing you learn about them — an entity with no facts is, correctly,
        // nobody yet. An author's just-created character has exactly that shape, so resolving
        // alone would make "Add" appear to do nothing. They are appended with empty fields, which
        // is what they are: named, and nothing known about them so far.
        val entities = doc?.let { d ->
            val resolved = EntityStateResolver.resolve(d, book, revealedMs = Long.MAX_VALUE)
            val seen = resolved.map { it.entity.entityId }.toSet()
            resolved + d.entities.filterNot { it.entityId in seen }
                .map { CompanionEntityState(entity = it, fields = emptyMap(), firstRevealedMs = 0L) }
        }.orEmpty()
        _uiState.value = UiState(
            bookId = bookId,
            bookTitle = book.displayTitle,
            loading = false,
            packId = pack.packId,
            isOwn = pack.isOwn,
            doc = doc,
            scraps = scraps,
            conflicts = conflicts,
            entities = entities,
            artTimelineByBoard = artTimelineByBoard,
            pinsByBoard = pinsByBoard
        )
    }

    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        value = block(value)
    }

    fun createPack(title: String) {
        val bookId = boundBookId
        viewModelScope.launch {
            authorRepository.createBookPack(bookId, title)
            reload(bookId)
        }
    }

    // ── Pregenerate from a wiki (§11) ────────────────────────────────────

    data class SeedState(
        val loading: Boolean = false,
        val detected: FandomSeedService.Detected? = null,
        val preview: FandomSeedService.Preview? = null,
        /** Names the listener has ticked. Defaults to all of them once a preview arrives. */
        val selected: Set<String> = emptySet(),
        /** Whether the wiki's map is ticked. Its own flag rather than a member of [selected],
         *  because a map is not a character and a name collision would silently toggle one. */
        val includeMap: Boolean = true,
        val error: String? = null,
        /** Facts written by the last [applySeed], for the confirmation line. */
        val applied: Int? = null,
        /** Portraits and map written by the last [applySeed], for the same line. */
        val appliedImages: Int = 0
    )

    private val _seedState = MutableStateFlow(SeedState())
    val seedState: StateFlow<SeedState> = _seedState.asStateFlow()

    /** Opens the pregenerate dialog and fills in the wiki/chapter guesses. */
    fun openSeed() {
        val bookId = boundBookId
        _seedState.value = SeedState(loading = true)
        viewModelScope.launch {
            val detected = seedService.detect(bookId)
            _seedState.value = SeedState(
                loading = false,
                detected = detected,
                error = if (detected?.wiki == null) {
                    "No wiki guessed from the title — enter one below."
                } else null
            )
        }
    }

    fun closeSeed() {
        _seedState.value = SeedState()
    }

    fun toggleSeedMap() {
        _seedState.update { it.copy(includeMap = !it.includeMap) }
    }

    fun toggleSeedSelection(name: String) {
        _seedState.update { state ->
            state.copy(
                selected = if (name in state.selected) state.selected - name else state.selected + name
            )
        }
    }

    fun runSeedPreview(slug: String, cutoffChapter: Int, limit: Int) {
        val bookId = boundBookId
        if (slug.isBlank()) return
        _seedState.update { it.copy(loading = true, error = null, preview = null, applied = null) }
        viewModelScope.launch {
            when (val result = seedService.preview(bookId, slug.trim(), cutoffChapter, limit)) {
                is FandomSeedService.SeedResult.Success -> _seedState.update {
                    it.copy(
                        loading = false,
                        preview = result.preview,
                        selected = result.preview.characters.map { c -> c.name }.toSet(),
                        includeMap = result.preview.map != null
                    )
                }
                is FandomSeedService.SeedResult.Error -> _seedState.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    /**
     * Writes the ticked characters into the pack — creating the pack first if this book has none,
     * so pregenerating is a valid *first* action on a book rather than something you can only do
     * after hand-making an empty pack.
     *
     * Received packs are excluded: a seed adds entities wholesale, and the edits overlay (§9.2) is
     * an op log for adjusting someone else's pack, not a place to graft a cast onto it.
     */
    fun applySeed() {
        val bookId = boundBookId
        val state = _seedState.value
        val preview = state.preview ?: return
        val chosen = preview.characters.filter { it.name in state.selected }
        if (chosen.isEmpty()) return
        _seedState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val packId = _uiState.value.packId
                ?: authorRepository.createBookPack(bookId, _uiState.value.bookTitle)
            if (packId == null) {
                _seedState.update { it.copy(loading = false, error = "Could not create a pack for this book.") }
                return@launch
            }
            // Portraits are downloaded and written to disk BEFORE the document write, so the
            // whole cast — names, facts and faces — still lands as the single revision
            // addSeededEntities exists to produce. Fetching them afterwards would mean a second
            // pass of per-entity edits and a revision each, which is precisely what that method's
            // kdoc argues against.
            var imagesWritten = 0
            val portraitPaths = HashMap<String, String>()
            for (candidate in chosen) {
                val url = candidate.portrait?.url ?: continue
                val bytes = seedService.downloadImage(url) ?: continue
                val rel = companionDataStore.writeBookPackMedia(
                    bookId, packId, "${slugify(candidate.name)}.${extensionOf(url)}", bytes
                ) ?: continue
                portraitPaths[candidate.name] = rel
                imagesWritten++
            }

            val written = if (_uiState.value.isOwn) {
                val seeds = chosen.map { candidate ->
                    CompanionAuthorRepository.SeedEntity(
                        name = candidate.name,
                        kind = EntityKind.CHARACTER,
                        mediaPath = portraitPaths[candidate.name],
                        facts = candidate.facts.map { fact ->
                            CompanionAuthorRepository.SeedFact(
                                field = fact.field,
                                value = fact.value,
                                anchorGlobalMs = fact.globalMs
                            )
                        }
                    )
                }
                authorRepository.addSeededEntities(bookId, packId, seeds)
            } else {
                // A received pack's base file is immutable (§9.2), so the seed becomes ops on the
                // edits overlay instead — one AddEntity plus one AddFact each. Slower and chattier
                // than the owned path's single rewrite, but `edits.json` is an op log by design,
                // and refusing here was the actual bug: seeding is most useful on exactly the
                // packs someone else started.
                var count = 0
                for (candidate in chosen) {
                    val entityId = editsRepository.addEntity(
                        bookId, packId, EntityKind.CHARACTER, candidate.name
                    ) ?: continue
                    portraitPaths[candidate.name]?.let { rel ->
                        editsRepository.editEntity(bookId, packId, entityId, media = rel)
                    }
                    for (fact in candidate.facts) {
                        val ok = editsRepository.addFact(
                            bookId, packId, entityId, fact.field, fact.value,
                            fact.globalMs, Importance.NOTABLE
                        )
                        if (ok != null) count++
                    }
                }
                count
            }

            if (seedMap(bookId, packId, preview.map.takeIf { state.includeMap })) imagesWritten++

            reload(bookId)
            val images = imagesWritten
            _seedState.update {
                if (written == null) it.copy(loading = false, error = "Could not write to this pack.")
                else it.copy(loading = false, applied = written, appliedImages = images, preview = null)
            }
        }
    }

    /**
     * Downloads the wiki's map and gives the pack a board wearing it.
     *
     * Owned packs only, for the same reason [createMapBoard] is: §9.2's op vocabulary can adjust an
     * existing board but cannot author a new one, so on a received pack there is nowhere to put a
     * map that did not already have a board. It fails quietly rather than reporting an error — the
     * cast is the point of the seed and a missing backdrop is a board falling back to its flat
     * [com.betteraudio.companion.model.ArtTone] ground, which is a design that already exists.
     *
     * @return true if art was actually written.
     */
    private suspend fun seedMap(
        bookId: Long,
        packId: String,
        map: com.betteraudio.companion.fandom.FandomWikiClient.ImageRef?
    ): Boolean {
        if (map == null || !_uiState.value.isOwn) return false
        val bytes = seedService.downloadImage(map.url) ?: return false
        val rel = companionDataStore.writeBookPackMedia(
            bookId, packId, "map.${extensionOf(map.url)}", bytes
        ) ?: return false
        // Re-seeding re-skins the board the pack already has rather than growing a second one:
        // the pins live on that board, and a fresh board would strand every one of them.
        val existing = _uiState.value.doc?.boards?.firstOrNull { it.kind == BoardKind.MAP }
        return if (existing != null) {
            authorRepository.setBoardArt(bookId, packId, existing.boardId, rel)
        } else {
            authorRepository.addBoard(
                bookId, packId, BoardKind.MAP, Presentation.FULLSCREEN,
                artMedia = rel,
                // Named from the wiki's own filename — "DREAM REALM MAP.png" -> "Dream Realm Map".
                // A pack that later gains a second map then already has two distinguishable names
                // instead of "Map" and "Map 2".
                title = prettyFileTitle(map.fileTitle)
            ) != null
        }
    }

    /** "File:DREAM REALM MAP.png" -> "Dream Realm Map". */
    private fun prettyFileTitle(fileTitle: String): String =
        fileTitle.removePrefix("File:").substringBeforeLast('.')
            .replace('_', ' ')
            .split(' ').filter { it.isNotBlank() }
            .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
            .take(40)
            .ifBlank { "Map" }

    /** "The Forgotten Shore" -> "the_forgotten_shore" — a filename, not a display string. */
    private fun slugify(name: String): String =
        name.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_').take(40)
            .ifBlank { "entity" }

    /** Extension from a URL path, defaulting to jpg. Fandom URLs carry a `?cb=` cache buster and
     *  sometimes a `/revision/latest` suffix, so neither can be assumed away. */
    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore("/revision/")
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("png", "jpg", "jpeg", "webp", "gif")) ext else "jpg"
    }

    fun addQuickCapture(note: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        viewModelScope.launch {
            val anchorMs = currentAnchorMs(bookId)
            authorRepository.addScrap(bookId, packId, note, anchorMs)
            reload(bookId)
        }
    }

    fun deleteScrap(scrapId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        viewModelScope.launch {
            authorRepository.deleteScrap(bookId, packId, scrapId)
            reload(bookId)
        }
    }

    fun convertScrap(
        scrapId: String,
        entityId: String?,
        newEntityName: String?,
        newEntityKind: EntityKind,
        field: String,
        importance: Importance
    ) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            if (isOwn) {
                authorRepository.convertScrap(bookId, packId, scrapId, entityId, newEntityName, newEntityKind, field, importance)
            } else {
                val scrap = authorRepository.listScraps(bookId, packId).firstOrNull { it.scrapId == scrapId }
                if (scrap != null) {
                    editsRepository.convertScrap(bookId, packId, scrap.note, scrap.anchor, entityId, newEntityName, newEntityKind, field, importance)
                    authorRepository.deleteScrap(bookId, packId, scrapId) // no per-op "mark converted" path off the edits repo; drop it instead
                }
            }
            reload(bookId)
        }
    }

    fun addEntity(kind: EntityKind, name: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        if (name.isBlank()) return
        viewModelScope.launch {
            if (isOwn) authorRepository.addEntity(bookId, packId, kind, name) else editsRepository.addEntity(bookId, packId, kind, name)
            reload(bookId)
        }
    }

    /** Removes an owned entity outright; **hides** (never deletes) one on a received pack — see
     *  [CompanionEditsRepository.hideEntity]'s kdoc on why that's the correct received-pack op. */
    fun deleteEntity(entityId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            if (isOwn) authorRepository.deleteEntity(bookId, packId, entityId) else editsRepository.hideEntity(bookId, packId, entityId)
            reload(bookId)
        }
    }

    /** Renames a character in place, or changes what kind of thing it is. */
    fun renameEntity(entityId: String, name: String, kind: EntityKind? = null) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        if (name.isBlank()) return
        viewModelScope.launch {
            if (isOwn) authorRepository.editEntity(bookId, packId, entityId, name = name, kind = kind)
            else editsRepository.editEntity(bookId, packId, entityId, name = name, kind = kind)
            reload(bookId)
        }
    }

    /**
     * Gives a character a face: writes [bytes] into the pack's own `media/` directory and points
     * the entity at the resulting **pack-relative** path.
     *
     * The bytes land in the base pack's directory even for a *received* pack, while the reference
     * to them goes through the edits overlay like any other change. That asymmetry is deliberate:
     * §9.3's "share my edits only" is a 4 KB file, and it stays 4 KB precisely because
     * `edits.json` carries paths and never payloads. The consequence — a portrait added to a
     * received pack does not travel with a shared edits file — is the right trade: the recipient
     * of those edits already has the base pack, and if they do not have the image either, a
     * missing portrait falls back to the crest rather than breaking anything.
     *
     * [fileName] is sanitised by the store, and is prefixed with the entity id so two characters
     * whose source images were both called `image.jpg` cannot overwrite each other.
     */
    fun setEntityPortrait(entityId: String, fileName: String, bytes: ByteArray) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            val rel = companionDataStore.writeBookPackMedia(
                bookId, packId, "${entityId.take(8)}_$fileName", bytes
            ) ?: return@launch
            if (isOwn) authorRepository.editEntity(bookId, packId, entityId, media = rel)
            else editsRepository.editEntity(bookId, packId, entityId, media = rel)
            reload(bookId)
        }
    }

    fun addFact(entityId: String, field: String, value: String, importance: Importance, useCurrentPosition: Boolean) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        if (field.isBlank() || value.isBlank()) return
        viewModelScope.launch {
            val anchorMs = if (useCurrentPosition) currentAnchorMs(bookId) else 0L
            if (isOwn) {
                authorRepository.addFact(bookId, packId, entityId, field, value, anchorMs, importance)
            } else {
                editsRepository.addFact(bookId, packId, entityId, field, value, anchorMs, importance)
            }
            reload(bookId)
        }
    }

    fun deleteFact(factId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            if (isOwn) authorRepository.deleteFact(bookId, packId, factId) else editsRepository.deleteFact(bookId, packId, factId)
            reload(bookId)
        }
    }

    // ── Board / pins (P5) ────────────────────────────────────────────────

    /** Creates the pack's map board if it doesn't already have one. Own packs only — a received
     *  pack's boards come from the author; the edits overlay has no `addBoard` op (§9.2's op
     *  vocabulary covers adjusting existing boards, not authoring new ones from scratch). */
    /**
     * Adds a map, optionally named.
     *
     * Not "create *the* map": a pack can hold several, and the deck switches between them. A world
     * map and a city map are separate documents — using one board and swapping its art would put
     * every pin from both on whichever picture happened to be showing.
     */
    fun addMapBoard(title: String? = null) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        if (!_uiState.value.isOwn) return
        viewModelScope.launch {
            authorRepository.addBoard(
                bookId, packId, BoardKind.MAP, Presentation.FULLSCREEN, title = title
            )
            reload(bookId)
        }
    }

    /** Kept for the "this pack has no map yet" affordance, which does not ask for a name. */
    fun createMapBoard() = addMapBoard(null)

    fun renameBoard(boardId: String, title: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        if (title.isBlank() || !_uiState.value.isOwn) return
        viewModelScope.launch {
            authorRepository.setBoardTitle(bookId, packId, boardId, title.trim())
            reload(bookId)
        }
    }

    fun deleteBoard(boardId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        if (!_uiState.value.isOwn) return
        viewModelScope.launch {
            authorRepository.deleteBoard(bookId, packId, boardId)
            reload(bookId)
        }
    }

    /**
     * Sets a board's picture from an image the user picked.
     *
     * [atCurrentPosition] is the whole difference between "this is what the map looks like" and
     * "this is what the map looks like *from here on*". False replaces the base art, which is true
     * from the first second of the book; true adds a revision anchored where the listener is
     * standing, which is how a map evolves.
     */
    fun setBoardImage(
        boardId: String,
        fileName: String,
        bytes: ByteArray,
        atCurrentPosition: Boolean,
        label: String? = null
    ) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        if (!_uiState.value.isOwn) return
        viewModelScope.launch {
            val rel = companionDataStore.writeBookPackMedia(
                bookId, packId, "board_${boardId.take(8)}_${System.currentTimeMillis()}_$fileName", bytes
            ) ?: return@launch
            if (atCurrentPosition) {
                authorRepository.addBoardArt(
                    bookId, packId, boardId, rel, currentAnchorMs(bookId), label
                )
            } else {
                authorRepository.setBoardArt(bookId, packId, boardId, rel)
            }
            reload(bookId)
        }
    }

    fun deleteBoardArt(boardId: String, artId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        viewModelScope.launch {
            authorRepository.deleteBoardArt(bookId, packId, boardId, artId)
            reload(bookId)
        }
    }
    fun addPinFor(entityId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val boardId = _uiState.value.mapBoard?.boardId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            if (isOwn) authorRepository.addPinElement(bookId, packId, boardId, entityId) else editsRepository.addPinElement(bookId, packId, boardId, entityId)
            reload(bookId)
        }
    }

    /** Commits a drag — records where [entityId] is as of the current playback position. Edits the
     *  existing fact instead of adding a new one when one already exists at the exact same anchor,
     *  so repeatedly nudging a pin in one editing session doesn't spam waypoints. */
    fun setPinLocation(entityId: String, xUnits: Float, yUnits: Float) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            val anchorMs = currentAnchorMs(bookId)
            val value = PackBoardResolver.formatCoords(xUnits, yUnits)
            val existing = _uiState.value.doc?.facts?.firstOrNull {
                it.entityId == entityId && it.field == "location" && it.anchor.globalMs == anchorMs
            }
            if (isOwn) {
                if (existing != null) {
                    authorRepository.editFact(bookId, packId, existing.factId, value = value)
                } else {
                    authorRepository.addFact(bookId, packId, entityId, "location", value, anchorMs, Importance.NOTABLE)
                }
            } else {
                editsRepository.editOrAddFact(bookId, packId, existing?.factId, entityId, "location", value, anchorMs, Importance.NOTABLE)
            }
            reload(bookId)
        }
    }

    fun deletePin(elementId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        val boardId = _uiState.value.mapBoard?.boardId ?: return
        val isOwn = _uiState.value.isOwn
        viewModelScope.launch {
            if (isOwn) authorRepository.deleteBoardElement(bookId, packId, boardId, elementId) else editsRepository.deleteBoardElement(bookId, packId, boardId, elementId)
            reload(bookId)
        }
    }

    /** "Take theirs" (§9.2) — drops one conflicting local edit, letting the update's value show
     *  through for that item. "Keep mine" needs no call at all — it's simply not clicking this. */
    fun dropConflictOp(opId: String) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        viewModelScope.launch {
            editsRepository.dropOp(bookId, packId, opId)
            reload(bookId)
        }
    }

    /** "Duplicate as my own" (§9.2) — only meaningful for a received pack. */
    fun forkPack() {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        if (_uiState.value.isOwn) return
        viewModelScope.launch {
            val newPackId = editsRepository.forkPack(bookId, packId)
            if (newPackId != null) reload(bookId)
        }
    }

    /** Exports the currently-bound pack — DATA_ONLY (pack data only) or FULL (audio bundled too,
     *  §10.1/P7) — and emits it on [shareEvent] for the UI to hand off to the share sheet.
     *  Silently no-ops on failure in this pass — a surfaced error toast is a small follow-up, not
     *  a correctness gap. */
    fun sharePack(full: Boolean = false) {
        val bookId = boundBookId
        val packId = _uiState.value.packId ?: return
        viewModelScope.launch {
            val result = if (full) exportService.exportBookPackFull(bookId, packId) else exportService.exportBookPack(bookId, packId)
            if (result is CompanionExportService.ExportResult.Success) {
                _shareEvent.emit(result.file)
            }
        }
    }
}
