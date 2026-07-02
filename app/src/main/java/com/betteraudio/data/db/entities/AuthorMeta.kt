package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-author presentation data for the Authors home view. Authors are a lightweight grouping
 * (keyed by author name) that only carry a custom cover — no cascade options or info page.
 * A row exists only once the user customises that author (e.g. sets a cover).
 */
@Entity(tableName = "author_meta")
data class AuthorMeta(
    @PrimaryKey val name: String,
    val coverArtPath: String? = null,
    val coverFxPath: String? = null
)
