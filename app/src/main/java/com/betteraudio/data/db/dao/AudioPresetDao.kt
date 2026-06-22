package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.AudioPreset
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioPresetDao {
    @Query("SELECT * FROM audio_presets ORDER BY id ASC")
    fun getAll(): Flow<List<AudioPreset>>

    @Query("SELECT * FROM audio_presets WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): AudioPreset?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: AudioPreset): Long

    @Update
    suspend fun update(preset: AudioPreset)

    @Query("DELETE FROM audio_presets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE audio_presets SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE audio_presets SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)
}
