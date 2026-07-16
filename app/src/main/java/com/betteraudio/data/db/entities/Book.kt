package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class BookStatus { NOT_STARTED, IN_PROGRESS, FINISHED }

@Entity(tableName = "books", indices = [Index("seriesId")])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    // Source of truth for series membership. seriesName is kept as a denormalised cache/hint
    // (scanner + legacy) but seriesId is authoritative.
    val seriesId: Long? = null,
    val seriesName: String? = null,
    val seriesOrder: Float? = null,
    val folderPath: String,
    val coverArtPath: String? = null,
    // Pre-rendered reflection + progressive-blur composite (baked once, reused at
    // render time so the screens don't recompute the blur every frame). Null = not
    // yet baked / invalidated by a cover change; the UI live-renders as a fallback.
    val coverFxPath: String? = null,
    val totalDurationMs: Long = 0,
    val addedDateMs: Long = System.currentTimeMillis(),
    val status: BookStatus = BookStatus.NOT_STARTED,
    val fileCount: Int = 0,
    val synopsis: String? = null, // AI-generated synopsis
    // ── Extra metadata pulled from the audio file tags ──────────────────────
    val narrator: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val album: String? = null,
    val description: String? = null, // embedded comment / description tag
    // ── User-editable overrides (scanner never writes here) ─────────────────
    val titleOverride: String? = null,
    val authorOverride: String? = null,
    val isIgnored: Boolean = false,
    // Per-book toggle: when on, the player auto-skips silent gaps (sensitivity + min length
    // are global, in SettingsStore).
    val skipSilenceEnabled: Boolean = false,
    // ── Ebook (EPUB) support ─────────────────────────────────────────────────
    // Absolute path to a connected .epub. Non-null = this book has an ebook (either attached to an
    // audiobook, or a standalone ebook-only row where fileCount/totalDurationMs are both 0).
    val ebookPath: String? = null,
    // Spine item count at last parse — used for whole-book reading-progress math without
    // re-parsing the epub on every read of PlaybackProgress.
    val ebookSpineCount: Int = 0,
    // Audio-chapter-index ↔ epub-spine-index alignment (JSON int array), or null = not yet
    // computed (auto-matched on next reader/sync use). Nulled whenever ebookPath changes.
    val chapterMapJson: String? = null
) {
    val displayTitle: String get() = titleOverride ?: title
    val displayAuthor: String get() = authorOverride ?: author
}
