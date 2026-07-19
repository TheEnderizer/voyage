package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.WidgetBinding
import kotlinx.coroutines.flow.Flow

@Dao
interface WidgetBindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: WidgetBinding)

    @Query("SELECT * FROM widget_bindings")
    fun observeAllBindings(): Flow<List<WidgetBinding>>

    @Query("SELECT designId FROM widget_bindings WHERE appWidgetId = :appWidgetId")
    suspend fun getDesignId(appWidgetId: Int): Long?

    @Query("SELECT * FROM widget_bindings WHERE designId = :designId")
    suspend fun bindingsForDesign(designId: Long): List<WidgetBinding>

    @Query("SELECT * FROM widget_bindings")
    suspend fun allBindings(): List<WidgetBinding>

    @Query("DELETE FROM widget_bindings WHERE appWidgetId IN (:appWidgetIds)")
    suspend fun deleteByIds(appWidgetIds: List<Int>)

    @Query("DELETE FROM widget_bindings WHERE designId = :designId")
    suspend fun deleteByDesignId(designId: Long)
}
