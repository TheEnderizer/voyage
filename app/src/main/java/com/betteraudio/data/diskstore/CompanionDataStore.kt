package com.betteraudio.data.diskstore

import com.betteraudio.companion.model.CompanionEditsCodec
import com.betteraudio.companion.model.CompanionPackCodec
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.PackEditsDoc
import com.betteraudio.companion.model.PackScrapsCodec
import com.betteraudio.companion.model.PackScrapsDoc
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes one companion pack's `pack.json` (+ `edits.json` if it has local edits) — the
 * disk-mirror store for companion packs (docs/companion-packs.md §7), alongside
 * BookDataStore/LibraryDataStore/WidgetsDataStore. Disk is the source of truth; the
 * `companion_packs` Room table ([com.betteraudio.data.db.dao.CompanionPackDao]) is only a thin
 * registry, so packs can be listed/enabled/disabled without parsing every pack.json on every
 * screen open — see [com.betteraudio.data.db.entities.CompanionPack]'s kdoc.
 *
 * BOOK-scoped packs are addressed by `bookId`, not by `targetKey`/relPath directly: the caller
 * reaching this store is always a companion screen opened FROM that book, so it already has the
 * `Book` row loaded and there is never a need for a cold reverse lookup by relPath (which
 * `BookDao` has no index for — relPath is computed, not stored). They resolve to
 * `data/companion[_<slug>]/<packId>/` under that book's own folder (see [BookDataPaths]).
 * SERIES-scoped packs are addressed by `packId` alone and resolve to
 * `.voyage/companion/<packId>/` (see [VoyageLayout]) — series data is library-wide, so there is no
 * per-series subdirectory; several packs can target the same series.
 *
 * Every entry point dispatches to [Dispatchers.IO] itself, matching every other store in this
 * package — callers are routinely Main-dispatched ViewModel scopes.
 */
@Singleton
class CompanionDataStore @Inject constructor(
    private val bookDao: BookDao,
    private val settings: SettingsStore
) {
    // ── BOOK-scoped ────────────────────────────────────────────────────────

    suspend fun readBookPack(bookId: Long, packId: String): CompanionPackDoc? = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.let(::readPackDoc)
    }

    suspend fun readBookPackEdits(bookId: Long, packId: String): PackEditsDoc? = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.let(::readEditsDoc)
    }

    suspend fun writeBookPack(bookId: Long, doc: CompanionPackDoc): Boolean = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, doc.packId)?.let { writePackDoc(it, doc) } ?: false
    }

    suspend fun writeBookPackEdits(bookId: Long, packId: String, doc: PackEditsDoc): Boolean = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.let { writeEditsDoc(it, doc) } ?: false
    }

    /** Quick-capture scraps (§9.1, P3) — see [PackScrapsDoc]'s kdoc for why these are a sidecar
     *  file, never part of `pack.json` itself. */
    suspend fun readBookScraps(bookId: Long, packId: String): PackScrapsDoc? = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.let(::readScrapsDoc)
    }

    suspend fun writeBookScraps(bookId: Long, packId: String, doc: PackScrapsDoc): Boolean = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.let { writeScrapsDoc(it, doc) } ?: false
    }

    /** Removes only this pack's own directory — never the shared `data/companion[_<slug>]/` root
     *  another pack on the same book may still own. */
    suspend fun deleteBookPack(bookId: Long, packId: String): Boolean = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.let { runCatching { it.deleteRecursively() }.getOrDefault(false) } ?: false
    }

    /**
     * This pack's own directory on disk, so a caller can resolve the *relative* media paths the
     * pack format stores (`PackEntity.media` = `"media/kelsier.webp"`, `PackBoard.artMedia`) into
     * real files.
     *
     * Public, and returning the pack root rather than `media/`, because the paths in the document
     * are relative to the pack root by definition — resolving them anywhere else would silently
     * work for the `media/` convention and break for any pack that nests art differently.
     * Callers hand the result to Coil as a `File`; nothing here reads image bytes.
     */
    suspend fun bookPackDir(bookId: Long, packId: String): File? = withContext(Dispatchers.IO) {
        bookPackDirFor(bookId, packId)?.takeIf { it.isDirectory }
    }

    /**
     * Copies [bytes] into this pack's `media/` directory and returns the **pack-relative** path to
     * store in [com.betteraudio.companion.model.PackEntity.media].
     *
     * Relative, not absolute: an absolute path would be correct on this device and wrong the
     * moment the pack is exported and imported somewhere else, which is the whole reason the
     * format specifies relative media paths in the first place.
     */
    suspend fun writeBookPackMedia(
        bookId: Long,
        packId: String,
        fileName: String,
        bytes: ByteArray
    ): String? = withContext(Dispatchers.IO) {
        val dir = bookPackDirFor(bookId, packId) ?: return@withContext null
        val media = File(dir, MEDIA_DIR_NAME)
        if (!media.isDirectory && !media.mkdirs()) return@withContext null
        val safe = fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeIf { it.isNotBlank() }
            ?: return@withContext null
        val target = File(media, safe)
        runCatching { target.writeBytes(bytes) }.getOrNull() ?: return@withContext null
        ensureNoMedia(dir)
        "$MEDIA_DIR_NAME/$safe"
    }

    /** Deletes one media file by its pack-relative path. Guarded against a `..` path escaping the
     *  pack directory — media paths arrive from an imported document, which is untrusted input. */
    suspend fun deleteBookPackMedia(bookId: Long, packId: String, relPath: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = bookPackDirFor(bookId, packId) ?: return@withContext false
            val target = File(dir, relPath)
            val inside = runCatching {
                target.canonicalPath.startsWith(dir.canonicalPath + File.separator)
            }.getOrDefault(false)
            inside && runCatching { target.delete() }.getOrDefault(false)
        }

    private suspend fun bookPackDirFor(bookId: Long, packId: String): File? {
        val book = bookDao.getBookOnce(bookId) ?: return null
        return BookDataPaths.bookPackDir(book.folderPath, packId)
    }

    // ── SERIES-scoped ──────────────────────────────────────────────────────

    suspend fun readSeriesPack(packId: String): CompanionPackDoc? = withContext(Dispatchers.IO) {
        seriesPackDir(packId)?.let(::readPackDoc)
    }

    suspend fun readSeriesPackEdits(packId: String): PackEditsDoc? = withContext(Dispatchers.IO) {
        seriesPackDir(packId)?.let(::readEditsDoc)
    }

    suspend fun writeSeriesPack(doc: CompanionPackDoc): Boolean = withContext(Dispatchers.IO) {
        seriesPackDir(doc.packId)?.let { writePackDoc(it, doc) } ?: false
    }

    suspend fun writeSeriesPackEdits(packId: String, doc: PackEditsDoc): Boolean = withContext(Dispatchers.IO) {
        seriesPackDir(packId)?.let { writeEditsDoc(it, doc) } ?: false
    }

    suspend fun deleteSeriesPack(packId: String): Boolean = withContext(Dispatchers.IO) {
        seriesPackDir(packId)?.let { runCatching { it.deleteRecursively() }.getOrDefault(false) } ?: false
    }

    suspend fun readSeriesScraps(packId: String): PackScrapsDoc? = withContext(Dispatchers.IO) {
        seriesPackDir(packId)?.let(::readScrapsDoc)
    }

    suspend fun writeSeriesScraps(packId: String, doc: PackScrapsDoc): Boolean = withContext(Dispatchers.IO) {
        seriesPackDir(packId)?.let { writeScrapsDoc(it, doc) } ?: false
    }

    private fun seriesPackDir(packId: String): File? =
        VoyageLayout.seriesPackDir(settings.currentLibraryFolder, packId)

    // ── shared I/O ─────────────────────────────────────────────────────────

    private fun readPackDoc(dir: File): CompanionPackDoc? {
        val file = File(dir, PACK_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.let { CompanionPackCodec.decodeOrNull(it) }
    }

    private fun readEditsDoc(dir: File): PackEditsDoc? {
        val file = File(dir, EDITS_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.let { CompanionEditsCodec.decodeOrNull(it) }
    }

    private fun writePackDoc(dir: File, doc: CompanionPackDoc): Boolean {
        val ok = writeTextAtomic(File(dir, PACK_FILE_NAME), CompanionPackCodec.encodeToString(doc))
        // The pack dir directly contains media/ (its images) — marking it hides that subtree too,
        // same defensive-per-subdirectory convention WidgetsDataStore uses for widget_images/
        // even though the parent .voyage/ (or data/) already carries its own marker.
        if (ok) ensureNoMedia(dir)
        return ok
    }

    private fun writeEditsDoc(dir: File, doc: PackEditsDoc): Boolean =
        writeTextAtomic(File(dir, EDITS_FILE_NAME), CompanionEditsCodec.encodeToString(doc))

    private fun readScrapsDoc(dir: File): PackScrapsDoc? {
        val file = File(dir, SCRAPS_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.let { PackScrapsCodec.decodeOrNull(it) }
    }

    private fun writeScrapsDoc(dir: File, doc: PackScrapsDoc): Boolean =
        writeTextAtomic(File(dir, SCRAPS_FILE_NAME), PackScrapsCodec.encodeToString(doc))

    companion object {
        const val PACK_FILE_NAME = "pack.json"
        const val EDITS_FILE_NAME = "edits.json"
        const val SCRAPS_FILE_NAME = "scraps.json"
        const val MEDIA_DIR_NAME = "media"
    }
}
