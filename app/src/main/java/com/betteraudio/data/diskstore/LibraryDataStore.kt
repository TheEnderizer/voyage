package com.betteraudio.data.diskstore

import com.betteraudio.data.db.dao.AudioPresetDao
import com.betteraudio.data.db.dao.AuthorMetaDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes `<libraryRoot>/.voyage/library.json` (presets, authors, series — the
 * library-wide data with no single book folder to live in) plus the series/author covers that
 * live in `.voyage/covers/` because those two have no folder of their own. Same DAO-only
 * dependency rule as [BookDataStore]: no repository injected, to avoid a Hilt cycle.
 */
@Singleton
class LibraryDataStore @Inject constructor(
    private val seriesDao: SeriesDao,
    private val bookDao: BookDao,
    private val authorMetaDao: AuthorMetaDao,
    private val audioPresetDao: AudioPresetDao,
    private val settings: SettingsStore
) {
    suspend fun write(): Boolean {
        val libraryFolder = settings.currentLibraryFolder
        val file = VoyageLayout.libraryFile(libraryFolder) ?: return false

        val presets = audioPresetDao.getAll().first().map { p ->
            LibraryDocument.PresetEntry(p.name, p.type, p.speedMult, p.boostDb, p.eqBandsJson, p.isDefault)
        }
        val authors = authorMetaDao.getAllOnce().map { a ->
            LibraryDocument.AuthorEntry(a.name, a.coverArtPath?.let { relativizeCover(libraryFolder, it) })
        }
        val series = seriesDao.getAllOnce().map { s ->
            val members = bookDao.getBooksInSeriesByIdOnce(s.id).map { b ->
                LibraryDocument.MemberRef(b.folderPath, BookDataPaths.relPath(b.folderPath, libraryFolder), b.title, b.author, b.seriesOrder)
            }
            LibraryDocument.SeriesEntry(
                name = s.name, author = s.author, narrator = s.narrator, description = s.description,
                playbackSpeed = s.playbackSpeed, boostDb = s.boostDb, eqBandsJson = s.eqBandsJson,
                skipSilenceEnabled = s.skipSilenceEnabled,
                cover = s.coverArtPath?.let { relativizeCover(libraryFolder, it) },
                createdAtMs = s.createdAtMs, members = members
            )
        }
        val existing = readDocFile(file)
        val doc = LibraryDocument(
            writtenAt = System.currentTimeMillis(),
            libraryRoot = libraryFolder,
            presets = presets, authors = authors, series = series,
            unknown = existing?.unknown ?: emptyMap()
        )
        val ok = writeTextAtomic(file, LibraryDataCodec.encodeToString(doc))
        if (ok) file.parentFile?.let(::ensureNoMedia)
        return ok
    }

    suspend fun read(): LibraryDocument? = readDocFile(VoyageLayout.libraryFile(settings.currentLibraryFolder))

    fun lastModified(): Long {
        val file = VoyageLayout.libraryFile(settings.currentLibraryFolder) ?: return 0L
        return if (file.isFile) file.lastModified() else 0L
    }

    suspend fun writeSeriesCoverBytes(name: String, ext: String, bytes: ByteArray): String? =
        writeNamedCover("series", name, ext) { it.writeBytes(bytes) }

    suspend fun writeAuthorCoverBytes(name: String, ext: String, bytes: ByteArray): String? =
        writeNamedCover("author", name, ext) { it.writeBytes(bytes) }

    suspend fun writeSeriesCoverStream(name: String, ext: String, input: InputStream): String? =
        writeNamedCover("series", name, ext) { it.outputStream().use { out -> input.copyTo(out) } }

    suspend fun writeAuthorCoverStream(name: String, ext: String, input: InputStream): String? =
        writeNamedCover("author", name, ext) { it.outputStream().use { out -> input.copyTo(out) } }

    suspend fun writeSeriesCoverFile(name: String, ext: String, source: File): String? =
        writeNamedCover("series", name, ext) { source.copyTo(it, overwrite = true) }

    suspend fun writeAuthorCoverFile(name: String, ext: String, source: File): String? =
        writeNamedCover("author", name, ext) { source.copyTo(it, overwrite = true) }

    /** Resolves a doc-recorded cover path — relative to `.voyage/`, or an absolute fallback —
     *  back to a real file, or null if it no longer exists. */
    fun resolveCover(relOrAbs: String): File? {
        val root = VoyageLayout.rootDir(settings.currentLibraryFolder) ?: return null
        return BookDataPaths.resolveRelOrAbs(root, relOrAbs).takeIf { it.isFile }
    }

    /** Always on IO — same reason as [BookDataStore.writeCoverInternal]: the online-search call
     *  site is a ViewModel writing to external storage from a Main-dispatched scope. */
    private suspend fun writeNamedCover(
        kind: String, name: String, ext: String, writer: (File) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val coversDir = VoyageLayout.coversDir(settings.currentLibraryFolder)
            ?: return@withContext null
        runCatching {
            coversDir.mkdirs()
            val baseName = "${kind}_${BookDataPaths.slug(name)}"
            coversDir.listFiles { f -> f.isFile && f.nameWithoutExtension == baseName }?.forEach { it.delete() }
            val target = File(coversDir, "$baseName.$ext")
            writer(target)
            ensureNoMedia(coversDir)
            target.absolutePath
        }.getOrElse {
            AppLog.w("DiskStore", "write $kind cover failed for '$name': ${it.message}")
            null
        }
    }

    private fun relativizeCover(libraryFolder: String, absolutePath: String): String {
        val root = VoyageLayout.rootDir(libraryFolder) ?: return absolutePath
        return BookDataPaths.relativizeToDir(absolutePath, root)
    }

    private fun readDocFile(file: File?): LibraryDocument? {
        if (file == null || !file.isFile) return null
        return runCatching { file.readText() }.getOrNull()?.let { LibraryDataCodec.decodeOrNull(it) }
    }
}
