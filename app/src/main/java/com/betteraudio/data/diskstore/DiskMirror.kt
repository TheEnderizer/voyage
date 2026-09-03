package com.betteraudio.data.diskstore

import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.PackEditsDoc
import com.betteraudio.companion.model.PackScrapsDoc
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point every repository write goes through to keep the on-disk mirror
 * (BookDataStore / LibraryDataStore) in sync with Room. Two write modes:
 *  - [markDirty] / [markLibraryDirty]: cheap, non-suspending, just remembers "this needs a
 *    flush" — used for high-frequency writes (playback position) that the cadence intentionally
 *    defers to pause/stop/close rather than mirroring on every tick.
 *  - [flushBook] / [flushLibrary]: writes immediately — used for user-authored edits (rename,
 *    cover change, bookmark add, series membership) where losing the mirror write to a crash
 *    between "now" and "the next flush trigger" would be a visible regression.
 *
 * [suppressed] exists because a bulk apply (BackupManager.restore, the scanner's per-book import,
 * a companion-pack FULL import) calls several of the "immediate" repository methods — each
 * individually hooked to flush — for ONE logical operation; without suppression that's dozens of
 * full JSON re-serializations mid operation instead of one at the end. It nests, and is safe
 * across blocks running concurrently in different coroutines; see its own kdoc for the two
 * properties that make that true.
 *
 * Every write is best-effort: a failure flips [healthy] false and leaves the book/library dirty
 * for the next flush to retry (never silently dropped), but never throws, blocks a caller, or
 * surfaces a dialog — the DB write it mirrors already succeeded, and this is a convenience copy,
 * not the source of truth, until the moment it's the only copy left (after a reinstall).
 */
@Singleton
class DiskMirror @Inject constructor(
    private val bookDataStore: BookDataStore,
    private val libraryDataStore: LibraryDataStore,
    private val widgetsDataStore: WidgetsDataStore,
    private val companionDataStore: CompanionDataStore,
    private val settings: SettingsStore,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val dirtyBooks = ConcurrentHashMap.newKeySet<Long>()
    private val libraryDirty = AtomicBoolean(false)
    private val gate = SuppressionGate()
    private val flushMutex = Mutex()

    private val _healthy = MutableStateFlow(true)
    val healthy: StateFlow<Boolean> = _healthy.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun markDirty(bookId: Long) {
        dirtyBooks.add(bookId)
    }

    fun markLibraryDirty() {
        libraryDirty.set(true)
    }

    /** Writes [bookId]'s doc now, unless a [suppressed] block is active — then it's just marked
     *  dirty for the flush the caller runs once the block completes. */
    suspend fun flushBook(bookId: Long) {
        // Deliberately NOT gated on a library folder being set, unlike flushLibrary: a book's doc
        // path is derived from its own absolute folderPath and is perfectly writable without one.
        // (The library folder only supplies the portable relPath field, which has an absolute
        // fallback.) Gating this would needlessly defer the cold-start widget-tap position save,
        // which fires before SettingsStore's volatile snapshot has landed.
        if (gate.isSuppressed) {
            dirtyBooks.add(bookId)
            return
        }
        // Dispatched to IO *here*, not at each call site: the hooked repository methods are
        // routinely invoked straight from a ViewModel's Main-dispatched viewModelScope (rename a
        // book, add a bookmark, change a cover), and serializing the doc + writing it to a slow
        // SD card on the main thread would jank the frame — or ANR on a bad volume.
        val ok = withContext(Dispatchers.IO) {
            runCatching { bookDataStore.write(bookId) }.getOrElse { false }
        }
        recordResult(ok, "book $bookId")
        if (ok) dirtyBooks.remove(bookId) else dirtyBooks.add(bookId)
    }

    suspend fun flushLibrary() {
        if (gate.isSuppressed || !hasLibraryFolder()) {
            libraryDirty.set(true)
            return
        }
        val ok = withContext(Dispatchers.IO) { writeLibraryAndWidgets() }
        recordResult(ok, "library")
        libraryDirty.set(!ok)
    }

    /**
     * "Is there anywhere to mirror to yet?" — false before the user has picked a library folder
     * (first run, or the settings screen mid-change). Deliberately NOT treated as a failure: the
     * write genuinely has no destination yet, and reporting it as one would light up the
     * "mirror unhealthy" row in Settings → Library for a brand-new install that has done nothing
     * wrong. The item stays dirty either way, so it's written as soon as a folder exists.
     */
    private fun hasLibraryFolder(): Boolean = settings.currentLibraryFolder.isNotBlank()

    /** library.json and widgets/designs.json are both cheap, whole-library snapshots with no
     *  per-item dirty tracking of their own — one write covers both, same as [markLibraryDirty]
     *  covers both callers (preset/series/author edits AND widget design save/delete). */
    private suspend fun writeLibraryAndWidgets(): Boolean {
        val libraryOk = runCatching { libraryDataStore.write() }.getOrElse { false }
        val widgetsOk = runCatching { widgetsDataStore.write() }.getOrElse { false }
        return libraryOk && widgetsOk
    }

    /**
     * Drains every dirty book/the library flag under one lock. Checks the library volume is
     * actually mounted ONCE per batch rather than once per book — an unplugged SD card should
     * cost one stat, not N. Books/the library flag that fail to write are left dirty so the next
     * trigger (or a manual "re-export library data") retries them instead of silently losing the
     * mirror write.
     *
     * **Defers while any [suppressed] block is active**, matching [flushBook]/[flushLibrary]
     * rather than being the one flush path that ignores suppression. [dirtyBooks] is shared by
     * every caller, so without this an unrelated trigger — a playback pause, or a concurrent bulk
     * apply's own epilogue flush — would drain a *different* still-running block's
     * partially-applied book and mirror that half-written state to disk. Since disk is the source
     * of truth, a process death in that window would leave the half-applied doc as the surviving
     * copy. Nothing is lost by deferring: the dirty flags stay set and the outermost
     * [suppressed] exit drains them.
     */
    suspend fun flushDirty() = withContext(Dispatchers.IO) { flushMutex.withLock {
        if (gate.isSuppressed) return@withLock
        val root = settings.currentLibraryFolder
        if (root.isBlank() || !File(root).isDirectory) {
            if (dirtyBooks.isNotEmpty() || libraryDirty.get()) {
                recordResult(false, "library folder not accessible")
            }
            return@withLock
        }
        // Snapshot before writing — a write can itself mark the same book dirty again (e.g. a
        // concurrent edit), and that should survive to the NEXT flush, not be dropped here.
        val books = dirtyBooks.toList()
        if (books.isEmpty() && !libraryDirty.get()) return@withLock // nothing to do — don't log a no-op batch
        var booksOk = 0
        for (id in books) {
            val ok = runCatching { bookDataStore.write(id) }.getOrElse { false }
            recordResult(ok, "book $id")
            if (ok) { dirtyBooks.remove(id); booksOk++ }
        }
        var libraryFlushed = false
        if (libraryDirty.compareAndSet(true, false)) {
            val ok = writeLibraryAndWidgets()
            recordResult(ok, "library")
            if (!ok) libraryDirty.set(true)
            libraryFlushed = ok
        }
        AppLog.d(LogCat.DISK) { "flushDirty: books=$booksOk/${books.size} library=$libraryFlushed" }
    } }

    fun flushDirtyAsync() {
        appScope.launch { flushDirty() }
    }

    /** Removes only [book]'s own doc/cover/mapping file — never the shared data dir an AUTO
     *  cluster sibling may still own. See BookDataStore.delete. */
    suspend fun deleteBookData(book: Book) {
        withContext(Dispatchers.IO) { runCatching { bookDataStore.delete(book) } }
        dirtyBooks.remove(book.id)
    }

    /**
     * Writes one companion pack's `pack.json` immediately (docs/companion-packs.md §7/§11). Unlike
     * [flushBook]/[flushLibrary] there is deliberately no dirty-tracking/deferral tier for
     * packs — a pack write is a rare, user-authored edit (create/edit an entity, save a board),
     * never a high-frequency one like playback position, so the two-tier design that tier exists
     * for (cheap markDirty vs. expensive immediate flush) has nothing to trade off here. Adding a
     * third dirty-tracking dimension keyed by packId to save writes that already only happen a
     * handful of times per editing session would be complexity with no payoff.
     */
    suspend fun flushBookPack(bookId: Long, doc: CompanionPackDoc) {
        val ok = withContext(Dispatchers.IO) { runCatching { companionDataStore.writeBookPack(bookId, doc) }.getOrElse { false } }
        recordResult(ok, "companion pack ${doc.packId}")
    }

    /** SERIES-scoped counterpart to [flushBookPack]; see its doc for why there is no dirty tier. */
    suspend fun flushSeriesPack(doc: CompanionPackDoc) {
        val ok = withContext(Dispatchers.IO) { runCatching { companionDataStore.writeSeriesPack(doc) }.getOrElse { false } }
        recordResult(ok, "companion pack ${doc.packId}")
    }

    /** The local-edits overlay (§9.2, P6) — same immediate-write rationale as [flushBookPack]: a
     *  local edit is a rare, user-initiated action, never a high-frequency one. */
    suspend fun flushBookPackEdits(bookId: Long, packId: String, doc: PackEditsDoc) {
        val ok = withContext(Dispatchers.IO) { runCatching { companionDataStore.writeBookPackEdits(bookId, packId, doc) }.getOrElse { false } }
        recordResult(ok, "companion edits $packId")
    }

    suspend fun flushSeriesPackEdits(packId: String, doc: PackEditsDoc) {
        val ok = withContext(Dispatchers.IO) { runCatching { companionDataStore.writeSeriesPackEdits(packId, doc) }.getOrElse { false } }
        recordResult(ok, "companion edits $packId")
    }

    /** Quick-capture scraps (§9.1, P3) — same immediate-write rationale as [flushBookPack]: a
     *  scrap write is a rare, user-initiated capture, not a high-frequency one. */
    suspend fun flushBookScraps(bookId: Long, packId: String, doc: PackScrapsDoc) {
        val ok = withContext(Dispatchers.IO) { runCatching { companionDataStore.writeBookScraps(bookId, packId, doc) }.getOrElse { false } }
        recordResult(ok, "companion scraps $packId")
    }

    suspend fun flushSeriesScraps(packId: String, doc: PackScrapsDoc) {
        val ok = withContext(Dispatchers.IO) { runCatching { companionDataStore.writeSeriesScraps(packId, doc) }.getOrElse { false } }
        recordResult(ok, "companion scraps $packId")
    }

    /**
     * Runs [block] with every "immediate" hook downgraded to markDirty, then drains once on the
     * **outermost** exit — so a bulk apply (scan-time import, backup restore, companion-pack FULL
     * import) produces one write per book instead of one per field it happens to touch.
     *
     * **Safe across blocks running concurrently in different coroutines**, which is now a real
     * case rather than a theoretical one: a WorkManager companion import
     * ([com.betteraudio.companion.CompanionFullImportService] →
     * `AudioFileScanner.importSingleFolder`) can overlap a library scan. Two properties make it
     * safe, and both are load-bearing:
     *
     *  1. [SuppressionGate] is a nesting **counter**, not a flag — suppression lifts only when the
     *     last block exits, so a block finishing can never un-suppress one still running.
     *     Replacing it with a Boolean silently reintroduces exactly that bug; `SuppressionGateTest`
     *     pins it.
     *  2. [flushDirty] defers while any block is active, so no other trigger can drain the shared
     *     dirty set mid-apply. See its kdoc for what that would otherwise write to disk.
     *
     * The drain runs [NonCancellable]: the Room writes it mirrors have already committed, so a
     * cancelled scan must still leave disk consistent with them instead of lagging until some
     * later trigger. Callers may still flush after the block — several do, and by then it is a
     * cheap no-op. If another block happens to enter between this exit and the drain, the drain
     * defers and *that* block's outermost exit covers everything, including this one's work.
     */
    suspend fun <T> suppressed(block: suspend () -> T): T {
        if (gate.enter()) AppLog.d(LogCat.DISK) { "suppressed: entering bulk-apply mode" }
        try {
            return block()
        } finally {
            if (gate.exit()) {
                AppLog.d(LogCat.DISK) { "suppressed: exiting bulk-apply mode (dirtyBooks=${dirtyBooks.size} libraryDirty=${libraryDirty.get()})" }
                withContext(NonCancellable) { flushDirty() }
            }
        }
    }

    private fun recordResult(ok: Boolean, what: String) {
        if (ok) {
            _healthy.value = true
            _lastError.value = null
        } else {
            _healthy.value = false
            _lastError.value = "Failed to write $what"
            AppLog.w(LogCat.DISK, "flush failed: $what")
        }
    }
}
