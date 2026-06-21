package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.PlaybackProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    fun getProgressForBook(bookId: Long): Flow<PlaybackProgress?>

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getProgressForBookOnce(bookId: Long): PlaybackProgress?

    @Query("SELECT * FROM playback_progress ORDER BY lastPlayedMs DESC LIMIT 1")
    fun getMostRecentProgress(): Flow<PlaybackProgress?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PlaybackProgress)

    @Query("""
        UPDATE playback_progress
        SET currentFileId = :fileId, positionMs = :positionMs, lastPlayedMs = :lastPlayedMs
        WHERE bookId = :bookId
    """)
    suspend fun updatePosition(bookId: Long, fileId: Long, positionMs: Long, lastPlayedMs: Long)

    @Query("UPDATE playback_progress SET playbackSpeed = :speed WHERE bookId = :bookId")
    suspend fun updateSpeed(bookId: Long, speed: Float)

    @Query("""
        UPDATE playback_progress
        SET isCompleted = 1, completedDateMs = :completedMs
        WHERE bookId = :bookId
    """)
    suspend fun markCompleted(bookId: Long, completedMs: Long)
}
