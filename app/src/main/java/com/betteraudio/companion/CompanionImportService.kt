package com.betteraudio.companion

import android.content.Context
import android.net.Uri
import com.betteraudio.companion.model.CompanionManifestCodec
import com.betteraudio.companion.model.CompanionManifestDoc
import com.betteraudio.companion.model.CompanionPackCodec
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.PackMember
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.CompanionPack
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.DiskMirror
import com.betteraudio.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ingests a `.voyagepack` archive (docs/companion-packs.md §10.3, P4 — **DATA_ONLY only**; audio
 * placement for a book the recipient doesn't already have is P7). The DATA_ONLY happy path is
 * exactly §10.3 step 2's common case: *"if the book is already there, never copy audio — attach
 * the pack and stop."* There is no audio in a DATA_ONLY archive to place even if the book were
 * missing, so a match miss is reported plainly rather than attempted.
 *
 * **Matching, scoped down from §10.3's full plan.** The real design calls for `fileKey` fingerprint
 * matching backed by a cached `AudioFile.fileKey` column (populated lazily, like
 * `damageRangesJson`) — see [com.betteraudio.data.db.entities.AudioFile.fileKey], added in this
 * pass but not yet populated by anything. Wiring a background populate-on-scan pass and a
 * `BackupMatcher` extension is real additional scope; this pass matches on normalized
 * title + author instead (case/whitespace-insensitive), which is the same signal a human would use
 * and is correct for the overwhelmingly common case (one book, one obvious title match). A
 * fileKey-backed second signal is a natural, additive follow-up once that cache is populated
 * elsewhere — nothing about this matcher's shape needs to change to add it.
 *
 * **SERIES-scoped packs import their first member only in this pass** — full per-member matching
 * across a whole series (§10.3's "several packs can target the same series", each member matched
 * independently) is real additional UI (a per-book match/skip review list) not built here. A
 * SERIES-scoped `.voyagepack` still imports; it just attaches against its first listed member,
 * which is enough for the single-book share this feature is chiefly used for.
 */
@Singleton
class CompanionImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val companionPackDao: CompanionPackDao,
    private val progressDao: PlaybackProgressDao,
    private val diskMirror: DiskMirror,
    private val editsRepository: CompanionEditsRepository,
    private val settings: SettingsStore
) {
    sealed class ImportResult {
        /** Attached for the first time. [existingProgressMs] is the recipient's own book-global
         *  position in [bookId], if any — 0 for a book they haven't started, driving the "keep
         *  progress or start fresh" prompt (§10.1) only when it's actually > 0. */
        data class Attached(
            val bookId: Long,
            val bookTitle: String,
            val packTitle: String,
            val existingProgressMs: Long
        ) : ImportResult()
        /** A newer revision of a pack already attached (§9.2/P6) — the base was swapped and any
         *  local edits re-applied on top automatically; [conflictCount] is how many of those edits
         *  might now disagree with the update (0 = applied silently, nothing to review). */
        data class Updated(
            val bookId: Long,
            val bookTitle: String,
            val packTitle: String,
            val newRevision: Int,
            val conflictCount: Int
        ) : ImportResult()
        data class AlreadyUpToDate(val packTitle: String) : ImportResult()
        /** A FULL-payload archive (§10.1/P7) — handed off to [CompanionImportWorker] instead of
         *  handled inline, since placing a book's audio can genuinely take minutes and must survive
         *  the app closing mid-extract. The caller shows a "importing in the background" state;
         *  there is no synchronous result to report here. */
        data class QueuedFullImport(val packTitle: String) : ImportResult()
        data class NoMatch(val packTitle: String, val memberTitle: String) : ImportResult()
        data class NotAPack(val reason: String) : ImportResult()
        data class Failure(val reason: String) : ImportResult()
    }

    suspend fun importFromUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        // Cheap peek at manifest.json ONLY (always the first zip entry — see
        // CompanionExportService) before touching anything else: a FULL archive's audio/ entries
        // must never be buffered into memory the way parseArchive buffers manifest/pack/media, so
        // this has to branch before parseArchive ever runs, not after.
        val payload = runCatching { peekPayload(uri) }.getOrNull()
        if (payload == com.betteraudio.companion.model.PackPayload.FULL) {
            val title = runCatching { peekTitle(uri) }.getOrNull() ?: "companion pack"
            // Copied to an app-owned file BEFORE enqueueing, not handed to the worker as the
            // original content:// URI: a share-sheet grant is not guaranteed to outlive this
            // activity, and WorkManager can run the job long after — including after a process
            // death — by which point a revoked grant would fail the job with no way to recover.
            // filesDir is always readable by this app regardless of any external grant.
            val localFile = copyToLocalFile(uri)
                ?: return@withContext ImportResult.Failure("Couldn't read the pack file")
            CompanionImportWorker.enqueue(context, localFile.absolutePath)
            return@withContext ImportResult.QueuedFullImport(title)
        }

        val parsed = runCatching { parseArchive(uri) }.getOrElse {
            return@withContext ImportResult.NotAPack("This doesn't look like a companion pack file")
        } ?: return@withContext ImportResult.NotAPack("This doesn't look like a companion pack file")

        val (doc, manifest, media) = parsed

        // Already attached somewhere on this device (§9.2 revision-swap / P6), rather than the
        // usual first-time title/author match — packId is exact where a fuzzy title match isn't,
        // and the book it was attached to before is authoritative regardless of what its title
        // says now (a user can rename a book locally after attaching).
        val existingRow = companionPackDao.getById(doc.packId)
        if (existingRow != null && !existingRow.isOwn) {
            return@withContext handleUpdate(existingRow, doc, media)
        }

        val members = manifest?.members?.takeIf { it.isNotEmpty() } ?: doc.members
        val member = members.firstOrNull()
            ?: return@withContext ImportResult.Failure("This pack doesn't reference any book")

        val candidates = bookDao.getAllBooksOnce()
        val match = CompanionPackMatcher.bestMatch(candidates, member) ?: return@withContext ImportResult.NoMatch(doc.title, member.title)

        val ok = attach(match, doc, media)
        if (!ok) return@withContext ImportResult.Failure("Couldn't save the pack to this device")

        val progress = progressDao.getProgressForBookOnce(match.id)
        val existingProgressMs = (progress?.filesBeforeCurrentMs ?: 0L) + (progress?.positionMs ?: 0L)

        ImportResult.Attached(
            bookId = match.id,
            bookTitle = match.displayTitle,
            packTitle = doc.title,
            existingProgressMs = existingProgressMs
        )
    }

    private suspend fun handleUpdate(existingRow: CompanionPack, newDoc: CompanionPackDoc, media: Map<String, ByteArray>): ImportResult {
        if (newDoc.revision <= existingRow.revision) {
            return ImportResult.AlreadyUpToDate(existingRow.title)
        }
        val book = resolveBookByTargetKey(existingRow.targetKey)
            ?: return ImportResult.Failure("The book this pack was attached to is no longer in your library")

        val conflicts = editsRepository.updateWithConflictCheck(book.id, newDoc)

        if (media.isNotEmpty()) {
            val packDir = BookDataPaths.bookPackDir(book.folderPath, newDoc.packId)
            val mediaDir = File(packDir, "media").apply { mkdirs() }
            for ((name, bytes) in media) runCatching { File(mediaDir, name).writeBytes(bytes) }
        }

        return ImportResult.Updated(
            bookId = book.id,
            bookTitle = book.displayTitle,
            packTitle = newDoc.title,
            newRevision = newDoc.revision,
            conflictCount = conflicts.size
        )
    }

    private suspend fun resolveBookByTargetKey(targetKey: String): Book? =
        bookDao.getAllBooksOnce().firstOrNull { BookDataPaths.relPath(it.folderPath, settings.currentLibraryFolder) == targetKey }

    private fun copyToLocalFile(uri: Uri): File? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val dir = File(context.filesDir, "companion_import_pending").apply { mkdirs() }
        val out = File(dir, "${java.util.UUID.randomUUID()}.voyagepack")
        return runCatching {
            input.use { s -> out.outputStream().use { os -> s.copyTo(os, bufferSize = 256 * 1024) } }
            out
        }.getOrElse { runCatching { out.delete() }; null }
    }

    /** Reads only `manifest.json` — always the archive's first entry — and stops. Never touches
     *  anything after it, so this is cheap and safe to call even on a multi-GB FULL archive. */
    private fun peekPayload(uri: Uri): com.betteraudio.companion.model.PackPayload? =
        peekManifest(uri)?.payload

    private fun peekTitle(uri: Uri): String? = peekManifest(uri)?.title

    private fun peekManifest(uri: Uri): CompanionManifestDoc? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name == "manifest.json") {
                        return CompanionManifestCodec.decodeOrNull(String(zip.readBytes(), Charsets.UTF_8))
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private data class ParsedArchive(val doc: CompanionPackDoc, val manifest: CompanionManifestDoc?, val media: Map<String, ByteArray>)

    /** DATA_ONLY only by the time this is called — [importFromUri] routes FULL archives to
     *  [CompanionImportWorker] before this ever runs. `audio/` entries are still explicitly
     *  skipped (never buffered) as defense in depth against a mislabeled or malformed archive. */
    private fun parseArchive(uri: Uri): ParsedArchive? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        var doc: CompanionPackDoc? = null
        var manifest: CompanionManifestDoc? = null
        val media = mutableMapOf<String, ByteArray>()
        input.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && !entry.name.startsWith("audio/")) {
                        val bytes = zip.readBytes()
                        when {
                            entry.name == "manifest.json" -> manifest = CompanionManifestCodec.decodeOrNull(String(bytes, Charsets.UTF_8))
                            entry.name == "pack.json" -> doc = CompanionPackCodec.decodeOrNull(String(bytes, Charsets.UTF_8))
                            entry.name.startsWith("media/") && entry.name.length > "media/".length ->
                                media[entry.name.removePrefix("media/")] = bytes
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        val d = doc ?: return null
        return ParsedArchive(d, manifest, media)
    }

    private suspend fun attach(book: Book, doc: CompanionPackDoc, media: Map<String, ByteArray>): Boolean {
        diskMirror.flushBookPack(book.id, doc)
        if (media.isNotEmpty()) {
            val packDir = BookDataPaths.bookPackDir(book.folderPath, doc.packId)
            val mediaDir = File(packDir, "media").apply { mkdirs() }
            for ((name, bytes) in media) {
                runCatching { File(mediaDir, name).writeBytes(bytes) }
            }
        }
        val targetKey = BookDataPaths.relPath(book.folderPath, settings.currentLibraryFolder)
        companionPackDao.upsert(
            CompanionPack(
                packId = doc.packId,
                scope = doc.scope.name,
                targetKey = targetKey,
                title = doc.title,
                authorHandle = doc.authorHandle,
                revision = doc.revision,
                enabled = true,
                lastSeenRevealMs = 0L,
                isOwn = false
            )
        )
        // Deliberately no revealedMs write here (§10.3 step 6): the recipient's existing progress
        // (0 for a never-started book) is already the correct reveal point without touching it.
        return true
    }
}
