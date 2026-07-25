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
import com.betteraudio.data.db.dao.BookmarkDao
import com.betteraudio.data.db.dao.ChapterDao
import com.betteraudio.data.db.dao.WidgetDesignDao
import com.betteraudio.data.db.dao.ListeningHistoryDao
import com.betteraudio.data.db.dao.PlaybackProgressDao
import com.betteraudio.data.db.dao.SeriesDao
import com.betteraudio.data.db.dao.WidgetBindingDao
import com.betteraudio.data.db.entities.AudioFile
import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.AuthorMeta
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.WidgetDesign
import com.betteraudio.data.db.entities.WidgetBinding
import com.betteraudio.util.AppLog
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.db.entities.SyncAnchor
import com.betteraudio.data.db.dao.SyncAnchorDao

// Version 9: manualGrouping on books (user-locked join/split, ignored by AutoJoiner)
// Version 10: Book.skipSilenceEnabled + listening_sessions / skip_events history tables
// Version 11: listening_sessions.endBookPositionMs (resume-from-session)
// Version 12: first-class series — `series` + `author_meta` tables, Book.seriesId; series
//             seeded from existing seriesName; stale groupId nulled out (grouping retired).
// Version 13: EPUB reader — Book.ebookPath/ebookSpineCount/chapterMapJson;
//             PlaybackProgress text position (textSpineIndex/textFraction/textOverallFraction/lastMode).
// Version 14: sync_anchors table — verified paragraph-resolution listen↔read alignment points.
// Version 15: skip_events gains `kind` + text-side columns, so reading-side jumps (reader chapter/
//             TOC navigation, "Listen from here" / "Read from here") share the same merged,
//             time-ordered history table as audio-side skips for a linked audio+epub book.
// Version 16: custom_widget_design + widget_binding tables for the custom widget maker.
// Version 17: skip_events.source ("jump" | "skip_button" | "auto") — distinguishes confirmed
//             jumps from coalesced skip-button taps and periodic auto-checkpoints, so the
//             position-history UI can show/prune each differently.
// Version 18: drops the retired book-group/"join" feature entirely — book_groups/book_group_members
//             tables and Book.groupId/manualGrouping columns (grouping had already been superseded
//             by first-class Series since v12; the UI/nav path to it was fully unreachable).
// Version 19: widget maker v2 rewrite — drops the old custom_widget_design/widget_binding tables
//             (6x6-grid, bucketed-size design model) entirely in favor of widget_designs
//             (free aspect ratio + a JSON design-unit document) and widget_bindings. No migration
//             of old designs: this is a deliberate clean break (see widget/model/WidgetDesignDoc.kt).
//
// Phantom "whole library" series rows created by a scanner bug (the AUTO scan mode could treat
// the library root itself as a series container — fixed in AudioFileScanner.scanFolder's depth
// guard) are cleaned up as a one-shot app-startup job (VoyageApp.cleanupPhantomSeries), not a
// Room migration: identifying a phantom row needs the configured library-root folder name
// (SettingsStore), which a migration has no access to.
//
// Version 20: indexes on the four hot equality-lookup columns (books.folderPath,
//             books.ebookPath, audio_files.filePath, series.name) — none existed before, so
//             getBookByFolder (called once per candidate book on every scan) was a full table
//             scan. Also adds playback_progress.filesBeforeCurrentMs (backfilled via a
//             correlated sum over audio_files, same trackNumber/fileName order
//             BookWithProgress.audioFiles uses) so the home grid (HomeGridBook) can compute
//             progress without ever loading a book's file list.
@Database(
    entities = [Book::class, AudioFile::class, PlaybackProgress::class, Chapter::class, Bookmark::class, AudioPreset::class, ListeningSession::class, SkipEvent::class, Series::class, AuthorMeta::class, SyncAnchor::class, WidgetDesign::class, WidgetBinding::class],
    version = 20,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun audioFileDao(): AudioFileDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun chapterDao(): ChapterDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun audioPresetDao(): AudioPresetDao
    abstract fun listeningHistoryDao(): ListeningHistoryDao
    abstract fun seriesDao(): SeriesDao
    abstract fun authorMetaDao(): AuthorMetaDao
    abstract fun syncAnchorDao(): SyncAnchorDao
    abstract fun widgetDesignDao(): WidgetDesignDao
    abstract fun widgetBindingDao(): WidgetBindingDao

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

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 12 → 13 (epub reader)")
                db.execSQL("ALTER TABLE books ADD COLUMN ebookPath TEXT")
                db.execSQL("ALTER TABLE books ADD COLUMN ebookSpineCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN chapterMapJson TEXT")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN textSpineIndex INTEGER")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN textFraction REAL")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN textOverallFraction REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN lastMode TEXT NOT NULL DEFAULT 'AUDIO'")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 13 → 14 (sync anchors)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `sync_anchors` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `audioMs` INTEGER NOT NULL,
                        `spineIndex` INTEGER NOT NULL,
                        `paragraphIndex` INTEGER NOT NULL,
                        `charOffset` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `createdAtMs` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_anchors_bookId` ON `sync_anchors` (`bookId`)")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 14 → 15 (skip_events text-side jumps)")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN kind TEXT NOT NULL DEFAULT 'AUDIO'")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN fromSpineIndex INTEGER")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN fromFraction REAL")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN toSpineIndex INTEGER")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN toFraction REAL")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN toSpineTitle TEXT")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 15 → 16 (custom widget maker)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `custom_widget_design` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `sizeBucket` TEXT NOT NULL,
                        `backgroundType` TEXT NOT NULL,
                        `backgroundValue` TEXT NOT NULL DEFAULT '',
                        `elementsJson` TEXT NOT NULL DEFAULT '[]',
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `widget_binding` (
                        `appWidgetId` INTEGER PRIMARY KEY NOT NULL,
                        `designId` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 16 → 17 (skip_events.source)")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN source TEXT NOT NULL DEFAULT 'jump'")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 17 → 18 (drop retired book-group feature)")
                db.execSQL("DROP TABLE IF EXISTS book_group_members")
                db.execSQL("DROP TABLE IF EXISTS book_groups")
                // Full rebuild (not ALTER TABLE ... DROP COLUMN) so this works on every SQLite
                // version this app's minSdk can ship with, not just 3.35+.
                db.execSQL("""
                    CREATE TABLE books_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        author TEXT NOT NULL,
                        seriesId INTEGER,
                        seriesName TEXT,
                        seriesOrder REAL,
                        folderPath TEXT NOT NULL,
                        coverArtPath TEXT,
                        coverFxPath TEXT,
                        totalDurationMs INTEGER NOT NULL,
                        addedDateMs INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        fileCount INTEGER NOT NULL,
                        synopsis TEXT,
                        narrator TEXT,
                        genre TEXT,
                        year INTEGER,
                        album TEXT,
                        description TEXT,
                        titleOverride TEXT,
                        authorOverride TEXT,
                        isIgnored INTEGER NOT NULL,
                        skipSilenceEnabled INTEGER NOT NULL,
                        ebookPath TEXT,
                        ebookSpineCount INTEGER NOT NULL,
                        chapterMapJson TEXT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO books_new (
                        id, title, author, seriesId, seriesName, seriesOrder, folderPath, coverArtPath,
                        coverFxPath, totalDurationMs, addedDateMs, status, fileCount, synopsis, narrator,
                        genre, year, album, description, titleOverride, authorOverride, isIgnored,
                        skipSilenceEnabled, ebookPath, ebookSpineCount, chapterMapJson
                    )
                    SELECT
                        id, title, author, seriesId, seriesName, seriesOrder, folderPath, coverArtPath,
                        coverFxPath, totalDurationMs, addedDateMs, status, fileCount, synopsis, narrator,
                        genre, year, album, description, titleOverride, authorOverride, isIgnored,
                        skipSilenceEnabled, ebookPath, ebookSpineCount, chapterMapJson
                    FROM books
                """.trimIndent())
                db.execSQL("DROP TABLE books")
                db.execSQL("ALTER TABLE books_new RENAME TO books")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_books_seriesId ON books(seriesId)")
            }
        }

        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 18 → 19 (widget maker v2 — clean break, no data migration)")
                db.execSQL("DROP TABLE IF EXISTS custom_widget_design")
                db.execSQL("DROP TABLE IF EXISTS widget_binding")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `widget_designs` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `aspectRatio` REAL NOT NULL DEFAULT 2.0,
                        `documentJson` TEXT NOT NULL DEFAULT '{}',
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `widget_bindings` (
                        `appWidgetId` INTEGER PRIMARY KEY NOT NULL,
                        `designId` INTEGER NOT NULL,
                        `boundAt` INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i("DB", "migrating 19 → 20 (hot-column indexes + filesBeforeCurrentMs)")

                // Room compares the declared @Entity indices against the schema by name, so these
                // must match Room's own naming convention (index_<table>_<column>) exactly or
                // validation fails on launch.
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_folderPath` ON `books` (`folderPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_ebookPath` ON `books` (`ebookPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_files_filePath` ON `audio_files` (`filePath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_name` ON `series` (`name`)")

                db.execSQL("ALTER TABLE playback_progress ADD COLUMN filesBeforeCurrentMs INTEGER NOT NULL DEFAULT 0")
                // Backfill for every row already paused mid-book: without this, BookWithProgress's
                // own live computation (audioFiles.takeWhile{it.id!=currentFileId}.sumOf{durationMs})
                // and HomeGridBook's new denormalized column would disagree the moment this ships,
                // and every such book's grid progress bar would collapse toward zero until its next
                // file transition quietly recomputed it.
                //
                // Done in Kotlin, not a correlated SQL UPDATE: ordered by id (insertion order),
                // NOT trackNumber/fileName — BookWithProgress's @Relation audioFiles has no
                // explicit ORDER BY, so in practice it returns natural (rowid) order, and
                // importBook always inserts audio_files rows in the already-resolved playback
                // sequence — so id order matches what progressFraction actually computes today.
                // trackNumber/fileName would silently disagree for a disc-merged book: each disc
                // restarts its own track numbering (both disc 1 and disc 2 can contain
                // "01 - track.mp3"/trackNumber=1), which groups by within-disc track position
                // across discs rather than preserving disc-then-disc order.
                db.query("SELECT bookId, currentFileId FROM playback_progress WHERE currentFileId IS NOT NULL").use { rows ->
                    val bookIdIdx = rows.getColumnIndexOrThrow("bookId")
                    val fileIdIdx = rows.getColumnIndexOrThrow("currentFileId")
                    val targets = mutableListOf<Pair<Long, Long>>()
                    while (rows.moveToNext()) {
                        targets.add(rows.getLong(bookIdIdx) to rows.getLong(fileIdIdx))
                    }
                    targets.forEach { (bookId, currentFileId) ->
                        var filesBeforeCurrentMs = 0L
                        db.query(
                            "SELECT id, durationMs FROM audio_files WHERE bookId = ? ORDER BY id ASC",
                            arrayOf(bookId)
                        ).use { files ->
                            val idIdx = files.getColumnIndexOrThrow("id")
                            val durIdx = files.getColumnIndexOrThrow("durationMs")
                            while (files.moveToNext()) {
                                if (files.getLong(idIdx) == currentFileId) return@use
                                filesBeforeCurrentMs += files.getLong(durIdx)
                            }
                        }
                        db.execSQL(
                            "UPDATE playback_progress SET filesBeforeCurrentMs = ? WHERE bookId = ?",
                            arrayOf(filesBeforeCurrentMs, bookId)
                        )
                    }
                }
            }
        }
    }
}
