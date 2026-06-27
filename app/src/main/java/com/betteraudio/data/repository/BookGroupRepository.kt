package com.betteraudio.data.repository

import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookGroupDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.db.entities.BookGroupMember
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookGroupRepository @Inject constructor(
    private val bookGroupDao: BookGroupDao,
    private val bookDao: BookDao,
    private val audioFileDao: AudioFileDao
) {
    fun getAllGroups(): Flow<List<BookGroup>> = bookGroupDao.getAllGroups()

    fun getBooksForGroup(groupId: Long): Flow<List<Book>> = bookGroupDao.getBooksForGroup(groupId)

    suspend fun getGroupById(groupId: Long): BookGroup? = bookGroupDao.getGroupById(groupId)

    suspend fun getBooksForGroupOnce(groupId: Long): List<Book> = bookGroupDao.getBooksForGroupOnce(groupId)

    suspend fun getAudioFilesForBooks(bookIds: List<Long>): Map<Long, List<AudioFile>> {
        return bookIds.associateWith { bookId ->
            audioFileDao.getFilesForBookOnce(bookId)
                .sortedWith(compareBy({ it.trackNumber }, { it.fileName }))
        }
    }

    /**
     * [manual] = the user explicitly created this group (vs. the scanner's AutoJoiner). Manual
     * groups lock their members' [Book.manualGrouping] so a later refresh never disturbs them.
     */
    suspend fun createGroup(
        name: String,
        coverArtPath: String?,
        playbackSpeed: Float,
        orderedBookIds: List<Long>,
        manual: Boolean = false
    ): Long {
        val group = BookGroup(name = name, coverArtPath = coverArtPath, playbackSpeed = playbackSpeed)
        val groupId = bookGroupDao.insertGroup(group)
        val members = orderedBookIds.mapIndexed { index, bookId ->
            BookGroupMember(groupId, bookId, index)
        }
        bookGroupDao.insertMembers(members)
        orderedBookIds.forEach { bookId -> bookDao.setGroupId(bookId, groupId) }
        if (manual) bookDao.markManualGrouping(orderedBookIds)
        return groupId
    }

    suspend fun updateGroup(
        group: BookGroup,
        orderedBookIds: List<Long>
    ) {
        bookGroupDao.updateGroup(group)
        bookGroupDao.clearMembers(group.id)
        val members = orderedBookIds.mapIndexed { index, bookId ->
            BookGroupMember(group.id, bookId, index)
        }
        bookGroupDao.insertMembers(members)
        bookDao.markManualGrouping(orderedBookIds) // editing a group is a manual decision
    }

    suspend fun dissolveGroup(groupId: Long) {
        val books = bookGroupDao.getBooksForGroupOnce(groupId)
        books.forEach { bookDao.setGroupId(it.id, null) }
        // Splitting is a manual decision: keep these books ungrouped through future refreshes.
        bookDao.markManualGrouping(books.map { it.id })
        val group = bookGroupDao.getGroupById(groupId) ?: return
        bookGroupDao.deleteGroup(group)
    }
}
