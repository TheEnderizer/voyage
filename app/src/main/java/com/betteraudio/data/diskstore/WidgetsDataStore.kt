package com.betteraudio.data.diskstore

import android.content.Context
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.widget.model.WidgetDesignCodec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors every saved [WidgetDesign] into `.voyage/widgets/designs.json`, plus a copy of every
 * image an element references, into `.voyage/widgets/images/`. `filesDir/widget_images/<uuid>.jpg`
 * stays the ONLY live copy an element path ever points at — this is a backup, restored back into
 * that same folder under the same filenames on a reinstall, never read from directly otherwise.
 * `WidgetBinding` (which placed home-screen widget uses which design) is never written —
 * `appWidgetId` is launcher-assigned and ephemeral, meaningless on a different install.
 *
 * Deliberately the simplest store in the diskstore package: no relocation fallback, no per-design
 * content hash — designs.json is rewritten whole on every flushLibrary (it is a few KB), and only
 * the image copies are made incremental, since those are user photos. A design is far cheaper to
 * rebuild by hand than a lost listening position, so this is the first thing to cut if it ever
 * causes trouble (see the storage-redesign plan's phase ordering).
 */
@Singleton
class WidgetsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val designDao: WidgetDesignDao,
    private val settings: SettingsStore
) {
    suspend fun write(): Boolean = withContext(Dispatchers.IO) {
        val libraryFolder = settings.currentLibraryFolder
        val file = VoyageLayout.widgetDesignsFile(libraryFolder) ?: return@withContext false
        val imagesDir = VoyageLayout.widgetImagesDir(libraryFolder) ?: return@withContext false

        val designs = designDao.observeAll().first()
        val doc = WidgetsDocument(
            writtenAt = System.currentTimeMillis(),
            designs = designs.map { d ->
                WidgetsDocument.DesignEntry(d.name, d.aspectRatio, d.documentJson, d.createdAt, d.updatedAt)
            }
        )
        val ok = writeTextAtomic(file, WidgetsDataCodec.encodeToString(doc))
        if (!ok) return@withContext false
        file.parentFile?.let(::ensureNoMedia)

        // Copy every referenced image alongside the designs — best-effort per file, so one missed
        // source (deleted, unreadable) doesn't fail the whole write.
        val sourceDir = File(context.filesDir, WIDGET_IMAGES_LOCAL_DIR)
        imagesDir.mkdirs()
        val referenced = referencedImagePaths(designs)
        referenced.forEach { path ->
            val source = File(path)
            if (!source.isFile || source.parentFile != sourceDir) return@forEach
            // Skip an image already mirrored byte-for-byte. flushLibrary() runs on every preset,
            // series and author edit too, and widget images are user photos — re-copying all of
            // them on every series rename would be pointlessly expensive.
            val dest = File(imagesDir, source.name)
            if (dest.isFile && dest.length() == source.length() && dest.lastModified() >= source.lastModified()) {
                return@forEach
            }
            runCatching { source.copyTo(dest, overwrite = true) }
        }
        // Sweep anything left over from a since-deleted/edited design — same "referenced set wins"
        // rule BookDataStore.sweepOrphans and the live filesDir sweep both use. ".nomedia" is
        // explicitly spared: it is ours, but it is never "referenced" by a design, so without this
        // every write would delete the marker it just wrote.
        val referencedNames = referenced.mapTo(mutableSetOf()) { File(it).name }
        imagesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != ".nomedia" && f.name !in referencedNames) runCatching { f.delete() }
        }
        ensureNoMedia(imagesDir)
        true
    }

    suspend fun read(): WidgetsDocument? = withContext(Dispatchers.IO) {
        val file = VoyageLayout.widgetDesignsFile(settings.currentLibraryFolder)
            ?: return@withContext null
        if (!file.isFile) return@withContext null
        runCatching { file.readText() }.getOrNull()?.let { WidgetsDataCodec.decodeOrNull(it) }
    }

    /** Inserts every design from [doc] not already present (matched by name, same idempotency rule
     *  [com.betteraudio.widget.DefaultWidgetDesigns.ensureSeeded] uses), then copies its images back
     *  into `filesDir/widget_images/` under their original filenames — must run AFTER the design
     *  rows exist, or the next orphan sweep deletes the just-restored images as unreferenced. */
    suspend fun restore(doc: WidgetsDocument): Int {
        var inserted = 0
        for (entry in doc.designs) {
            val existing = designDao.getByName(entry.name)
            // A same-named row that is OLDER than the backup is almost always one of the two
            // designs DefaultWidgetDesigns.ensureSeeded() just recreated pristine (WidgetUpdater
            // seeds them on first construction, which can beat bootstrap). Skipping it outright
            // would silently discard the user's edits to "Cover & Controls"/"Minimal Bar".
            if (existing != null && existing.updatedAt >= entry.updatedAt) continue
            designDao.upsert(
                WidgetDesign(
                    id = existing?.id ?: 0L,
                    name = entry.name,
                    aspectRatio = entry.aspectRatio,
                    documentJson = entry.documentJson,
                    createdAt = entry.createdAt,
                    updatedAt = entry.updatedAt
                )
            )
            inserted++
        }
        withContext(Dispatchers.IO) {
            val imagesDir = VoyageLayout.widgetImagesDir(settings.currentLibraryFolder)
            if (imagesDir != null && imagesDir.isDirectory) {
                val destDir = File(context.filesDir, WIDGET_IMAGES_LOCAL_DIR).apply { mkdirs() }
                imagesDir.listFiles()?.forEach { f ->
                    // Never clobber a live image: a filename collision here would mean a UUID
                    // collision, but if one ever happened the in-app file is the one designs
                    // actually point at. (Checked explicitly rather than relying on copyTo's
                    // overwrite=false, which throws instead of skipping.)
                    val dest = File(destDir, f.name)
                    if (f.isFile && f.name != ".nomedia" && !dest.exists()) {
                        runCatching { f.copyTo(dest) }
                    }
                }
            }
        }
        return inserted
    }

    private fun referencedImagePaths(designs: List<WidgetDesign>): Set<String> {
        val referenced = mutableSetOf<String>()
        designs.forEach { d ->
            val doc = WidgetDesignCodec.decode(d.documentJson, d.aspectRatio)
            doc.elements.forEach { el ->
                el.imagePath?.let { referenced += it }
                el.backgroundLayer?.imagePath?.let { referenced += it }
            }
        }
        return referenced
    }

    private companion object {
        const val WIDGET_IMAGES_LOCAL_DIR = "widget_images"
    }
}
