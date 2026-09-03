package com.betteraudio.companion

import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.CompanionDataStore
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.settings.SettingsStore
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The confirmed-seek control (docs/companion-packs.md §6.3, rule 3) — the one piece of reveal-
 * cursor movement that is NOT automatic. [RevealCursor] (playback package) only ever implements
 * rules 1/2; this repository is what a "set reveal point to here" UI action calls, in both
 * directions:
 *  - forward, to catch the companion up after a deliberate skip the automatic engine correctly
 *    refused to credit;
 *  - backward, to re-hide a book's facts on a deliberate restart-from-zero.
 *
 * [previewSetRevealPoint] answers "how many items would this reveal/hide" so a confirm dialog can
 * state the count before the user commits (§6.3 requires this on every call, both directions).
 * [undoSetRevealPoint] restores the value from BEFORE the last confirm — held only in memory
 * ([undoBuffer]), never persisted, matching §6.3's reasoning: nobody undoes a reveal-point change
 * after an app restart, and a persisted column is one more place the actual spoiler-safety
 * invariant (§10.1's `revealedMs` strip-on-export) could be gotten wrong.
 */
@Singleton
class RevealCursorRepository @Inject constructor(
    private val bookDao: BookDao,
    private val companionPackDao: CompanionPackDao,
    private val companionDataStore: CompanionDataStore,
    private val audiobookRepository: AudiobookRepository,
    private val settings: SettingsStore
) {
    private val undoBuffer = ConcurrentHashMap<Long, Long>()

    enum class Direction { FORWARD, BACKWARD, NONE }
    data class SeekConfirmPreview(val revealCount: Int, val hideCount: Int, val direction: Direction)

    suspend fun previewSetRevealPoint(bookId: Long, newRevealedMs: Long): SeekConfirmPreview {
        val book = bookDao.getBookOnce(bookId) ?: return SeekConfirmPreview(0, 0, Direction.NONE)
        val current = audiobookRepository.getRevealedMs(bookId)
        if (newRevealedMs == current) return SeekConfirmPreview(0, 0, Direction.NONE)
        val positions = loadResolvedFactPositions(bookId, book)
        return if (newRevealedMs > current) {
            SeekConfirmPreview(
                revealCount = positions.count { it in (current + 1)..newRevealedMs },
                hideCount = 0,
                direction = Direction.FORWARD
            )
        } else {
            SeekConfirmPreview(
                revealCount = 0,
                hideCount = positions.count { it in (newRevealedMs + 1)..current },
                direction = Direction.BACKWARD
            )
        }
    }

    /** Applies the change the user just confirmed in [previewSetRevealPoint]'s dialog. */
    suspend fun confirmSetRevealPoint(bookId: Long, newRevealedMs: Long) {
        val prev = audiobookRepository.getRevealedMs(bookId)
        undoBuffer[bookId] = prev
        audiobookRepository.updateRevealedMs(bookId, newRevealedMs.coerceAtLeast(0L))
    }

    /** Restores the value from immediately before the last [confirmSetRevealPoint] on this book,
     *  if any. Returns false (no-op) once consumed or if nothing was confirmed this session. */
    suspend fun undoSetRevealPoint(bookId: Long): Boolean {
        val prev = undoBuffer.remove(bookId) ?: return false
        audiobookRepository.updateRevealedMs(bookId, prev)
        return true
    }

    /** The "start fresh" option on companion-pack import (§10.1) and any future user-facing
     *  progress reset — zeroes position and reveal together via
     *  [AudiobookRepository.resetProgress], and drops any pending undo for this book (an undo back
     *  to a pre-reset value would be a spoiler leak in exactly the case this exists to prevent). */
    suspend fun resetProgressAndReveal(bookId: Long) {
        undoBuffer.remove(bookId)
        audiobookRepository.resetProgress(bookId)
    }

    /**
     * Every resolvable fact position from this book's enabled BOOK-scoped packs, in book-global
     * ms. SERIES-scoped packs are deliberately out of scope here — counting facts for those needs
     * the Reveal progress panel's per-member iteration (§6.3), a P2/UI-layer concern, not this
     * single-book preview. Facts [AnchorResolver] cannot resolve at all are silently excluded
     * (never counted as revealed OR hidden) rather than guessed at.
     */
    private suspend fun loadResolvedFactPositions(bookId: Long, book: Book): List<Long> {
        val targetKey = BookDataPaths.relPath(book.folderPath, settings.currentLibraryFolder)
        val packs = companionPackDao.getForTargetOnce(targetKey).filter { it.enabled }
        val positions = mutableListOf<Long>()
        for (pack in packs) {
            val doc = companionDataStore.readBookPack(bookId, pack.packId) ?: continue
            for (fact in doc.facts) {
                val resolved = AnchorResolver.resolve(fact.anchor, book) ?: continue
                positions += resolved.bookGlobalMs
            }
        }
        return positions
    }
}
