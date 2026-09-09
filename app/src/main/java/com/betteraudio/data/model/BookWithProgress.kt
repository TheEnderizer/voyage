package com.betteraudio.data.model

import androidx.room.Embedded
import androidx.room.Relation
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.PlaybackProgress

data class BookWithProgress(
    @Embedded val book: Book,
    @Relation(parentColumn = "id", entityColumn = "bookId")
    val progress: PlaybackProgress?,
    @Relation(parentColumn = "id", entityColumn = "bookId")
    val audioFiles: List<AudioFile>
) {
    val progressFraction: Float get() {
        val total = book.totalDurationMs.takeIf { it > 0 } ?: return 0f
        val pos = progress?.positionMs ?: 0L
        // Add duration of all files before the current file
        val currentFileId = progress?.currentFileId
        val filesBeforeCurrent = if (currentFileId != null) {
            audioFiles.takeWhile { it.id != currentFileId }.sumOf { it.durationMs }
        } else 0L
        return ((filesBeforeCurrent + pos).toFloat() / total).coerceIn(0f, 1f)
    }

    val lastPlayedMs: Long get() = progress?.lastPlayedMs ?: book.addedDateMs
}
