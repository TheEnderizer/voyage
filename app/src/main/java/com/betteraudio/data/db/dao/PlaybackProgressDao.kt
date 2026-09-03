package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.PlaybackProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookOnce(bookId: Long): PlaybackProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PlaybackProgress)

    @Query("""
        UPDATE playback_progress
        SET currentFileId = :fileId, positionMs = :positionMs, lastPlayedMs = :lastPlayedMs,
            filesBeforeCurrentMs = :filesBeforeCurrentMs, isCompleted = 0
        WHERE bookId = :bookId
    """)
    suspend fun updatePosition(bookId: Long, fileId: Long, positionMs: Long, lastPlayedMs: Long, filesBeforeCurrentMs: Long)

    @Query("UPDATE playback_progress SET playbackSpeed = :speed WHERE bookId = :bookId")
    suspend fun updateSpeed(bookId: Long, speed: Float)

    @Query("UPDATE playback_progress SET boostDb = :boostDb WHERE bookId = :bookId")
    suspend fun updateBoostDb(bookId: Long, boostDb: Int)

    @Query("UPDATE playback_progress SET eqBandsJson = :json WHERE bookId = :bookId")
    suspend fun updateEqBands(bookId: Long, json: String?)

    @Query("UPDATE playback_progress SET lastPlayedMs = :ts WHERE bookId = :bookId")
    suspend fun touchLastPlayed(bookId: Long, ts: Long): Int

    @Query("UPDATE playback_progress SET lastPausedAt = :ts WHERE bookId = :bookId")
    suspend fun updateLastPausedAt(bookId: Long, ts: Long)

    @Query("""
        UPDATE playback_progress
        SET isCompleted = 1, completedDateMs = :completedMs
        WHERE bookId = :bookId
    """)
    suspend fun markCompleted(bookId: Long, completedMs: Long)

    // ── Ebook reading position ───────────────────────────────────────────────
    // Returns affected-row count (0 = no progress row yet for this book) so the repository can
    // fall back to an upsert, mirroring the existing touchLastPlayed pattern.
    @Query("""
        UPDATE playback_progress
        SET textSpineIndex = :spineIndex, textFraction = :fraction, textCharOffset = :charOffset,
            textOverallFraction = :overallFraction, lastMode = 'TEXT', lastPlayedMs = :ts
        WHERE bookId = :bookId
    """)
    suspend fun updateTextPosition(bookId: Long, spineIndex: Int, fraction: Float, charOffset: Int?, overallFraction: Float, ts: Long): Int

    @Query("UPDATE playback_progress SET lastMode = 'AUDIO' WHERE bookId = :bookId")
    suspend fun setLastModeAudio(bookId: Long): Int

    // ── Companion packs (docs/companion-packs.md §6) ─────────────────────────
    @Query("SELECT revealedMs FROM playback_progress WHERE bookId = :bookId")
    suspend fun getRevealedMs(bookId: Long): Long?

    @Query("UPDATE playback_progress SET revealedMs = :revealedMs WHERE bookId = :bookId")
    suspend fun updateRevealedMs(bookId: Long, revealedMs: Long)

    /** Zeroes both position and reveal together — deliberately the ONLY reset path for this
     *  table (there was none before companion packs; see PlaybackProgress.revealedMs doc). Used
     *  by the "start fresh" option on companion-pack import (§10.1) and available to any future
     *  user-facing progress reset. Does not touch playbackSpeed/boostDb/eqBandsJson (audio
     *  settings survive a progress reset) or the ebook-reading columns. */
    @Query("""
        UPDATE playback_progress
        SET currentFileId = NULL, positionMs = 0, filesBeforeCurrentMs = 0, revealedMs = 0,
            isCompleted = 0, completedDateMs = NULL
        WHERE bookId = :bookId
    """)
    suspend fun resetProgress(bookId: Long)
}
