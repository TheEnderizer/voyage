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

    /** Looked up by the seeded default designs' stable names (see DefaultWidgetDesigns) so the
     *  seeder is idempotent and the fixed-design providers can find "their" design to auto-bind. */
    @Query("SELECT * FROM widget_designs WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): WidgetDesign?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(design: WidgetDesign): Long

    @Query("DELETE FROM widget_designs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
