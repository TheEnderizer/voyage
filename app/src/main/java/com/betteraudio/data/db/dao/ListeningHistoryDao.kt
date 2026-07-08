package com.betteraudio.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.SkipEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningHistoryDao {
    // ── Sessions ───────────────────────────────────────────────────────────
    @Insert
    suspend fun insertSession(session: ListeningSession): Long

    @Query("UPDATE listening_sessions SET endMs = :endMs, endChapterIndex = :endChapterIndex, endChapterName = :endChapterName, endPositionInChapterMs = :endPositionInChapterMs, listenedMs = :listenedMs WHERE id = :id")
    suspend fun finishSession(
        id: Long,
        endMs: Long,
        endChapterIndex: Int,
        endChapterName: String,
        endPositionInChapterMs: Long,
        listenedMs: Long
    )

    @Query("SELECT * FROM listening_sessions WHERE bookId = :bookId AND endMs > 0 ORDER BY startMs DESC")
    fun getSessionsForBook(bookId: Long): Flow<List<ListeningSession>>

    @Query("DELETE FROM listening_sessions WHERE bookId = :bookId")
    suspend fun deleteSessionsForBook(bookId: Long)

    // Re-parent a standalone ebook-only book's history onto the audiobook row it's being merged
    // into (see AudiobookRepository.mergeStandaloneEbookProgress) — otherwise it would be lost to
    // the standalone row's cascade delete instead of being "merged" as expected.
    @Query("UPDATE listening_sessions SET bookId = :toBookId WHERE bookId = :fromBookId")
    suspend fun reassignSessionsToBook(fromBookId: Long, toBookId: Long)

    // ── Confirmed skips ────────────────────────────────────────────────────
    @Insert
    suspend fun insertSkip(skip: SkipEvent): Long

    @Query("SELECT * FROM skip_events WHERE bookId = :bookId ORDER BY atMs DESC")
    fun getSkipsForBook(bookId: Long): Flow<List<SkipEvent>>

    @Query("DELETE FROM skip_events WHERE bookId = :bookId")
    suspend fun deleteSkipsForBook(bookId: Long)

    @Query("UPDATE skip_events SET bookId = :toBookId WHERE bookId = :fromBookId")
    suspend fun reassignSkipsToBook(fromBookId: Long, toBookId: Long)
}
