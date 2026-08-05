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
