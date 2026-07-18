package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.WidgetDesign
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetDesignDao {
    @Query("SELECT * FROM widget_designs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WidgetDesign>>

    @Query("SELECT * FROM widget_designs WHERE id = :id")
    suspend fun getById(id: Long): WidgetDesign?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(design: WidgetDesign): Long

    @Query("DELETE FROM widget_designs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
