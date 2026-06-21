package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betteraudio.data.db.entities.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY absolutePositionMs ASC")
    fun getForBook(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
