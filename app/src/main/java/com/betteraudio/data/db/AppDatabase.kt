package com.betteraudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.AudioPresetDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookGroupDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.db.entities.BookGroupMember
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.PlaybackProgress

// Version 8: coverFxPath on books (pre-baked reflection + progressive-blur composite)
@Database(
    entities = [Book::class, AudioFile::class, PlaybackProgress::class, BookGroup::class, BookGroupMember::class, Chapter::class, Bookmark::class, AudioPreset::class],
    version = 8,
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
    abstract fun audioPresetDao(): AudioPresetDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `bookmarks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `fileId` INTEGER NOT NULL,
                        `positionInFileMs` INTEGER NOT NULL,
                        `absolutePositionMs` INTEGER NOT NULL,
                        `comment` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `Book`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmarks_bookId` ON `bookmarks` (`bookId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN boostDb INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `audio_presets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `speedMult` REAL NOT NULL DEFAULT 1.0,
                        `boostDb` INTEGER NOT NULL DEFAULT 0,
                        `eqBandsJson` TEXT,
                        `isDefault` INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN eqBandsJson TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN titleOverride TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN authorOverride TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN isIgnored INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE audio_presets ADD COLUMN type TEXT NOT NULL DEFAULT 'SPEED'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN lastPausedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN coverFxPath TEXT")
            }
        }
    }
}
