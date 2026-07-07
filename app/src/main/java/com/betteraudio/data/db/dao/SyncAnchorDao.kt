package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betteraudio.data.db.entities.SyncAnchor
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncAnchorDao {
    @Insert
    suspend fun insertAll(anchors: List<SyncAnchor>)

    @Query("SELECT * FROM sync_anchors WHERE bookId = :bookId ORDER BY audioMs ASC")
    suspend fun getForBookOnce(bookId: Long): List<SyncAnchor>

    @Query("SELECT COUNT(*) FROM sync_anchors WHERE bookId = :bookId")
    fun countForBook(bookId: Long): Flow<Int>

    @Query("DELETE FROM sync_anchors WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)
}
