package com.betteraudio.companion

import com.betteraudio.companion.model.BoardArt
import com.betteraudio.companion.model.BoardKind
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.EntityKind
import com.betteraudio.companion.model.FactAnchor
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.companion.model.PackBoardElement
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.companion.model.PackMember
import com.betteraudio.companion.model.PackScope
import com.betteraudio.companion.model.PackScrap
import com.betteraudio.companion.model.PackScrapsDoc
import com.betteraudio.companion.model.Presentation
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.CompanionPack
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.CompanionDataStore
import com.betteraudio.data.diskstore.DiskMirror
import com.betteraudio.data.settings.SettingsStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authoring writes for a pack this device owns (docs/companion-packs.md §9.1, P3): quick capture,
 * entity/fact CRUD, and creating a BOOK-scoped pack in the first place. Everything here mutates
 * `pack.json` directly — the [CompanionPack.isOwn] check on every mutation is what keeps this from
 * ever being the write path for a *received* pack, whose base file must stay immutable (§9.2 is
 * the P6 concern of layering `edits.json` on top instead).
 *
 * **Revision bumps on every structural edit, not just at export.** §4's doc comment on
 * [CompanionPackDoc.revision] says "bumped by the owner on export" — true for what a *recipient*
 * ever sees, but not sufficient for local authoring: §9.2's conflict detection is keyed on
 * `PackFact.rev` ("the revision this fact last changed in"), and that value has to be correct the
 * moment the fact is authored, not retroactively patched in at export time. Bumping on every save
 * makes `fact.rev` trivially correct and — as a free side effect — keeps
 * [com.betteraudio.ui.companion.CompanionViewModel]'s `(packId, revision)`-keyed doc cache from
 * ever serving a stale doc after an authoring edit. Revisions skipped between two real exports are
 * harmless; nothing depends on them being contiguous, only monotonic.
 */
@Singleton
class CompanionAuthorRepository @Inject constructor(
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val companionPackDao: CompanionPackDao,
    private val companionDataStore: CompanionDataStore,
    private val diskMirror: DiskMirror,
    private val settings: SettingsStore
) {
    // ── Pack lifecycle ─────────────────────────────────────────────────────

    /** Creates a new BOOK-scoped pack authored on this device and registers it. Returns the new
     *  pack's id, or null if [bookId] doesn't resolve to a real book or the write failed. */
    suspend fun createBookPack(bookId: Long, title: String, authorHandle: String? = null): String? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        val member = PackMember(
            bookRef = "m1",
            title = book.displayTitle,
            author = book.displayAuthor,
            seriesOrder = book.seriesOrder,
            durationMs = book.totalDurationMs,
            fileCount = book.fileCount
            // fileKeys deliberately left empty here — computed at export time (P4), not
            // authoring time, so creating a pack never touches every audio file on disk.
        )
        val doc = CompanionPackDoc(
            scope = PackScope.BOOK,
            title = title.ifBlank { book.displayTitle },
            authorHandle = authorHandle,
            members = listOf(member)
        )
        diskMirror.flushBookPack(bookId, doc)
        val targetKey = BookDataPaths.relPath(book.folderPath, settings.currentLibraryFolder)
        companionPackDao.upsert(
            CompanionPack(
                packId = doc.packId,
                scope = "BOOK",
                targetKey = targetKey,
                title = doc.title,
                authorHandle = doc.authorHandle,
                revision = doc.revision,
                enabled = true,
                isOwn = true
            )
        )
        return doc.packId
    }

    suspend fun deletePack(bookId: Long, packId: String) {
        companionDataStore.deleteBookPack(bookId, packId)
        companionPackDao.deleteById(packId)
    }

    /**
     * Drops the registry row for a pack whose `pack.json` is gone, returning true if it did.
     *
     * Disk is the source of truth (see CLAUDE.md's storage model), so a pack folder deleted from
     * outside the app — a file manager, a sync client, the user tidying up — leaves a Room row
     * pointing at nothing. That orphan is not harmless: it is still `enabled`, so the editor picks
     * it as "the" pack for the book and renders a pack that has no document — in the observed case
     * a *received* pack, which additionally hid every owner-only action behind [CompanionPack.isOwn]
     * while the consumption sheet next to it correctly said there was no pack at all.
     *
     * Deleting the row cannot lose anything: the data it referenced is already gone.
     */
    suspend fun dropIfOrphaned(bookId: Long, packId: String): Boolean {
        if (companionDataStore.readBookPack(bookId, packId) != null) return false
        companionPackDao.deleteById(packId)
        return true
    }

    // ── Entities ───────────────────────────────────────────────────────────

    suspend fun addEntity(bookId: Long, packId: String, kind: EntityKind, name: String): String? =
        mutate(bookId, packId) { doc, _ ->
            val entity = PackEntity(entityId = UUID.randomUUID().toString(), kind = kind, name = name)
            (doc.copy(entities = doc.entities + entity)) to entity.entityId
        }

    suspend fun editEntity(
        bookId: Long,
        packId: String,
        entityId: String,
        name: String? = null,
        kind: EntityKind? = null,
        /** Pack-relative portrait path, e.g. `media/sunny.webp` — relative so the portrait
         *  survives export/import, which an absolute device path would not. */
        media: String? = null
    ): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(entities = doc.entities.map {
                if (it.entityId == entityId)
                    it.copy(name = name ?: it.name, kind = kind ?: it.kind, media = media ?: it.media)
                else it
            }) to true
        } ?: false

    /** Also drops every fact that referenced this entity — an orphaned fact has nothing to attach
     *  its value to in any consumption UI. */
    suspend fun deleteEntity(bookId: Long, packId: String, entityId: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(
                entities = doc.entities.filterNot { it.entityId == entityId },
                facts = doc.facts.filterNot { it.entityId == entityId }
            ) to true
        } ?: false

    // ── Facts ──────────────────────────────────────────────────────────────

    suspend fun addFact(
        bookId: Long,
        packId: String,
        entityId: String,
        field: String,
        value: String,
        anchorGlobalMs: Long,
        importance: Importance
    ): String? {
        val ratio = ratioFor(bookId, anchorGlobalMs)
        return mutate(bookId, packId) { doc, newRevision ->
            val bookRef = doc.members.firstOrNull()?.bookRef ?: "m1"
            val fact = PackFact(
                factId = UUID.randomUUID().toString(),
                entityId = entityId,
                field = field,
                value = value,
                anchor = FactAnchor(bookRef = bookRef, globalMs = anchorGlobalMs, ratio = ratio),
                importance = importance,
                origin = doc.packId,
                rev = newRevision
            )
            doc.copy(facts = doc.facts + fact) to fact.factId
        }
    }

    suspend fun editFact(
        bookId: Long,
        packId: String,
        factId: String,
        value: String? = null,
        importance: Importance? = null
    ): Boolean = mutate(bookId, packId) { doc, newRevision ->
        doc.copy(facts = doc.facts.map {
            if (it.factId == factId) {
                it.copy(value = value ?: it.value, importance = importance ?: it.importance, rev = newRevision)
            } else it
        }) to true
    } ?: false

    suspend fun deleteFact(bookId: Long, packId: String, factId: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(facts = doc.facts.filterNot { it.factId == factId }) to true
        } ?: false

    // ── Seeding (§9.4) ──────────────────────────────────────────────────────

    /** One drafted character from [com.betteraudio.companion.fandom.FandomSeedService]. */
    data class SeedEntity(
        val name: String,
        val kind: EntityKind,
        val facts: List<SeedFact>,
        /** Pack-relative portrait path, already written into `media/` by the caller. */
        val mediaPath: String? = null
    )

    data class SeedFact(
        val field: String,
        val value: String,
        val anchorGlobalMs: Long,
        val importance: Importance = Importance.NOTABLE
    )

    /**
     * Adds a whole drafted cast in **one** write.
     *
     * Looping [addEntity]/[addFact] would be the obvious implementation and the wrong one: each
     * goes through [mutate], so ten characters with eight facts each would rewrite `pack.json`
     * ninety times and burn ninety revisions — and since `fact.rev` is the basis of §9.2's conflict
     * detection, those revisions are not free noise, they are ninety distinct versions a recipient
     * could later be asked to reconcile against. A seed is one authoring act and lands as one.
     *
     * An entity whose name already exists gains the new facts instead of being duplicated, so
     * re-running the seed after listening further tops up the cast rather than doubling it.
     *
     * @return the number of facts actually written, or null if the pack isn't writable here.
     */
    suspend fun addSeededEntities(
        bookId: Long,
        packId: String,
        seeds: List<SeedEntity>
    ): Int? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        return mutate(bookId, packId) { doc, newRevision ->
            val bookRef = doc.members.firstOrNull()?.bookRef ?: "m1"
            val entities = doc.entities.toMutableList()
            val facts = doc.facts.toMutableList()
            var written = 0

            for (seed in seeds) {
                val existing = entities.firstOrNull { it.name.equals(seed.name, ignoreCase = true) }
                val entityId = existing?.entityId ?: UUID.randomUUID().toString().also { id ->
                    entities += PackEntity(
                        entityId = id, kind = seed.kind, name = seed.name, media = seed.mediaPath
                    )
                }
                // Re-running the seed tops a portrait up but never replaces one: a face the author
                // chose by hand outranks anything a wiki offers, and this path cannot tell the
                // difference between "no portrait yet" and "deliberately none" except by the
                // absence itself.
                if (existing != null && existing.media.isNullOrBlank() && seed.mediaPath != null) {
                    val index = entities.indexOfFirst { it.entityId == entityId }
                    if (index >= 0) entities[index] = entities[index].copy(media = seed.mediaPath)
                }
                for (seedFact in seed.facts) {
                    // Re-running the seed must not restate a fact this pack already carries at the
                    // same position — that would badge the reveal digest for nothing.
                    val duplicate = facts.any {
                        it.entityId == entityId && it.field == seedFact.field &&
                            it.value == seedFact.value
                    }
                    if (duplicate) continue
                    facts += PackFact(
                        factId = UUID.randomUUID().toString(),
                        entityId = entityId,
                        field = seedFact.field,
                        value = seedFact.value,
                        anchor = FactAnchor(
                            bookRef = bookRef,
                            globalMs = seedFact.anchorGlobalMs,
                            ratio = ratioFor(book, seedFact.anchorGlobalMs)
                        ),
                        importance = seedFact.importance,
                        origin = doc.packId,
                        rev = newRevision
                    )
                    written++
                }
            }
            doc.copy(entities = entities, facts = facts) to written
        }
    }

    // ── Boards (§4.1/§5, P5) ───────────────────────────────────────────────

    /** Creates a board — for P5's map, always [BoardKind.MAP] + [Presentation.FULLSCREEN]. Returns
     *  the new board's id. */
    suspend fun addBoard(
        bookId: Long,
        packId: String,
        kind: BoardKind,
        presentation: Presentation,
        /** Pack-relative art path, already written into `media/` by the caller. */
        artMedia: String? = null,
        title: String? = null
    ): String? =
        mutate(bookId, packId) { doc, _ ->
            val board = PackBoard(
                boardId = UUID.randomUUID().toString(),
                kind = kind,
                presentation = presentation,
                title = title,
                artMedia = artMedia
            )
            doc.copy(boards = doc.boards + board) to board.boardId
        }

    suspend fun setBoardTitle(bookId: Long, packId: String, boardId: String, title: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(
                boards = doc.boards.map { if (it.boardId == boardId) it.copy(title = title) else it }
            ) to true
        } ?: false

    /**
     * Removes a board and every pin marker on it.
     *
     * The `location` facts those pins read are deliberately left alone: a fact is a statement about
     * where a character was, and it stays true whether or not a board is drawing it. Deleting them
     * here would silently erase authored story data as a side effect of tidying up a picture.
     */
    suspend fun deleteBoard(bookId: Long, packId: String, boardId: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(boards = doc.boards.filterNot { it.boardId == boardId }) to true
        } ?: false

    /**
     * Adds a timed backdrop to a board (see [com.betteraudio.companion.model.BoardArt]).
     *
     * Anchored exactly like a fact, so the map changes at the moment the story changes it. Two
     * revisions at the same position replace rather than stack — an author correcting the picture
     * they just added means "this one instead", not "two maps here".
     */
    suspend fun addBoardArt(
        bookId: Long,
        packId: String,
        boardId: String,
        media: String,
        anchorGlobalMs: Long,
        label: String? = null
    ): String? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        return mutate(bookId, packId) { doc, _ ->
            val art = BoardArt(
                artId = UUID.randomUUID().toString(),
                media = media,
                anchor = FactAnchor(
                    bookRef = doc.members.firstOrNull()?.bookRef ?: "m1",
                    globalMs = anchorGlobalMs,
                    ratio = ratioFor(book, anchorGlobalMs)
                ),
                label = label
            )
            doc.copy(
                boards = doc.boards.map { board ->
                    if (board.boardId != boardId) board
                    else board.copy(
                        artRevisions = board.artRevisions
                            .filterNot { it.anchor.globalMs == anchorGlobalMs } + art
                    )
                }
            ) to art.artId
        }
    }

    suspend fun deleteBoardArt(bookId: Long, packId: String, boardId: String, artId: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(
                boards = doc.boards.map { board ->
                    if (board.boardId != boardId) board
                    else board.copy(artRevisions = board.artRevisions.filterNot { it.artId == artId })
                }
            ) to true
        } ?: false

    /** Points an existing board at a new backdrop. */
    suspend fun setBoardArt(bookId: Long, packId: String, boardId: String, artMedia: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            doc.copy(
                boards = doc.boards.map {
                    if (it.boardId == boardId) it.copy(artMedia = artMedia) else it
                }
            ) to true
        } ?: false

    /**
     * Adds a PIN marker element bound to [entityId] on [boardId]. This does **not** place the pin
     * anywhere on the map by itself — a freshly-added pin has no resolved position until a
     * `location` fact exists for that entity (see [PackBoardResolver]'s kdoc); the caller follows
     * this with [addFact] (field = the returned pin's field, value = `"<x>,<y>"`) once the author
     * actually drags it somewhere.
     */
    suspend fun addPinElement(bookId: Long, packId: String, boardId: String, entityId: String, field: String = "location"): String? =
        mutate(bookId, packId) { doc, _ ->
            val element = PackBoardElement(
                id = UUID.randomUUID().toString(),
                kind = PackBoardResolver.PIN_KIND,
                x = 0f, y = 0f, w = 0f, h = 0f, // unused for PIN kind — see PackBoardResolver
                entityRef = entityId,
                field = field
            )
            val boards = doc.boards.map { if (it.boardId == boardId) it.copy(elements = it.elements + element) else it }
            doc.copy(boards = boards) to element.id
        }

    suspend fun deleteBoardElement(bookId: Long, packId: String, boardId: String, elementId: String): Boolean =
        mutate(bookId, packId) { doc, _ ->
            val boards = doc.boards.map { if (it.boardId == boardId) it.copy(elements = it.elements.filterNot { e -> e.id == elementId }) else it }
            doc.copy(boards = boards) to true
        } ?: false

    // ── Quick capture (scraps, §9.1) ──────────────────────────────────────

    suspend fun listScraps(bookId: Long, packId: String): List<PackScrap> =
        companionDataStore.readBookScraps(bookId, packId)?.scraps.orEmpty()

    /** Records the anchor and a note, nothing more — the fast path during playback. Returns the
     *  new scrap's id, or null if the pack doesn't exist. Deliberately **not** gated on
     *  [CompanionPack.isOwn] — `scraps.json` sits outside the shared schema entirely (see
     *  [PackScrapsDoc]'s kdoc), so quick-capturing a note against a *received* pack (using a
     *  friend's map as scaffolding for your own observations) is always allowed; only turning a
     *  scrap into a real entity/fact ([convertScrap]) needs the ownership branch, and that's
     *  handled by routing through [com.betteraudio.companion.CompanionEditsRepository] instead when
     *  the pack isn't owned — see [com.betteraudio.ui.companion.CompanionAuthorViewModel]. */
    suspend fun addScrap(bookId: Long, packId: String, note: String, anchorGlobalMs: Long): String? {
        companionPackDao.getById(packId) ?: return null
        val doc = companionDataStore.readBookPack(bookId, packId) ?: return null
        val bookRef = doc.members.firstOrNull()?.bookRef ?: "m1"
        val scrapsDoc = companionDataStore.readBookScraps(bookId, packId) ?: PackScrapsDoc()
        val scrap = PackScrap(
            anchor = FactAnchor(bookRef = bookRef, globalMs = anchorGlobalMs, ratio = ratioFor(bookId, anchorGlobalMs)),
            note = note
        )
        diskMirror.flushBookScraps(bookId, packId, scrapsDoc.copy(scraps = scrapsDoc.scraps + scrap))
        return scrap.scrapId
    }

    suspend fun deleteScrap(bookId: Long, packId: String, scrapId: String) {
        val doc = companionDataStore.readBookScraps(bookId, packId) ?: return
        diskMirror.flushBookScraps(bookId, packId, doc.copy(scraps = doc.scraps.filterNot { it.scrapId == scrapId }))
    }

    /**
     * Turns a scrap into a real fact — either on an existing entity ([entityId] non-null) or a
     * brand-new one ([entityId] null, [newEntityName]/[newEntityKind] used instead). The scrap's
     * own anchor carries straight over (it was already sitting at the right position — §9.1), and
     * the scrap is marked [PackScrap.converted] rather than deleted, so its provenance survives.
     */
    suspend fun convertScrap(
        bookId: Long,
        packId: String,
        scrapId: String,
        entityId: String?,
        newEntityName: String?,
        newEntityKind: EntityKind,
        field: String,
        importance: Importance
    ): Boolean {
        val pack = companionPackDao.getById(packId)?.takeIf { it.isOwn } ?: return false
        val scrapsDoc = companionDataStore.readBookScraps(bookId, packId) ?: return false
        val scrap = scrapsDoc.scraps.firstOrNull { it.scrapId == scrapId } ?: return false
        val doc = companionDataStore.readBookPack(bookId, packId) ?: return false
        val newRevision = doc.revision + 1

        val targetEntityId: String
        val entities: List<PackEntity>
        if (entityId != null && doc.entities.any { it.entityId == entityId }) {
            targetEntityId = entityId
            entities = doc.entities
        } else {
            targetEntityId = UUID.randomUUID().toString()
            val name = newEntityName?.takeIf { it.isNotBlank() } ?: scrap.note.take(30).ifBlank { "New entity" }
            entities = doc.entities + PackEntity(entityId = targetEntityId, kind = newEntityKind, name = name)
        }

        val fact = PackFact(
            factId = UUID.randomUUID().toString(),
            entityId = targetEntityId,
            field = field,
            value = scrap.note,
            anchor = scrap.anchor,
            importance = importance,
            origin = doc.packId,
            rev = newRevision
        )
        val finalDoc = doc.copy(entities = entities, facts = doc.facts + fact, revision = newRevision)
        diskMirror.flushBookPack(bookId, finalDoc)
        companionPackDao.upsert(pack.copy(title = finalDoc.title, revision = finalDoc.revision))
        diskMirror.flushBookScraps(
            bookId, packId,
            scrapsDoc.copy(scraps = scrapsDoc.scraps.map { if (it.scrapId == scrapId) it.copy(converted = true) else it })
        )
        return true
    }

    // ── shared plumbing ────────────────────────────────────────────────────

    /**
     * Reads this pack's doc, hands it (plus the revision the write will land on) to [transform],
     * writes the result at the bumped revision, and keeps the thin registry row's own
     * title/revision mirror in sync. Refuses (returns null) for a pack this device doesn't own —
     * see the class kdoc — or one that no longer exists.
     */
    private suspend fun <T> mutate(
        bookId: Long,
        packId: String,
        transform: (doc: CompanionPackDoc, newRevision: Int) -> Pair<CompanionPackDoc, T>
    ): T? {
        val pack = companionPackDao.getById(packId)?.takeIf { it.isOwn } ?: return null
        val doc = companionDataStore.readBookPack(bookId, packId) ?: return null
        val newRevision = doc.revision + 1
        val (updated, result) = transform(doc, newRevision)
        val finalDoc = updated.copy(revision = newRevision)
        diskMirror.flushBookPack(bookId, finalDoc)
        companionPackDao.upsert(pack.copy(title = finalDoc.title, revision = finalDoc.revision))
        return result
    }

    private suspend fun ratioFor(bookId: Long, globalMs: Long): Float? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        return ratioFor(book, globalMs)
    }

    private fun ratioFor(book: Book, globalMs: Long): Float? {
        if (book.totalDurationMs <= 0L) return null
        return (globalMs.toDouble() / book.totalDurationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
