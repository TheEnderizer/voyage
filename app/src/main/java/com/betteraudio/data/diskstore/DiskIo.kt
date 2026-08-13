package com.betteraudio.data.diskstore

import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
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
        // Non-atomic fallback — some FAT-formatted SD cards don't guarantee atomic rename. Worth
        // seeing (not every write): it means a reader could in principle observe a partial file
        // on this specific volume, which the whole tmp+rename dance exists to prevent.
        AppLog.d(LogCat.DISK) { "writeTextAtomic: rename failed for ${target.absolutePath}, falling back to a direct (non-atomic) write" }
        target.writeText(text)
        runCatching { tmp.delete() }
    }
    true
}.getOrElse {
    // ENOSPC surfaces here as a plain IOException from writeText/mkdirs — message-only detection
    // (no dedicated exception type on this path), but including it is still strictly more
    // information than the old bare "write failed" line, since freeSpace makes ENOSPC vs. a
    // permission/volume-unmount failure distinguishable at a glance without re-running anything.
    val free = runCatching { target.parentFile?.usableSpace }.getOrNull()
    AppLog.w(LogCat.DISK, "write failed for ${target.absolutePath} (free space on volume: ${free ?: "?"} bytes): ${it.message}")
    false
}

/** Ensures [dir] is hidden from the media store / gallery — same convention the scanner already
 *  uses for a book's own folder. Best-effort; a failure here is not worth surfacing. */
internal fun ensureNoMedia(dir: File) {
    val marker = File(dir, ".nomedia")
    if (!marker.exists()) runCatching { marker.createNewFile() }
}
