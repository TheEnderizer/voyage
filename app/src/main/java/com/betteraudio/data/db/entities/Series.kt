package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A first-class audiobook series. Books belong to a series via [Book.seriesId]; each book keeps
 * its own progress/status, so a series is a presentation + playback-ordering layer, never a
 * merge of the members' data.
 *
 * The nullable cascade fields ([playbackSpeed], [boostDb], [eqBandsJson], [skipSilenceEnabled],
 * [author], [narrator]) are series-level defaults: null means "not set", and a member book's own
 * value overrides the series value when present. [coverArtPath]/[coverFxPath] are the series'
 * own cover (independent of any member book cover).
 */
@Entity(tableName = "series")
data class Series(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val author: String? = null,
    val narrator: String? = null,
    val coverArtPath: String? = null,
    val coverFxPath: String? = null,
    val description: String? = null,
    val playbackSpeed: Float? = null,
    val boostDb: Int? = null,
    val eqBandsJson: String? = null,
    val skipSilenceEnabled: Boolean? = null,
    val createdAtMs: Long = System.currentTimeMillis()
)
