package com.betteraudio.ui.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.companion.CompanionEntityState
import com.betteraudio.companion.EntityStateResolver
import com.betteraudio.companion.PackBoardResolver
import com.betteraudio.companion.ReceiverThreshold
import com.betteraudio.companion.ResolvedPin
import com.betteraudio.companion.RevealCursorRepository
import com.betteraudio.companion.RevealDigest
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.entities.CompanionPack
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.CompanionDataStore
import com.betteraudio.data.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the companion consumption UI (docs/companion-packs.md §8/P2) for whichever book is
 * currently bound via [bind]. Not nav-route-scoped — a future embedding (the player's companion
 * affordance) calls [bind] from a `LaunchedEffect(bookId)` the same way any other per-book
 * component would, rather than reading a `SavedStateHandle`, since there is no dedicated
 * companion nav destination (§8: FULLSCREEN boards are a persistent overlay, not a route; INLINE/
 * SHEET presentations live inside the existing player surfaces).
 *
 * Deliberately reads only "one active pack per book" (§2's stated non-goal: no multi-pack
 * layering in v1) — the first `enabled` registry row for this book's target key, by title. A pack
 * switcher is future UI, not a data-model gap; [CompanionPackDao] already returns every row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val companionPackDao: CompanionPackDao,
    private val companionDataStore: CompanionDataStore,
    private val revealCursorRepository: RevealCursorRepository,
    private val settings: SettingsStore
) : ViewModel() {

    data class UiState(
        val bookId: Long? = null,
        val hasPack: Boolean = false,
        val pack: CompanionPack? = null,
        /** Absolute path of this pack's directory on disk, or null when there is no pack.
         *
         *  Published here rather than resolved in the UI because resolving it needs the `Book` row
         *  (see [CompanionDataStore.bookPackDir]) and the UI has only a `bookId`. Every media path
         *  in a pack — [com.betteraudio.companion.model.PackEntity.media],
         *  [com.betteraudio.companion.model.PackBoard.artMedia] — is relative to *this* directory,
         *  which is what makes a pack portable, so this is the one place the two halves meet. */
        val packDir: String? = null,
        /** Entities introduced so far, oldest-first — the cast strip's data. */
        val entities: List<CompanionEntityState> = emptyList(),
        /** Facts revealed since this pack was last opened (`lastSeenRevealMs`), filtered by the
         *  receiver's own notify threshold — see [RevealDigest]. Non-empty drives the badge. */
        val digest: List<RevealDigest.Item> = emptyList(),
        val revealedMs: Long = 0L,
        /** Where the listener actually is, book-global (`filesBeforeCurrentMs + positionMs` — the
         *  coordinate [revealedMs] is in; see PlaybackProgress.revealedMs's "never compare the two
         *  directly" note). Drives the catch-up affordance: the two are independent by design, and
         *  on a book that was already part-listened when companion packs arrived the cursor starts
         *  at 0 no matter how far in the listener is. */
        val bookPositionMs: Long = 0L,
        /** Every board in the active pack (P5) — the companion affordance offers a "View map" entry
         *  when a FULLSCREEN [com.betteraudio.companion.model.BoardKind.MAP] board is present. */
        val boards: List<PackBoard> = emptyList(),
        /** [board.boardId] → its already-resolved, spoiler-filtered pins — computed here (not lazily
         *  in the UI) so [PackBoardResolver] only ever sees the same [book]/[revealedMs] this
         *  ViewModel already resolved everything else against. */
        val pinsByBoard: Map<String, List<ResolvedPin>> = emptyMap(),
        /** `boardId` → the pack-relative art path to show *right now*. Resolved here, not in the
         *  UI, so a backdrop the listener has not reached is never handed to a composable at all —
         *  same enforcement point as every other spoiler-filtered value on this state. */
        val artByBoard: Map<String, String?> = emptyMap()
    )

    private val bookIdFlow = MutableStateFlow<Long?>(null)
    private val selectedEntityIdFlow = MutableStateFlow<String?>(null)

    /** Cache of the last-read pack doc, keyed by (packId, revision) — pack.json rarely changes
     *  (an author edit), while the surrounding Flow chain re-emits on every reveal-cursor tick
     *  (~every 30s during playback); re-parsing the file on every tick would be pure waste. */
    private var cachedDoc: Pair<Pair<String, Int>, CompanionPackDoc>? = null

    fun bind(bookId: Long) {
        bookIdFlow.value = bookId
    }

    fun selectEntity(entityId: String?) {
        selectedEntityIdFlow.value = entityId
    }

    val selectedEntityId: StateFlow<String?> = selectedEntityIdFlow.asStateFlow()

    val uiState: StateFlow<UiState> = bookIdFlow.filterNotNull().flatMapLatest { bookId ->
        bookDao.getBookWithProgress(bookId).flatMapLatest { bwp ->
            val book = bwp?.book
            if (book == null) {
                flowOf(UiState(bookId = bookId))
            } else {
                val revealedMs = bwp.progress?.revealedMs ?: 0L
                val bookPositionMs =
                    (bwp.progress?.filesBeforeCurrentMs ?: 0L) + (bwp.progress?.positionMs ?: 0L)
                val targetKey = BookDataPaths.relPath(book.folderPath, settings.currentLibraryFolder)
                combine(
                    companionPackDao.observeForTarget(targetKey),
                    settings.companionNotifyThreshold
                ) { packs, thresholdName ->
                    val activePack = packs.firstOrNull { it.enabled }
                    if (activePack == null) {
                        UiState(bookId = bookId, revealedMs = revealedMs, bookPositionMs = bookPositionMs)
                    } else {
                        // Cache key includes isOwn so an edits.json change (which doesn't bump the
                        // base pack's own revision — see CompanionEditsRepository) still busts the
                        // cache; edits are re-read fresh every time rather than cached, since they
                        // change far less predictably than the base doc.
                        val cacheKey = activePack.packId to activePack.revision
                        val baseDoc = cachedDoc?.takeIf { it.first == cacheKey }?.second
                            ?: companionDataStore.readBookPack(bookId, activePack.packId)?.also {
                                cachedDoc = cacheKey to it
                            }
                        val doc = if (baseDoc != null && !activePack.isOwn) {
                            val edits = companionDataStore.readBookPackEdits(bookId, activePack.packId)
                            com.betteraudio.companion.PackEditsApplier.apply(baseDoc, edits)
                        } else baseDoc
                        if (doc == null) {
                            UiState(bookId = bookId, revealedMs = revealedMs, bookPositionMs = bookPositionMs)
                        } else {
                            val threshold = runCatching { ReceiverThreshold.valueOf(
                                when (thresholdName) { "MAJOR" -> "MAJOR_ONLY"; "NEVER" -> "NEVER"; else -> "NOTABLE_PLUS" }
                            ) }.getOrDefault(ReceiverThreshold.NOTABLE_PLUS)
                            UiState(
                                bookId = bookId,
                                hasPack = true,
                                pack = activePack,
                                packDir = companionDataStore.bookPackDir(bookId, activePack.packId)?.path,
                                entities = EntityStateResolver.resolve(doc, book, revealedMs),
                                digest = RevealDigest.since(doc, book, activePack.lastSeenRevealMs, revealedMs, threshold),
                                revealedMs = revealedMs,
                                bookPositionMs = bookPositionMs,
                                boards = doc.boards,
                                pinsByBoard = doc.boards.associate { b -> b.boardId to PackBoardResolver.resolvePins(doc, b, book, revealedMs) },
                                artByBoard = doc.boards.associate { b -> b.boardId to PackBoardResolver.resolveArt(b, book, revealedMs) }
                            )
                        }
                    }
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Call when the companion is actually opened (not merely badged) — clears the digest/badge
     *  by advancing `lastSeenRevealMs` to the current reveal cursor. */
    fun markSeen() {
        val state = uiState.value
        val pack = state.pack ?: return
        viewModelScope.launch {
            companionPackDao.updateLastSeenRevealMs(pack.packId, state.revealedMs)
        }
    }

    // ── Confirmed-seek control (§6.3) — thin pass-throughs to RevealCursorRepository, exposed
    //    here so a future "set reveal point" affordance has one place to call into alongside the
    //    rest of the companion's UI state, rather than injecting the repository separately. ──────

    suspend fun previewSetRevealPoint(newRevealedMs: Long): RevealCursorRepository.SeekConfirmPreview {
        val bookId = bookIdFlow.value ?: return RevealCursorRepository.SeekConfirmPreview(
            0, 0, RevealCursorRepository.Direction.NONE
        )
        return revealCursorRepository.previewSetRevealPoint(bookId, newRevealedMs)
    }

    fun confirmSetRevealPoint(newRevealedMs: Long) {
        val bookId = bookIdFlow.value ?: return
        viewModelScope.launch { revealCursorRepository.confirmSetRevealPoint(bookId, newRevealedMs) }
    }

    fun undoSetRevealPoint() {
        val bookId = bookIdFlow.value ?: return
        viewModelScope.launch { revealCursorRepository.undoSetRevealPoint(bookId) }
    }
}
