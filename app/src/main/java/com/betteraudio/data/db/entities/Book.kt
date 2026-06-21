package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BookStatus { NOT_STARTED, IN_PROGRESS, FINISHED }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val seriesName: String? = null,
    val seriesOrder: Float? = null,
    val folderPath: String,
    val coverArtPath: String? = null,
    val totalDurationMs: Long = 0,
    val addedDateMs: Long = System.currentTimeMillis(),
    val status: BookStatus = BookStatus.NOT_STARTED,
    val fileCount: Int = 0,
    val groupId: Long? = null,   // non-null = this book belongs to a BookGroup
    val synopsis: String? = null, // AI-generated synopsis
    // ── Extra metadata pulled from the audio file tags ──────────────────────
    val narrator: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val album: String? = null,
    val description: String? = null // embedded comment / description tag
)
