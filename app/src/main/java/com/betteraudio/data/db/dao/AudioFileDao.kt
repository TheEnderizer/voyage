package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.AudioFile
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    @Query("SELECT * FROM audio_files WHERE bookId = :bookId ORDER BY trackNumber ASC, fileName ASC")
    suspend fun getFilesForBookOnce(bookId: Long): List<AudioFile>

    /** Every audio file row, for the scanner's disk reconciliation — one query instead of
     *  one [getFilesForBookOnce] call per book, grouped by bookId in memory by the caller. */
    @Query("SELECT * FROM audio_files")
    suspend fun getAllFilesOnce(): List<AudioFile>

    @Query("SELECT * FROM audio_files WHERE id = :id")
    suspend fun getFileById(id: Long): AudioFile?

    @Query("SELECT * FROM audio_files WHERE filePath = :path LIMIT 1")
    suspend fun getFileByPath(path: String): AudioFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<AudioFile>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: AudioFile): Long

    @Query("DELETE FROM audio_files WHERE bookId = :bookId")
    suspend fun deleteFilesForBook(bookId: Long)

    // Repoint a file to a new on-disk location (used by the library restructure move).
    @Query("UPDATE audio_files SET filePath = :path WHERE id = :id")
    suspend fun updatePath(id: Long, path: String)

    /** Caches the result of a damage scan. Empty string = scanned and clean; see [AudioFile.damageRangesJson]. */
    @Query("UPDATE audio_files SET damageRangesJson = :ranges WHERE id = :id")
    suspend fun updateDamageRanges(id: Long, ranges: String)
}
