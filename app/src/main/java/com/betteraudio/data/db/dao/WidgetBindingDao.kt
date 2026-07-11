package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.WidgetBinding

@Dao
interface WidgetBindingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: WidgetBinding)

    @Query("SELECT designId FROM widget_binding WHERE appWidgetId = :appWidgetId")
    suspend fun getDesignId(appWidgetId: Int): Long?

    @Query("DELETE FROM widget_binding WHERE appWidgetId IN (:appWidgetIds)")
    suspend fun deleteByIds(appWidgetIds: List<Int>)
}
