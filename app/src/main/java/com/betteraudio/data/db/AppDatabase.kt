package com.betteraudio.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.betteraudio.data.db.dao.AudioFileDao
import com.betteraudio.data.db.dao.AudioPresetDao
import com.betteraudio.data.db.dao.AuthorMetaDao
import com.betteraudio.data.db.dao.BookDao
import com.betteraudio.data.db.dao.BookGroupDao
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.ListeningHistoryDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.AuthorMeta
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.BookGroup
import com.betteraudio.data.db.entities.BookGroupMember
import com.betteraudio.util.AppLog
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.db.entities.SkipEvent

// Version 9: manualGrouping on books (user-locked join/split, ignored by AutoJoiner)
// Version 10: Book.skipSilenceEnabled + listening_sessions / skip_events history tables
// Version 11: listening_sessions.endBookPositionMs (resume-from-session)
// Version 12: first-class series — `series` + `author_meta` tables, Book.seriesId; series
//             seeded from existing seriesName; stale groupId nulled out (grouping retired).
@Database(
    entities = [Book::class, AudioFile::class, PlaybackProgress::class, BookGroup::class, BookGroupMember::class, Chapter::class, Bookmark::class, AudioPreset::class, ListeningSession::class, SkipEvent::class, Series::class, AuthorMeta::class],
    version = 12,
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
    abstract fun listeningHistoryDao(): ListeningHistoryDao
    abstract fun seriesDao(): SeriesDao
    abstract fun authorMetaDao(): AuthorMetaDao

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

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN manualGrouping INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN skipSilenceEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `listening_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `startMs` INTEGER NOT NULL,
                        `endMs` INTEGER NOT NULL DEFAULT 0,
                        `startChapterIndex` INTEGER NOT NULL DEFAULT -1,
                        `startChapterName` TEXT NOT NULL DEFAULT '',
                        `endChapterIndex` INTEGER NOT NULL DEFAULT -1,
                        `endChapterName` TEXT NOT NULL DEFAULT '',
                        `startPositionInChapterMs` INTEGER NOT NULL DEFAULT 0,
                        `endPositionInChapterMs` INTEGER NOT NULL DEFAULT 0,
                        `listenedMs` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_listening_sessions_bookId` ON `listening_sessions` (`bookId`)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `skip_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `atMs` INTEGER NOT NULL,
                        `fromPositionMs` INTEGER NOT NULL,
                        `toPositionMs` INTEGER NOT NULL,
                        `chapterIndex` INTEGER NOT NULL DEFAULT -1,
                        `chapterName` TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_skip_events_bookId` ON `skip_events` (`bookId`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 10 → 11 (endBookPositionMs)")
                db.execSQL("ALTER TABLE listening_sessions ADD COLUMN endBookPositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 11 → 12 (first-class series)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `series` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `author` TEXT,
                        `narrator` TEXT,
                        `coverArtPath` TEXT,
                        `coverFxPath` TEXT,
                        `description` TEXT,
                        `playbackSpeed` REAL,
                        `boostDb` INTEGER,
                        `eqBandsJson` TEXT,
                        `skipSilenceEnabled` INTEGER,
                        `createdAtMs` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `author_meta` (
                        `name` TEXT NOT NULL,
                        `coverArtPath` TEXT,
                        `coverFxPath` TEXT,
                        PRIMARY KEY(`name`)
                    )
                """.trimIndent())
                db.execSQL("ALTER TABLE books ADD COLUMN seriesId INTEGER")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_seriesId` ON `books` (`seriesId`)")
                // Seed a series row per distinct seriesName; author = the shared author when every
                // book in that series has the same non-blank author, else null.
                db.execSQL("""
                    INSERT INTO `series` (`name`, `author`, `createdAtMs`)
                    SELECT `seriesName`,
                           CASE WHEN MIN(`author`) = MAX(`author`) THEN NULLIF(MIN(`author`), '') ELSE NULL END,
                           ${System.currentTimeMillis()}
                    FROM `books`
                    WHERE `seriesName` IS NOT NULL AND `seriesName` != ''
                    GROUP BY `seriesName`
                """.trimIndent())
                db.execSQL("""
                    UPDATE `books` SET `seriesId` =
                        (SELECT `s`.`id` FROM `series` `s` WHERE `s`.`name` = `books`.`seriesName`)
                    WHERE `seriesName` IS NOT NULL AND `seriesName` != ''
                """.trimIndent())
                // Grouping is retired: detach every book from any old join-group so the library
                // queries (which filter groupId IS NULL) show them all. The now-empty group tables
                // are dropped in a later cleanup migration once their Room entities are removed.
                db.execSQL("UPDATE `books` SET `groupId` = NULL")
            }
        }
    }
}
