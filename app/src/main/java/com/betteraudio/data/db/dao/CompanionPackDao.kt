package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betteraudio.data.db.entities.CompanionPack
import kotlinx.coroutines.flow.Flow

@Dao
interface CompanionPackDao {

    @Query("SELECT * FROM companion_packs WHERE targetKey = :targetKey ORDER BY title ASC")
    fun observeForTarget(targetKey: String): Flow<List<CompanionPack>>

    @Query("SELECT * FROM companion_packs WHERE targetKey = :targetKey ORDER BY title ASC")
    suspend fun getForTargetOnce(targetKey: String): List<CompanionPack>

    @Query("SELECT * FROM companion_packs WHERE packId = :packId")
    suspend fun getById(packId: String): CompanionPack?

    @Query("SELECT * FROM companion_packs ORDER BY title ASC")
    fun observeAll(): Flow<List<CompanionPack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(pack: CompanionPack)

    @Query("DELETE FROM companion_packs WHERE packId = :packId")
    suspend fun deleteById(packId: String)

    @Query("UPDATE companion_packs SET enabled = :enabled WHERE packId = :packId")
    suspend fun setEnabled(packId: String, enabled: Boolean)

    @Query("UPDATE companion_packs SET lastSeenRevealMs = :ms WHERE packId = :packId")
    suspend fun updateLastSeenRevealMs(packId: String, ms: Long)
}
