package com.betteraudio.companion.model

import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Computes the file-identity key anchor #1 (docs/companion-packs.md §5) resolves against:
 * `sha1(size || first 64 KB || last 64 KB)`. Deliberately never hashes a whole file — a 900 MB
 * track on a phone is unacceptable — and deliberately excludes duration, which is decoder-derived
 * (`MediaMetadataRetriever`/`Mp4Probe`), not file-derived, and can disagree between two devices
 * holding a byte-identical file (see `Mp4Probe.kt`'s documented null-duration case on very large
 * files, and `PlayerController.playableDurationMs` for damage-repaired files reporting a shorter
 * playable length than the raw file). Size + head + tail is stable across both.
 *
 * Cheap (2 seeks + 128 KB read regardless of file size) but still real I/O — callers matching
 * against a whole library should cache the result rather than recomputing per import (see
 * `AudioFile.fileKey`, added lazily the same way `damageRangesJson` caches damage-scan results).
 */
object FileKey {
    private const val CHUNK_BYTES = 64 * 1024L

    fun compute(path: String): String? = runCatching {
        RandomAccessFile(path, "r").use { raf ->
            val size = raf.length()
            val digest = MessageDigest.getInstance("SHA-1")
            digest.update(size.toString().toByteArray(Charsets.UTF_8))

            val headLen = minOf(CHUNK_BYTES, size).toInt()
            val head = ByteArray(headLen)
            raf.seek(0)
            raf.readFully(head)
            digest.update(head)

            if (size > CHUNK_BYTES) {
                val tailStart = maxOf(size - CHUNK_BYTES, headLen.toLong())
                val tailLen = (size - tailStart).toInt()
                if (tailLen > 0) {
                    val tail = ByteArray(tailLen)
                    raf.seek(tailStart)
                    raf.readFully(tail)
                    digest.update(tail)
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrNull()
}
