package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_progress",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AudioFile::class,
            parentColumns = ["id"],
            childColumns = ["currentFileId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("bookId", unique = true), Index("currentFileId")]
)
data class PlaybackProgress(
    @PrimaryKey val bookId: Long,
    val currentFileId: Long? = null,
    val positionMs: Long = 0,
    val lastPlayedMs: Long = System.currentTimeMillis(),
    val playbackSpeed: Float = 1.0f,
    val boostDb: Int = 0,
    val eqBandsJson: String? = null,
    val isCompleted: Boolean = false,
    val completedDateMs: Long? = null,
    val lastPausedAt: Long = 0L,
    // ── Ebook reading position ───────────────────────────────────────────────
    val textSpineIndex: Int? = null,      // epub spine item last read; null = never read
    val textFraction: Float? = null,      // scroll fraction (0..1) within that spine item
    // Render-stream char offset (com.betteraudio.data.ebook.render.RenderDocument coordinates) of
    // the reading position within textSpineIndex — the real position for the native renderer.
    // Null until the native renderer has actually written one; textFraction stays the fallback
    // (charOffsetForFraction) until then. Deliberately NOT back-filled from existing textFraction
    // rows on migration — the epub reading position has no user-facing value worth preserving
    // across this migration, unlike audio position, which this column is added beside but does
    // not touch.
    val textCharOffset: Int? = null,
    // Whole-book reading fraction, denormalized so grid progress bars never need to parse the
    // epub or its chapter map. Written by the reader as (spineIndex + fraction) / spineCount.
    val textOverallFraction: Float = 0f,
    // Which position is freshest — "AUDIO" | "TEXT". Drives which side to convert FROM when the
    // other mode is opened (e.g. opening the reader while lastMode == AUDIO re-derives the text
    // locator from the current audio position instead of using a stale stored one).
    val lastMode: String = "AUDIO",
    // Sum of durationMs for every audio_files row before currentFileId (ordered by trackNumber,
    // fileName — the same order BookWithProgress.audioFiles uses). Denormalized so the home grid's
    // progress bar (HomeGridBook.progressFraction) never needs to load a book's full file list —
    // that per-book join, done for every book in the library on every recomposition, was the
    // dominant cost this column exists to remove. Recomputed by AudiobookRepository.updatePosition
    // whenever currentFileId actually changes.
    val filesBeforeCurrentMs: Long = 0L,
    // ── Companion packs (docs/companion-packs.md §6) ─────────────────────────
    // How far the companion is allowed to reveal, in BOOK-GLOBAL milliseconds — NOT the same
    // coordinate space as positionMs above, which is file-relative (global position is
    // filesBeforeCurrentMs + positionMs). Never compare the two directly. Moves in exactly three
    // ways (see playback/RevealCursor.kt): continuous listening advances it; a raw seek never
    // moves it; a user-confirmed seek moves it either direction. Cleared to 0 by
    // PlaybackProgressDao.resetProgress — there is no other reset path in this table.
    val revealedMs: Long = 0L
)
