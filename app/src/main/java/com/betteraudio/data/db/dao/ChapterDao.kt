package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.betteraudio.data.db.entities.Chapter
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    fun getChaptersForBook(bookId: Long): Flow<List<Chapter>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY orderIndex ASC")
    suspend fun getChaptersForBookOnce(bookId: Long): List<Chapter>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countForBook(bookId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<Chapter>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: Long)

    /** One-time cleanup of legacy auto-sliced "synthetic" chapters (see
     *  AudiobookRepository.purgeSyntheticChapters) — clears every chapter row for any book that
     *  carries at least one, in a single statement instead of one DELETE per affected book on
     *  every launch. */
    @Query("DELETE FROM chapters WHERE bookId IN (SELECT DISTINCT bookId FROM chapters WHERE source = 'synthetic')")
    suspend fun purgeSyntheticChapters()
}
