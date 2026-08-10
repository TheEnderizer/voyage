package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "audio_files",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId"), Index("filePath")]
)
data class AudioFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val filePath: String,
    val fileName: String,
    val trackNumber: Int = 0,
    val title: String? = null,
    val durationMs: Long = 0,
    val fileSizeBytes: Long = 0,
    val chapterTitle: String? = null,
    /**
     * Damaged byte ranges found by [com.betteraudio.playback.Mp3DamageScanner], as
     * `"start-end,start-end,…"`. Null = never scanned; empty = scanned and clean.
     *
     * Populated on demand, only after a file actually fails to play (scanning is a full sequential
     * read, far too costly for a library scan). Cached here so a damaged file is scanned once ever
     * rather than once per play — see PlayerController's corrupt-file recovery.
     */
    val damageRangesJson: String? = null
)

/**
 * The file's size in bytes, falling back to a `stat` when the column is unset.
 *
 * [AudioFile.fileSizeBytes] has only been populated since the scanner started recording it, so rows
 * imported by an older build still read 0. Callers that use the size to *judge* something — see
 * `Mp3DamageScanner.decodeUsable` — must not read that 0 as "empty file", so resolve it here.
 */
fun AudioFile.sizeOnDisk(): Long =
    if (fileSizeBytes > 0L) fileSizeBytes else java.io.File(filePath).length()
