package com.betteraudio.companion

import android.content.Context
import com.betteraudio.companion.model.CompanionManifestCodec
import com.betteraudio.companion.model.CompanionManifestDoc
import com.betteraudio.companion.model.CompanionPackCodec
import com.betteraudio.companion.model.FileKey
import com.betteraudio.companion.model.ManifestAudioFile
import com.betteraudio.companion.model.PackPayload
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.CompanionPackDao
import com.betteraudio.data.db.entities.sizeOnDisk
import com.betteraudio.data.diskstore.BookDataPaths
import com.betteraudio.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a `.voyagepack` archive for a BOOK-scoped pack (docs/companion-packs.md §10.1, P4 —
 * **DATA_ONLY only**; bundling audio is P7). Container layout, fixed by §10.1:
 * ```
 * manifest.json
 * pack.json
 * media/...
 * ```
 * Written to `filesDir/companion_share/` (a fixed filename, mirroring
 * [com.betteraudio.data.backup.BackupManager.writeShareBackupFile]) and handed to the caller as a
 * plain [File] — sharing it (via `FileProvider` + `ACTION_SEND`) is a UI concern, not this
 * service's.
 *
 * **No sanitization step here, and that's not an oversight.** §10.1's sanitize-on-export warning
 * is about a book's `data/book.json` (progress, `revealedMs`, sessions) leaking into a bundle that
 * includes the book folder — which only happens for a FULL export (P7). `pack.json` itself has no
 * progress field anywhere in its schema (see [com.betteraudio.companion.model.CompanionPackDoc]),
 * so a DATA_ONLY export built from *only* the pack's own directory is clean by construction: there
 * is nothing to strip.
 */
@Singleton
class CompanionExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao,
    private val companionPackDao: CompanionPackDao,
    private val settings: SettingsStore
) {
    sealed class ExportResult {
        data class Success(val file: File, val title: String) : ExportResult()
        data class Failure(val reason: String) : ExportResult()
    }

    suspend fun exportBookPack(bookId: Long, packId: String): ExportResult = withContext(Dispatchers.IO) {
        val book = bookDao.getBookOnce(bookId) ?: return@withContext ExportResult.Failure("Book not found")
        val pack = companionPackDao.getById(packId) ?: return@withContext ExportResult.Failure("Pack not found")
        val packDir = BookDataPaths.bookPackDir(book.folderPath, packId)
        val packFile = File(packDir, "pack.json")
        if (!packFile.isFile) return@withContext ExportResult.Failure("This pack has no data to export yet")
        val doc = runCatching { packFile.readText() }.getOrNull()?.let { CompanionPackCodec.decodeOrNull(it) }
            ?: return@withContext ExportResult.Failure("Pack data is corrupt")

        val mediaDir = File(packDir, "media")
        val mediaFiles = mediaDir.listFiles()?.filter { it.isFile }.orEmpty()

        val manifest = CompanionManifestDoc(
            packId = doc.packId,
            scope = doc.scope,
            payload = com.betteraudio.companion.model.PackPayload.DATA_ONLY,
            title = doc.title,
            members = doc.members,
            mediaFiles = mediaFiles.map { "media/${it.name}" }
        )

        val outDir = File(context.filesDir, "companion_share").apply { mkdirs() }
        val safeTitle = doc.title.ifBlank { "companion" }.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifBlank { "companion" }
        val outFile = File(outDir, "$safeTitle.voyagepack")

        val ok = runCatching {
            ZipOutputStream(outFile.outputStream()).use { zip ->
                putEntry(zip, "manifest.json", CompanionManifestCodec.encodeToString(manifest).toByteArray())
                // The pack's own doc — re-encoded from the parsed model (not a raw file copy) so
                // the export always reflects a document CompanionPackCodec can actually parse back,
                // never a half-written file mid-edit.
                putEntry(zip, "pack.json", CompanionPackCodec.encodeToString(doc).toByteArray())
                for (f in mediaFiles) {
                    putEntry(zip, "media/${f.name}", f.readBytes())
                }
            }
        }.isSuccess

        if (!ok) {
            runCatching { outFile.delete() }
            return@withContext ExportResult.Failure("Couldn't write the export file")
        }
        ExportResult.Success(outFile, doc.title)
    }

    /**
     * FULL export (§10.1/§12 P7) — everything [exportBookPack] does, plus every audio file zipped
     * under `audio/` **STORED, not DEFLATED**: audio is already compressed, so deflating it again
     * wastes CPU for zero size benefit, and a stored entry lets a reader stream it out without
     * inflating first (§10.1's stated reason for the STORED requirement). Realistically a USB/Drive
     * transfer, never a messaging app — audiobooks are hundreds of MB to several GB.
     *
     * Reads/hashes every file **streamed**, never loaded whole into memory (`file.readBytes()` on
     * a multi-GB file would exhaust the heap well before EOF) — the one correctness property this
     * method cannot get wrong. [AudioFile.fileKey] is computed and cached lazily here exactly the
     * way `damageRangesJson` is (see its kdoc): FULL export is the first place a fingerprint is
     * actually needed, so this is that "populated lazily" moment for every file in this book.
     */
    suspend fun exportBookPackFull(bookId: Long, packId: String): ExportResult = withContext(Dispatchers.IO) {
        val book = bookDao.getBookOnce(bookId) ?: return@withContext ExportResult.Failure("Book not found")
        companionPackDao.getById(packId) ?: return@withContext ExportResult.Failure("Pack not found")
        val packDir = BookDataPaths.bookPackDir(book.folderPath, packId)
        val packFile = File(packDir, "pack.json")
        if (!packFile.isFile) return@withContext ExportResult.Failure("This pack has no data to export yet")
        val doc = runCatching { packFile.readText() }.getOrNull()?.let { CompanionPackCodec.decodeOrNull(it) }
            ?: return@withContext ExportResult.Failure("Pack data is corrupt")

        val audioFiles = audioFileDao.getFilesForBookOnce(bookId).filter { File(it.filePath).isFile }
        if (audioFiles.isEmpty()) return@withContext ExportResult.Failure("This book has no audio files to bundle")

        val fileKeys = audioFiles.map { af ->
            af.fileKey ?: FileKey.compute(af.filePath)?.also { computed ->
                runCatching { audioFileDao.updateFileKey(af.id, computed) }
            }
        }

        val member = (doc.members.firstOrNull() ?: com.betteraudio.companion.model.PackMember(bookRef = "m1", title = book.displayTitle, author = book.displayAuthor)).copy(
            durationMs = book.totalDurationMs,
            fileCount = audioFiles.size,
            fileKeys = fileKeys.filterNotNull()
        )
        val fullDoc = doc.copy(members = listOf(member))

        val mediaDir = File(packDir, "media")
        val mediaFiles = mediaDir.listFiles()?.filter { it.isFile }.orEmpty()

        val manifestAudioFiles = audioFiles.mapIndexed { i, af ->
            ManifestAudioFile(fileName = File(af.filePath).name, sizeBytes = af.sizeOnDisk(), fileKey = fileKeys[i])
        }
        val manifest = CompanionManifestDoc(
            packId = fullDoc.packId,
            scope = fullDoc.scope,
            payload = PackPayload.FULL,
            title = fullDoc.title,
            members = listOf(member),
            mediaFiles = mediaFiles.map { "media/${it.name}" },
            audioFiles = manifestAudioFiles
        )

        val outDir = File(context.filesDir, "companion_share").apply { mkdirs() }
        val safeTitle = fullDoc.title.ifBlank { "companion" }.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().ifBlank { "companion" }
        val outFile = File(outDir, "$safeTitle.full.voyagepack")

        val ok = runCatching {
            ZipOutputStream(outFile.outputStream()).use { zip ->
                putEntry(zip, "manifest.json", CompanionManifestCodec.encodeToString(manifest).toByteArray())
                putEntry(zip, "pack.json", CompanionPackCodec.encodeToString(fullDoc).toByteArray())
                for (f in mediaFiles) putEntry(zip, "media/${f.name}", f.readBytes())
                for (af in audioFiles) putStoredEntry(zip, "audio/${File(af.filePath).name}", File(af.filePath))
            }
        }.isSuccess

        if (!ok) {
            runCatching { outFile.delete() }
            return@withContext ExportResult.Failure("Couldn't write the export file")
        }
        ExportResult.Success(outFile, fullDoc.title)
    }

    private fun putEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** A STORED entry's size/compressedSize/crc must be known BEFORE `putNextEntry` — java.util.zip
     *  can't compute them lazily while streaming the way a DEFLATED entry can. That means two
     *  streamed passes over [file] (checksum, then copy), never one buffered read of the whole
     *  thing — the memory-bounded property [exportBookPackFull]'s kdoc promises. */
    private fun putStoredEntry(zip: ZipOutputStream, name: String, file: File) {
        val checksum = CRC32()
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) checksum.update(buffer, 0, read)
        }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            crc = checksum.value
        }
        zip.putNextEntry(entry)
        file.inputStream().use { input -> input.copyTo(zip, bufferSize = 64 * 1024) }
        zip.closeEntry()
    }
}
