package com.betteraudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookGroupDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.db.entities.BookGroupMember
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.PlaybackProgress

// Version 4: added Bookmark entity (bookmarks with comments + return-from-jump)
@Database(
    entities = [Book::class, AudioFile::class, PlaybackProgress::class, BookGroup::class, BookGroupMember::class, Chapter::class, Bookmark::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun audioFileDao(): AudioFileDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun bookGroupDao(): BookGroupDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
}
