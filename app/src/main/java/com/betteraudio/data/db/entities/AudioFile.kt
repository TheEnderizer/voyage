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
    indices = [Index("bookId")]
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
    val chapterTitle: String? = null
)
