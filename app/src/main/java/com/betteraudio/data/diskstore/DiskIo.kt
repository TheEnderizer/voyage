package com.betteraudio.data.diskstore

import com.betteraudio.util.AppLog
import java.io.File

/**
 * Best-effort atomic write: write to a ".tmp" sibling then rename over the target, so a reader
 * never observes a half-written file. Falls back to a direct write if the rename fails (some
 * FAT-formatted SD cards don't guarantee atomic rename). Never throws — every disk-mirror file
 * is a convenience mirror, not the source of truth (Room/the DB still is until it's read back),
 * matching MappingFileIO's existing recipe.
 */
internal fun writeTextAtomic(target: File, text: String): Boolean = runCatching {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(target)) {
        target.writeText(text)
        runCatching { tmp.delete() }
    }
    true
}.getOrElse {
    AppLog.w("DiskStore", "write failed for ${target.absolutePath}: ${it.message}")
    false
}

/** Ensures [dir] is hidden from the media store / gallery — same convention the scanner already
 *  uses for a book's own folder. Best-effort; a failure here is not worth surfacing. */
internal fun ensureNoMedia(dir: File) {
    val marker = File(dir, ".nomedia")
    if (!marker.exists()) runCatching { marker.createNewFile() }
}
