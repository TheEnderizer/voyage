package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chapter within a book. Either parsed from embedded file metadata (Nero `chpl`
 * chapters in M4B/M4A), or synthesized one-per-file when no embedded chapters exist.
 *
 * [startInFileMs] is the offset of the chapter start *within its [fileId] audio file*.
 * Absolute book-/group-level positions are derived at playback time from the cumulative
 * file offsets, so a chapter row stays valid even if files are re-scanned/re-ordered.
 */
@Entity(
    tableName = "chapters",
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
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val fileId: Long,
    val title: String,
    val startInFileMs: Long = 0,
    val durationMs: Long = 0,
    val orderIndex: Int = 0,
    val source: String = "per_file" // "embedded" | "per_file"
)
