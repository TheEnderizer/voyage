package com.betteraudio.companion

import android.content.Context
import com.betteraudio.companion.model.CompanionManifestCodec
import com.betteraudio.companion.model.CompanionManifestDoc
import com.betteraudio.companion.model.CompanionPackCodec
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.CompanionPack
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.diskstore.DiskMirror
import com.betteraudio.data.files.LibraryPaths
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.scanner.AudioFileScanner
import com.betteraudio.data.scanner.ImportStructure
import com.betteraudio.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FULL-payload import (docs/companion-packs.md §10.3 step 3/§12 P7) — the path a DATA_ONLY import
 * never needs: the recipient doesn't have the book yet, so the audio has to be placed, not just
 * the pack attached. Runs off [CompanionImportWorker] (a `WorkManager` job, not a foreground
 * service — §10.3 explains why: the manifest declares only `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
 * and a `dataSync` foreground service on `targetSdk 36` needs a permission and type this app
 * doesn't have and a background-start allowance a plain import doesn't get).
 *
 * Sequence, all inside the library root so the final placement is a same-filesystem rename, never
 * a second copy: **precheck free space (2×) → extract to a temp dir → verify name+size against the
 * manifest → move into place → register via [AudioFileScanner.importSingleFolder] → attach the
 * pack.** An interrupted run at any point before the move leaves no partial book in the library —
 * only an orphaned temp dir, cleaned up in `finally`.
 *
 * **Overlapping a library scan is safe** (§10.3 step 4). This service calls
 * [AudioFileScanner.importSingleFolder], which uses `DiskMirror.suppressed` — a third call site
 * for a primitive whose two originals (the scanner's serial loop, `BackupManager`'s restore) never
 * overlapped, so §10.3 required auditing it before adding one that can. That audit found the
 * counter itself sound (see [com.betteraudio.data.diskstore.SuppressionGate]) but `flushDirty`
 * ignoring suppression, which is now fixed — a concurrent flush can no longer mirror this
 * import's half-registered book to disk. See [DiskMirror.suppressed]'s kdoc for the full argument.
 */
@Singleton
class CompanionFullImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audiobookRepository: AudiobookRepository,
    private val audioFileScanner: AudioFileScanner,
    private val companionPackDao: CompanionPackDao,
    private val diskMirror: DiskMirror,
    private val settings: SettingsStore
) {
    sealed class PlacementResult {
        data class Success(val bookId: Long, val bookTitle: String, val packTitle: String) : PlacementResult()
        data class InsufficientSpace(val neededBytes: Long, val availableBytes: Long) : PlacementResult()
        data class VerificationFailed(val reason: String) : PlacementResult()
        data class NotAFullPack(val reason: String) : PlacementResult()
        data class Failure(val reason: String) : PlacementResult()
    }

    /** [sourceFile] is always a local, app-owned file by the time this runs — see
     *  [CompanionImportService.importFromUri]'s kdoc on why the original `content://` delivery URI
     *  is copied out before this is ever enqueued (a share-sheet grant isn't guaranteed to outlive
     *  the activity that received it, and this can run long after, including post-process-death). */
    suspend fun importFull(sourceFile: File): PlacementResult = withContext(Dispatchers.IO) {
        val root = settings.currentLibraryFolder
        if (root.isBlank()) return@withContext PlacementResult.Failure("No library folder set")
        val rootDir = File(root)
        if (!sourceFile.isFile) return@withContext PlacementResult.Failure("The pack file is missing")

        val tempDir = File(rootDir, "$IMPORT_TMP_DIR_NAME/${UUID.randomUUID()}")
        try {
            var manifest: CompanionManifestDoc? = null
            var doc: CompanionPackDoc? = null
            var spacePrechecked = false

            sourceFile.inputStream().use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            when {
                                entry.name == "manifest.json" -> {
                                    manifest = CompanionManifestCodec.decodeOrNull(String(zip.readBytes(), Charsets.UTF_8))
                                    val m = manifest
                                    if (m == null || m.payload != com.betteraudio.companion.model.PackPayload.FULL) {
                                        return@withContext PlacementResult.NotAFullPack("This isn't a FULL companion pack")
                                    }
                                    // §10.3: precheck is 2x the payload, done as early as possible —
                                    // manifest.json is always the first entry (see CompanionExportService),
                                    // so this runs before a single audio byte is extracted.
                                    val payloadBytes = m.audioFiles.sumOf { it.sizeBytes } + m.mediaFiles.size.toLong() * 0L
                                    val available = rootDir.usableSpace
                                    if (available < payloadBytes * 2) {
                                        return@withContext PlacementResult.InsufficientSpace(payloadBytes, available)
                                    }
                                    spacePrechecked = true
                                }
                                entry.name == "pack.json" -> doc = CompanionPackCodec.decodeOrNull(String(zip.readBytes(), Charsets.UTF_8))
                                entry.name.startsWith("audio/") && entry.name.length > "audio/".length -> {
                                    if (!spacePrechecked) return@withContext PlacementResult.Failure("Archive is missing its manifest")
                                    // Extracted under tempDir/audio/, kept separate from tempDir/media/
                                    // (the companion pack's own images) — only the audio/ subtree's
                                    // CONTENTS get moved into the book's target folder below.
                                    val fileName = entry.name.removePrefix("audio/")
                                    val out = File(tempDir, "audio/$fileName").apply { parentFile?.mkdirs() }
                                    out.outputStream().use { os -> zip.copyTo(os, bufferSize = 64 * 1024) }
                                }
                                entry.name.startsWith("media/") && entry.name.length > "media/".length -> {
                                    val fileName = entry.name.removePrefix("media/")
                                    val out = File(tempDir, "media/$fileName").apply { parentFile?.mkdirs() }
                                    out.outputStream().use { os -> zip.copyTo(os, bufferSize = 64 * 1024) }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            val m = manifest ?: return@withContext PlacementResult.NotAFullPack("This isn't a companion pack archive")
            val d = doc ?: return@withContext PlacementResult.Failure("Pack data is missing from the archive")
            val member = m.members.firstOrNull() ?: return@withContext PlacementResult.Failure("This pack doesn't reference any book")

            val extractedAudioDir = File(tempDir, "audio")
            val mismatch = verify(m, extractedAudioDir)
            if (mismatch != null) return@withContext PlacementResult.VerificationFailed(mismatch)

            val structure = ImportStructure.fromName(settings.importStructure.first())
            val targetDir = LibraryPaths.targetFolder(member.title, member.author, seriesName = null, structure = structure, root = root)
            if (targetDir.exists()) {
                // Never merge into an existing folder (LibraryRestructurer.moveOne's own rule) —
                // if a folder is already there, this import can't safely claim it as its own.
                return@withContext PlacementResult.Failure("\"${targetDir.name}\" already exists in your library")
            }
            targetDir.parentFile?.mkdirs()

            val mediaSubdir = File(tempDir, "media")
            val moved = runCatching { extractedAudioDir.copyRecursively(targetDir, overwrite = false) }.getOrDefault(false)
            if (!moved) {
                runCatching { targetDir.deleteRecursively() }
                return@withContext PlacementResult.Failure("Couldn't place the book's files")
            }

            val registered = audioFileScanner.importSingleFolder(
                dir = targetDir,
                forcedAuthor = member.author.ifBlank { null },
                seriesName = null,
                seriesOrder = member.seriesOrder
            )
            if (!registered) {
                runCatching { targetDir.deleteRecursively() }
                return@withContext PlacementResult.Failure("The placed files weren't recognised as an audiobook")
            }

            val book = audiobookRepository.getBookByFolder(targetDir.absolutePath)
                ?: return@withContext PlacementResult.Failure("Book was placed but couldn't be found afterward")

            attachPack(book, d, mediaSubdir)

            PlacementResult.Success(book.id, book.displayTitle, d.title)
        } catch (e: Exception) {
            PlacementResult.Failure(e.message ?: "Import failed")
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    /** Name + size only (§10.3 step 3) — not `fileKey`, matching
     *  [com.betteraudio.data.files.LibraryRestructurer.verify]'s own established recipe; see
     *  [com.betteraudio.companion.model.ManifestAudioFile]'s kdoc for why. */
    private fun verify(manifest: CompanionManifestDoc, extractedDir: File): String? {
        val expected = manifest.audioFiles.associate { it.fileName to it.sizeBytes }
        if (expected.isEmpty()) return "manifest declares no audio files"
        val actual = extractedDir.listFiles()?.filter { it.isFile }?.associate { it.name to it.length() }.orEmpty()
        if (expected == actual) return null
        val missing = expected.keys - actual.keys
        val sizeMismatch = expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }
        return "expected ${expected.size} file(s), got ${actual.size}" +
            (if (missing.isNotEmpty()) "; missing=$missing" else "") +
            (if (sizeMismatch.isNotEmpty()) "; size-mismatch=$sizeMismatch" else "")
    }

    private suspend fun attachPack(book: Book, doc: CompanionPackDoc, extractedMediaDir: File) {
        diskMirror.flushBookPack(book.id, doc)
        val mediaFiles = extractedMediaDir.listFiles()?.filter { it.isFile }.orEmpty()
        if (mediaFiles.isNotEmpty()) {
            val packDir = BookDataPaths.bookPackDir(book.folderPath, doc.packId)
            val mediaDir = File(packDir, "media").apply { mkdirs() }
            for (f in mediaFiles) runCatching { f.copyTo(File(mediaDir, f.name), overwrite = true) }
        }
        val targetKey = BookDataPaths.relPath(book.folderPath, settings.currentLibraryFolder)
        companionPackDao.upsert(
            CompanionPack(
                packId = doc.packId, scope = doc.scope.name, targetKey = targetKey, title = doc.title,
                authorHandle = doc.authorHandle, revision = doc.revision, enabled = true, lastSeenRevealMs = 0L, isOwn = false
            )
        )
        // No revealedMs write — a freshly-placed book has none anyway (§10.3 step 6, same rule as
        // CompanionImportService.attach for the DATA_ONLY case).
    }

    companion object {
        /** Inside the library root (not `filesDir`) so the final placement move is a same-
         *  filesystem rename, not a second copy across volumes — §10.1's explicit reasoning. */
        const val IMPORT_TMP_DIR_NAME = ".voyage_import_tmp"
    }
}
