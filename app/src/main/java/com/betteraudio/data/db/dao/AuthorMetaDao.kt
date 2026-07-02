package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betteraudio.data.db.entities.AuthorMeta
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthorMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(author: AuthorMeta)

    @Query("SELECT * FROM author_meta WHERE name = :name")
    suspend fun getByName(name: String): AuthorMeta?

    @Query("SELECT * FROM author_meta")
    fun getAll(): Flow<List<AuthorMeta>>

    @Query("SELECT * FROM author_meta")
    suspend fun getAllOnce(): List<AuthorMeta>

    @Query("UPDATE author_meta SET coverFxPath = :path WHERE name = :name")
    suspend fun updateCoverFx(name: String, path: String?)
}
