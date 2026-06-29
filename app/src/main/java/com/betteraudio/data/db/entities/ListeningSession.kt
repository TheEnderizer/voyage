package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One continuous listening session for a book: opened when playback starts, closed on
 * pause / stop / book-change. `listenedMs` is accumulated *playing* time (pause gaps
 * excluded), distinct from the wall-clock span `endMs - startMs`.
 */
@Entity(
    tableName = "listening_sessions",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class ListeningSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startMs: Long,
    val endMs: Long = 0,
    val startChapterIndex: Int = -1,
    val startChapterName: String = "",
    val endChapterIndex: Int = -1,
    val endChapterName: String = "",
    val startPositionInChapterMs: Long = 0,
    val endPositionInChapterMs: Long = 0,
    // Absolute book-level position where the session ended — used to resume from here.
    val endBookPositionMs: Long = 0,
    val listenedMs: Long = 0
)
