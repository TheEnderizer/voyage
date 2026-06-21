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
    val isCompleted: Boolean = false,
    val completedDateMs: Long? = null
)
