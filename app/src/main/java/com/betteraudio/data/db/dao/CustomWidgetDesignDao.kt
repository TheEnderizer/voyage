package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.CustomWidgetDesign
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomWidgetDesignDao {
    @Query("SELECT * FROM custom_widget_design ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CustomWidgetDesign>>

    @Query("SELECT * FROM custom_widget_design WHERE id = :id")
    suspend fun getById(id: Long): CustomWidgetDesign?

    @Query("SELECT * FROM custom_widget_design WHERE sizeBucket = :bucket ORDER BY updatedAt DESC")
    suspend fun getByBucket(bucket: String): List<CustomWidgetDesign>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(design: CustomWidgetDesign): Long

    @Query("DELETE FROM custom_widget_design WHERE id = :id")
    suspend fun deleteById(id: Long)
}
