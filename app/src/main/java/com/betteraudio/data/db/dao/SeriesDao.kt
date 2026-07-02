package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.betteraudio.data.db.entities.Series
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Insert
    suspend fun insert(series: Series): Long

    @Update
    suspend fun update(series: Series)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM series WHERE id = :id")
    fun getById(id: Long): Flow<Series?>

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getByIdOnce(id: Long): Series?

    @Query("SELECT * FROM series WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Series?

    @Query("SELECT * FROM series ORDER BY name COLLATE NOCASE ASC")
    fun getAll(): Flow<List<Series>>

    @Query("SELECT * FROM series")
    suspend fun getAllOnce(): List<Series>

    @Query("UPDATE series SET coverArtPath = :path, coverFxPath = NULL WHERE id = :id")
    suspend fun updateCover(id: Long, path: String?)

    @Query("UPDATE series SET coverFxPath = :path WHERE id = :id")
    suspend fun updateCoverFx(id: Long, path: String?)

    // A series with no remaining member books (cleanup after removals).
    @Query("DELETE FROM series WHERE id NOT IN (SELECT DISTINCT seriesId FROM books WHERE seriesId IS NOT NULL)")
    suspend fun deleteEmpty()
}
