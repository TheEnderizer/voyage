package com.betteraudio.data.diskstore

import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicInteger
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
 * [suppressed] exists because a bulk apply (BackupManager.restore, the scanner's per-book import)
 * calls several of the "immediate" repository methods — each individually hooked to flush — for
 * ONE logical operation; without suppression that's dozens of full JSON re-serializations mid
 * operation instead of one at the end. Not reentrant across concurrently-running suppressed
 * blocks from different coroutines — the two real call sites (the scanner's serial per-directory
 * loop, and BackupManager's single restore transaction) never overlap in practice.
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
    private val settings: SettingsStore,
    @ApplicationScope private val appScope: CoroutineScope
) {
    private val dirtyBooks = ConcurrentHashMap.newKeySet<Long>()
    private val libraryDirty = AtomicBoolean(false)
    private val suppressDepth = AtomicInteger(0)
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
        if (suppressDepth.get() > 0) {
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
        if (suppressDepth.get() > 0 || !hasLibraryFolder()) {
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
     */
    suspend fun flushDirty() = withContext(Dispatchers.IO) { flushMutex.withLock {
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
        for (id in books) {
            val ok = runCatching { bookDataStore.write(id) }.getOrElse { false }
            recordResult(ok, "book $id")
            if (ok) dirtyBooks.remove(id)
        }
        if (libraryDirty.compareAndSet(true, false)) {
            val ok = writeLibraryAndWidgets()
            recordResult(ok, "library")
            if (!ok) libraryDirty.set(true)
        }
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

    /** Runs [block] with every "immediate" hook downgraded to markDirty — the caller is
     *  responsible for calling [flushDirty]/[flushLibrary] once [block] completes, so a bulk
     *  apply (scan-time import, backup restore) produces one write per book instead of one per
     *  field it happens to touch. */
    suspend fun <T> suppressed(block: suspend () -> T): T {
        suppressDepth.incrementAndGet()
        try {
            return block()
        } finally {
            suppressDepth.decrementAndGet()
        }
    }

    private fun recordResult(ok: Boolean, what: String) {
        if (ok) {
            _healthy.value = true
            _lastError.value = null
        } else {
            _healthy.value = false
            _lastError.value = "Failed to write $what"
            AppLog.w("DiskMirror", "flush failed: $what")
        }
    }
}
