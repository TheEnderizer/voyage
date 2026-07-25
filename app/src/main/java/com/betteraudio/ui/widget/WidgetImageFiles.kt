package com.betteraudio.ui.widget

import android.content.Context
import android.net.Uri
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.util.AppLog
import com.betteraudio.widget.model.WidgetDesignCodec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Copies a photo-picker [uri] into `filesDir/widget_images/<uuid>.jpg` (widget designs are
 *  stored as plain file paths, like every other image path in this app — no persisted URI
 *  permissions to manage). Returns the absolute path, or null on failure. Unreferenced files
 *  (element deleted/replaced, design deleted) are swept up by [sweepOrphanWidgetImages]. */
suspend fun copyPickedWidgetImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "widget_images").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (dest.exists() && dest.length() > 0) dest.absolutePath else null
    } catch (e: Exception) {
        AppLog.e("Widget", "failed to copy picked image", e)
        null
    }
}

/** Deletes every file under `filesDir/widget_images/` not referenced (as a background or element
 *  image path) by any current design — called after a design is saved (old images replaced) or
 *  deleted (all its images orphaned). */
suspend fun sweepOrphanWidgetImages(context: Context, designDao: WidgetDesignDao) = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "widget_images")
        val files = dir.listFiles() ?: return@withContext
        val designs = designDao.observeAll().first()
        val referenced = mutableSetOf<String>()
        for (d in designs) {
            val doc = WidgetDesignCodec.decode(d.documentJson, d.aspectRatio)
            doc.elements.forEach { el ->
                el.imagePath?.let { referenced += it }
                el.backgroundLayer?.imagePath?.let { referenced += it }
            }
        }
        for (f in files) {
            if (f.absolutePath !in referenced) f.delete()
        }
    } catch (e: Exception) {
        AppLog.e("Widget", "orphan widget-image sweep failed", e)
    }
}
