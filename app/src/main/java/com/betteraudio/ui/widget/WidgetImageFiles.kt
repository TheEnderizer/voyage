package com.betteraudio.ui.widget

import android.content.Context
import android.net.Uri
import com.betteraudio.util.AppLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copies a photo-picker [uri] into `filesDir/widget_images/<uuid>.jpg` (widget designs are
 *  stored as plain file paths, like every other image path in this app — no persisted URI
 *  permissions to manage). Returns the absolute path, or null on failure. Unreferenced files
 *  (element deleted/replaced, design deleted) are swept up by WidgetGalleryViewModel's GC. */
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
