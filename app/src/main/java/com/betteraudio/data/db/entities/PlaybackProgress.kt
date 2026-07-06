package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_progress",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudioFile::class,
            parentColumns = ["id"],
            childColumns = ["currentFileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("bookId", unique = true), Index("currentFileId")]
)
data class PlaybackProgress(
    @PrimaryKey val bookId: Long,
    val currentFileId: Long? = null,
    val positionMs: Long = 0,
    val lastPlayedMs: Long = System.currentTimeMillis(),
    val playbackSpeed: Float = 1.0f,
    val boostDb: Int = 0,
    val eqBandsJson: String? = null,
    val isCompleted: Boolean = false,
    val completedDateMs: Long? = null,
    val lastPausedAt: Long = 0L,
    // ── Ebook reading position ───────────────────────────────────────────────
    val textSpineIndex: Int? = null,      // epub spine item last read; null = never read
    val textFraction: Float? = null,      // scroll fraction (0..1) within that spine item
    // Whole-book reading fraction, denormalized so grid progress bars never need to parse the
    // epub or its chapter map. Written by the reader as (spineIndex + fraction) / spineCount.
    val textOverallFraction: Float = 0f,
    // Which position is freshest — "AUDIO" | "TEXT". Drives which side to convert FROM when the
    // other mode is opened (e.g. opening the reader while lastMode == AUDIO re-derives the text
    // locator from the current audio position instead of using a stale stored one).
    val lastMode: String = "AUDIO"
)
