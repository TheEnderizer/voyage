package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A verified listen↔read reference point produced by on-device forced alignment: at [audioMs] on
 * the book timeline, the narration is at character [charOffset] (paragraph [paragraphIndex]) of
 * epub spine item [spineIndex]. `PositionBridge` interpolates between anchors for paragraph-
 * resolution position conversion. Cleared when the book's epub changes.
 */
@Entity(
    tableName = "sync_anchors",
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
data class SyncAnchor(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val audioMs: Long,
    val spineIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val confidence: Float,
    val createdAtMs: Long = System.currentTimeMillis()
)
