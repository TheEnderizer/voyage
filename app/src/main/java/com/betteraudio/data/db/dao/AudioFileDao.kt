package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.AudioFile
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {

    @Query("SELECT * FROM audio_files WHERE bookId = :bookId ORDER BY trackNumber ASC, fileName ASC")
    fun getFilesForBook(bookId: Long): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files WHERE bookId = :bookId ORDER BY trackNumber ASC, fileName ASC")
    suspend fun getFilesForBookOnce(bookId: Long): List<AudioFile>

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
}
