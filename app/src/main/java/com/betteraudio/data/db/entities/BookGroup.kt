package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "book_groups")
data class BookGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val coverArtPath: String? = null,
    val playbackSpeed: Float = 1.0f,
    val createdAtMs: Long = System.currentTimeMillis()
)
