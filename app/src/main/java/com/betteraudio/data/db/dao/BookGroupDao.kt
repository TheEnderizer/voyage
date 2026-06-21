package com.betteraudio.data.db.dao

import androidx.room.*
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.db.entities.BookGroupMember
import kotlinx.coroutines.flow.Flow

@Dao
interface BookGroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: BookGroup): Long

    @Update
    suspend fun updateGroup(group: BookGroup)

    @Delete
    suspend fun deleteGroup(group: BookGroup)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<BookGroupMember>)

    @Query("DELETE FROM book_group_members WHERE groupId = :groupId")
    suspend fun clearMembers(groupId: Long)

    @Query("SELECT * FROM book_groups ORDER BY createdAtMs DESC")
    fun getAllGroups(): Flow<List<BookGroup>>

    @Query("SELECT * FROM book_groups WHERE id = :groupId")
    suspend fun getGroupById(groupId: Long): BookGroup?

    @Query("""
        SELECT books.* FROM books
        INNER JOIN book_group_members ON books.id = book_group_members.bookId
        WHERE book_group_members.groupId = :groupId
        ORDER BY book_group_members.orderIndex ASC
    """)
    fun getBooksForGroup(groupId: Long): Flow<List<Book>>

    @Query("""
        SELECT books.* FROM books
        INNER JOIN book_group_members ON books.id = book_group_members.bookId
        WHERE book_group_members.groupId = :groupId
        ORDER BY book_group_members.orderIndex ASC
    """)
    suspend fun getBooksForGroupOnce(groupId: Long): List<Book>
}
