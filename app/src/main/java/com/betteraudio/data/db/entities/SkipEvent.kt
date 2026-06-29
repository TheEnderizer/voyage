package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A *confirmed* navigation jump within a book — chapter selection, bookmark jump, or a
 * confirmed long scrubber drag. Fixed-amount skip-button taps and unconfirmed scrubs are
 * intentionally NOT recorded here.
 */
@Entity(
    tableName = "skip_events",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class SkipEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val atMs: Long = System.currentTimeMillis(),
    val fromPositionMs: Long,
    val toPositionMs: Long,
    val chapterIndex: Int = -1,
    val chapterName: String = ""
)
