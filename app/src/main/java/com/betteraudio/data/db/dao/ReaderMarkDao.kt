package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.betteraudio.data.db.entities.ReaderMark
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderMarkDao {
    /** Reading order, not creation order: the list in Contents is a second table of contents, and
     *  a bookmark from last week belongs where it sits in the book, not at the top. */
    @Query("SELECT * FROM reader_marks WHERE bookId = :bookId ORDER BY spineIndex ASC, renderStart ASC")
    fun getForBook(bookId: Long): Flow<List<ReaderMark>>

    @Insert
    suspend fun insert(mark: ReaderMark): Long

    @Update
    suspend fun update(mark: ReaderMark)

    @Query("DELETE FROM reader_marks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
