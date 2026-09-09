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
import com.betteraudio.data.db.dao.CompanionPackDao
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
import com.betteraudio.util.log.LogCat
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.Chapter
import com.betteraudio.data.db.entities.CompanionPack
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.db.entities.SkipEvent

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
// Version 22: books.dataAppliedAtMs — the storage redesign's disk-mirror "applied at" marker.
//             The obvious gate (disk doc's mtime vs. the book's own lastPlayedMs) doesn't
//             actually gate anything, since the disk mirror is written AFTER every DB write —
//             see AudioFileScanner.importBook and BookDataStore.
// Version 23: playback_progress.textCharOffset — the native reader's render-stream position
//             (docs/reader-features-and-plan.md Phase 1 item 11). Not back-filled from existing
//             textSpineIndex/textFraction rows: the epub reading position has no preserved value
//             across this migration (by design — see PlaybackProgress.textCharOffset); audio
//             columns on the same row are untouched, since ALTER TABLE ADD COLUMN only adds.
// Version 25: reconciles a real-world version collision, not a new column of its own. An
//             unrelated in-progress branch (a "companion" pack-import feature, never merged)
//             had independently bumped the DB to its OWN version 24 — playback_progress gained
//             its `revealedMs` column and a `companion_packs` table — and a debug build of that
//             branch had been installed on the dev phone. Shipping this reader work at version
//             23 (built with no knowledge of that branch) was therefore a *downgrade* on that
//             phone: Room has no downgrade path, so it refused to open the database at all
//             (caught before any write — see MIGRATION_24_25, which starts from that exact
//             on-disk schema). 25 is picked to sit safely above both branches' version numbers.
// Version 26: the companion pack feature (docs/companion-packs.md) — companion_packs, plus
//             playback_progress.revealedMs (the reveal cursor pack fact visibility is gated on,
//             in book-global ms — NOT the same coordinate space as positionMs, which is
//             file-relative) and audio_files.fileKey (stable per-file identity for pack
//             export/import). This work was developed on a branch that had independently bumped
//             the DB to its OWN 23 and 24 with exactly these columns, which is the collision
//             version 25 exists to clean up. It is re-landed HERE, above 25, rather than by
//             restoring those two migrations: 23 and 24 already mean something else on every
//             device that has run a shipped build, and a migration's meaning cannot be changed
//             retroactively. MIGRATION_25_26 therefore re-adds what MIGRATION_24_25 removed.
// Version 27: reader_marks — bookmarks and highlights inside an EPUB. Purely additive: one new
//             table, nothing existing touched, so there is no data to preserve or reshape.
@Database(
    entities = [Book::class, AudioFile::class, PlaybackProgress::class, Chapter::class, Bookmark::class, AudioPreset::class, ListeningSession::class, SkipEvent::class, Series::class, AuthorMeta::class, WidgetDesign::class, WidgetBinding::class, CompanionPack::class],
    version = 29,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun audioFileDao(): AudioFileDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun chapterDao(): ChapterDao
    abstract fun companionPackDao(): CompanionPackDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun audioPresetDao(): AudioPresetDao
    abstract fun listeningHistoryDao(): ListeningHistoryDao
    abstract fun seriesDao(): SeriesDao
    abstract fun authorMetaDao(): AuthorMetaDao
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
                AppLog.i(LogCat.DB, "migrating 10 → 11 (endBookPositionMs)")
                db.execSQL("ALTER TABLE listening_sessions ADD COLUMN endBookPositionMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 11 → 12 (first-class series)")
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
                AppLog.i(LogCat.DB, "migrating 12 → 13 (epub reader)")
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
                AppLog.i(LogCat.DB, "migrating 13 → 14 (sync anchors)")
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
                AppLog.i(LogCat.DB, "migrating 14 → 15 (skip_events text-side jumps)")
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
                AppLog.i(LogCat.DB, "migrating 15 → 16 (custom widget maker)")
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
                AppLog.i(LogCat.DB, "migrating 16 → 17 (skip_events.source)")
                db.execSQL("ALTER TABLE skip_events ADD COLUMN source TEXT NOT NULL DEFAULT 'jump'")
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 17 → 18 (drop retired book-group feature)")
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
                AppLog.i(LogCat.DB, "migrating 18 → 19 (widget maker v2 — clean break, no data migration)")
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
                AppLog.i(LogCat.DB, "migrating 19 → 20 (hot-column indexes + filesBeforeCurrentMs)")

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

        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 20 → 21 (audio_files.damageRangesJson)")
                // Nullable with no default: NULL distinguishes "never scanned" from "scanned and
                // clean" (empty string), which is what stops a healthy file being rescanned on
                // every failure and a damaged one being rescanned on every play.
                db.execSQL("ALTER TABLE audio_files ADD COLUMN damageRangesJson TEXT")
            }
        }

        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 21 → 22 (books.dataAppliedAtMs)")
                db.execSQL("ALTER TABLE books ADD COLUMN dataAppliedAtMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 22 → 23 (playback_progress.textCharOffset)")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN textCharOffset INTEGER")
            }
        }

        // A device that already reached exactly this app's own version 23 (vanishingly unlikely —
        // no build before this one ever shipped it) already has the correct schema; nothing to do
        // beyond the version bump, which Room records once migrate() returns.
        val MIGRATION_23_25 = object : Migration(23, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 23 → 25 (schema unchanged; see version 25 doc comment)")
            }
        }

        // See the version-25 doc comment above @Database: reconciles the unrelated "companion"
        // branch's own version 24 with this app's version 23 shape. Verified against that
        // branch's actual exported schema (app/schemas/.../24.json, stashed — never merged),
        // not assumed: TWO tables differ, not one — playback_progress (its own `revealedMs` vs.
        // this app's `textCharOffset`) and audio_files (an extra `fileKey` column this app has
        // no use for). Every other table is identical. SQLite's ALTER TABLE can't drop a column
        // on every Android version this app supports (DROP COLUMN needs SQLite 3.35+, not
        // guaranteed at minSdk 26), so both are rebuilt via the standard SQLite recipe: create
        // the correct shape under a temp name, copy every row with an explicit column list
        // (never `SELECT *`, so a stray extra column is silently left behind rather than copied
        // into a table with no place for it), drop the old table, rename, recreate the indices
        // (dropped along with the old table). The companion branch's own extra table
        // (companion_packs) is left completely alone — Room only validates tables it knows about
        // via @Database's entity list, so an unrelated extra table is harmless, and this app has
        // no reason to touch data that belongs to a feature it doesn't implement.
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 24 → 25 (reconciling a companion-branch schema collision, see version 25 doc comment)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `audio_files_v25` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL, `fileName` TEXT NOT NULL, `trackNumber` INTEGER NOT NULL,
                        `title` TEXT, `durationMs` INTEGER NOT NULL, `fileSizeBytes` INTEGER NOT NULL,
                        `chapterTitle` TEXT, `damageRangesJson` TEXT,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `audio_files_v25`
                        (id, bookId, filePath, fileName, trackNumber, title, durationMs, fileSizeBytes,
                         chapterTitle, damageRangesJson)
                    SELECT
                        id, bookId, filePath, fileName, trackNumber, title, durationMs, fileSizeBytes,
                        chapterTitle, damageRangesJson
                    FROM `audio_files`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `audio_files`")
                db.execSQL("ALTER TABLE `audio_files_v25` RENAME TO `audio_files`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_files_bookId` ON `audio_files` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_audio_files_filePath` ON `audio_files` (`filePath`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_progress_v25` (
                        `bookId` INTEGER NOT NULL, `currentFileId` INTEGER, `positionMs` INTEGER NOT NULL,
                        `lastPlayedMs` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, `boostDb` INTEGER NOT NULL,
                        `eqBandsJson` TEXT, `isCompleted` INTEGER NOT NULL, `completedDateMs` INTEGER,
                        `lastPausedAt` INTEGER NOT NULL, `textSpineIndex` INTEGER, `textFraction` REAL,
                        `textCharOffset` INTEGER, `textOverallFraction` REAL NOT NULL, `lastMode` TEXT NOT NULL,
                        `filesBeforeCurrentMs` INTEGER NOT NULL, PRIMARY KEY(`bookId`),
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`currentFileId`) REFERENCES `audio_files`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playback_progress_v25`
                        (bookId, currentFileId, positionMs, lastPlayedMs, playbackSpeed, boostDb, eqBandsJson,
                         isCompleted, completedDateMs, lastPausedAt, textSpineIndex, textFraction, textCharOffset,
                         textOverallFraction, lastMode, filesBeforeCurrentMs)
                    SELECT
                        bookId, currentFileId, positionMs, lastPlayedMs, playbackSpeed, boostDb, eqBandsJson,
                        isCompleted, completedDateMs, lastPausedAt, textSpineIndex, textFraction, NULL,
                        textOverallFraction, lastMode, filesBeforeCurrentMs
                    FROM `playback_progress`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `playback_progress`")
                db.execSQL("ALTER TABLE `playback_progress_v25` RENAME TO `playback_progress`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_progress_bookId` ON `playback_progress` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_progress_currentFileId` ON `playback_progress` (`currentFileId`)")
            }
        }

        /**
         * Re-lands the companion pack schema on top of version 25 — see the version-26 doc
         * comment above @Database for why it is a new version rather than a restored 23/24.
         *
         * Every column add is guarded by [hasColumn] rather than issued blind. That is not
         * defensiveness for its own sake: this app has three genuinely different populations of
         * database on real devices, because two branches once both called themselves version 23.
         * A device that ran the unmerged companion build already has `companion_packs` and may
         * already have `revealedMs`/`fileKey` (or may have had them stripped again by
         * MIGRATION_24_25, depending on which build it saw last), while a device that only ever
         * ran shipped builds has none of them. An unguarded `ALTER TABLE ADD COLUMN` throws
         * "duplicate column name" on the first of those and takes the app down on launch.
         */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 25 → 26 (companion packs)")

                // Created with isOwn already present, unlike the companion branch's own
                // 22→23 + 23→24 pair, so a fresh install gets the final shape in one step.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `companion_packs` (
                        `packId` TEXT PRIMARY KEY NOT NULL,
                        `scope` TEXT NOT NULL,
                        `targetKey` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `authorHandle` TEXT,
                        `revision` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `lastSeenRevealMs` INTEGER NOT NULL DEFAULT 0,
                        `isOwn` INTEGER NOT NULL DEFAULT 1
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_companion_packs_targetKey` ON `companion_packs` (`targetKey`)")

                // The IF NOT EXISTS above is a no-op on a device whose companion_packs came from
                // the old 22→23, which predates isOwn — so that column still has to be added.
                if (!hasColumn(db, "companion_packs", "isOwn")) {
                    db.execSQL("ALTER TABLE companion_packs ADD COLUMN isOwn INTEGER NOT NULL DEFAULT 1")
                }
                if (!hasColumn(db, "playback_progress", "revealedMs")) {
                    db.execSQL("ALTER TABLE playback_progress ADD COLUMN revealedMs INTEGER NOT NULL DEFAULT 0")
                }
                if (!hasColumn(db, "audio_files", "fileKey")) {
                    db.execSQL("ALTER TABLE audio_files ADD COLUMN fileKey TEXT")
                }
            }
        }

        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 26 → 27 (reader bookmarks & highlights)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `reader_marks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `bookId` INTEGER NOT NULL,
                        `spineIndex` INTEGER NOT NULL,
                        `kind` TEXT NOT NULL,
                        `renderStart` INTEGER NOT NULL,
                        `renderEnd` INTEGER NOT NULL,
                        `textFraction` REAL NOT NULL,
                        `colorArgb` INTEGER NOT NULL DEFAULT 0,
                        `note` TEXT NOT NULL DEFAULT '',
                        `preview` TEXT NOT NULL DEFAULT '',
                        `chapterTitle` TEXT NOT NULL DEFAULT '',
                        `createdAt` INTEGER NOT NULL,
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_reader_marks_bookId` ON `reader_marks` (`bookId`)")
            }
        }

        /**
         * Drops the EPUB reader from the schema: the `reader_marks` and `sync_anchors` tables, the
         * three epub columns on `books`, and the five text-position columns on `playback_progress`.
         * The reader was removed wholesale to be rebuilt from scratch; nothing reads any of this.
         *
         * Both tables are recreated rather than altered because `ALTER TABLE ... DROP COLUMN`
         * needs SQLite 3.35, and minSdk 26 ships 3.19 — the copy-into-a-new-table dance is the
         * only portable way to lose a column. Foreign keys are the reason for the ceremony around
         * it: `playback_progress` points at `books`, so `books` is rebuilt with
         * `PRAGMA foreign_keys` off and `legacy_alter_table` on, or the rename would repoint the
         * child's REFERENCES clause at the temp table. The integrity check at the end is what
         * turns a silently broken key into a loud failure here rather than a crash later.
         *
         * The dropped reading positions and highlights are not migrated anywhere. That is the
         * point of the removal — a from-scratch reader will not read this shape.
         */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 27 → 28 (dropping the epub reader from the schema)")

                db.execSQL("DROP TABLE IF EXISTS `reader_marks`")
                db.execSQL("DROP TABLE IF EXISTS `sync_anchors`")

                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL("PRAGMA legacy_alter_table=ON")

                // ── books: lose ebookPath, ebookSpineCount, chapterMapJson ──────────────
                db.execSQL("DROP TABLE IF EXISTS `books_new`")
                db.execSQL(
                    """
                    CREATE TABLE `books_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL, `seriesId` INTEGER, `seriesName` TEXT,
                        `seriesOrder` REAL, `folderPath` TEXT NOT NULL, `coverArtPath` TEXT,
                        `coverFxPath` TEXT, `totalDurationMs` INTEGER NOT NULL,
                        `addedDateMs` INTEGER NOT NULL, `status` TEXT NOT NULL,
                        `fileCount` INTEGER NOT NULL, `synopsis` TEXT, `narrator` TEXT,
                        `genre` TEXT, `year` INTEGER, `album` TEXT, `description` TEXT,
                        `titleOverride` TEXT, `authorOverride` TEXT, `isIgnored` INTEGER NOT NULL,
                        `skipSilenceEnabled` INTEGER NOT NULL, `dataAppliedAtMs` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `books_new` SELECT id, title, author, seriesId, seriesName,
                        seriesOrder, folderPath, coverArtPath, coverFxPath, totalDurationMs,
                        addedDateMs, status, fileCount, synopsis, narrator, genre, year, album,
                        description, titleOverride, authorOverride, isIgnored, skipSilenceEnabled,
                        dataAppliedAtMs FROM `books`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `books`")
                db.execSQL("ALTER TABLE `books_new` RENAME TO `books`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_seriesId` ON `books` (`seriesId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_books_folderPath` ON `books` (`folderPath`)")

                // ── playback_progress: lose the five text-position columns ──────────────
                db.execSQL("DROP TABLE IF EXISTS `playback_progress_new`")
                db.execSQL(
                    """
                    CREATE TABLE `playback_progress_new` (
                        `bookId` INTEGER NOT NULL, `currentFileId` INTEGER,
                        `positionMs` INTEGER NOT NULL, `lastPlayedMs` INTEGER NOT NULL,
                        `playbackSpeed` REAL NOT NULL, `boostDb` INTEGER NOT NULL,
                        `eqBandsJson` TEXT, `isCompleted` INTEGER NOT NULL,
                        `completedDateMs` INTEGER, `lastPausedAt` INTEGER NOT NULL,
                        `filesBeforeCurrentMs` INTEGER NOT NULL, `revealedMs` INTEGER NOT NULL,
                        PRIMARY KEY(`bookId`),
                        FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE ,
                        FOREIGN KEY(`currentFileId`) REFERENCES `audio_files`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `playback_progress_new` SELECT bookId, currentFileId, positionMs,
                        lastPlayedMs, playbackSpeed, boostDb, eqBandsJson, isCompleted,
                        completedDateMs, lastPausedAt, filesBeforeCurrentMs, revealedMs
                    FROM `playback_progress`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `playback_progress`")
                db.execSQL("ALTER TABLE `playback_progress_new` RENAME TO `playback_progress`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playback_progress_bookId` ON `playback_progress` (`bookId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_progress_currentFileId` ON `playback_progress` (`currentFileId`)")

                db.execSQL("PRAGMA legacy_alter_table=OFF")
                db.execSQL("PRAGMA foreign_keys=ON")
                db.query("PRAGMA foreign_key_check").use { c ->
                    if (c.moveToFirst()) {
                        AppLog.e(LogCat.DB, "migration 27 → 28 left a broken foreign key")
                        throw IllegalStateException("MIGRATION_27_28 broke a foreign key")
                    }
                }
            }
        }

        /**
         * Deletes the standalone ebook-only book rows left behind by the EPUB reader's removal.
         *
         * These rows never represented an audiobook. `EbookScanner` created one per loose `.epub`
         * it found, with `fileCount = 0`, no audio, and a synthetic `"<parentDir>::epub::<stem>"`
         * folderPath — and the library grid kept them out of the Audio section with an
         * `isEbookOnly` check that read `ebookPath != null && totalDurationMs == 0`. Removing the
         * reader took `ebookPath` with it, and so took the filter, and the rows surfaced in the
         * audio library as books that have nothing to play. They are matched on the `::epub::`
         * marker rather than on `fileCount = 0`, which a real audiobook can also reach when its
         * files go missing (`reconcileAgainstDisk` hides those with `isIgnored`, keeping progress).
         *
         * A separate migration rather than a fix to [MIGRATION_27_28] because 28 has already run
         * on a real device; an install still on 27 runs both and ends up in the same place.
         *
         * The `.epub` files and their `data/epub.<slug>.json` mirrors are untouched on disk — that
         * mirror is where the preserved reading position lives (see `BookDataCodec`), so a rebuilt
         * reader can still find both.
         *
         * Every child table is deleted explicitly rather than left to `ON DELETE CASCADE`: Room
         * runs migrations with `PRAGMA foreign_keys` **off**, so the cascade does not fire here and
         * a bare `DELETE FROM books` would leave orphaned progress, chapter and history rows behind
         * pointing at ids that no longer exist.
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                AppLog.i(LogCat.DB, "migrating 28 → 29 (dropping standalone ebook-only book rows)")
                val doomed = "SELECT `id` FROM `books` WHERE `folderPath` LIKE '%::epub::%'"
                for (child in listOf(
                    "audio_files", "playback_progress", "chapters",
                    "bookmarks", "listening_sessions", "skip_events",
                )) {
                    db.execSQL("DELETE FROM `$child` WHERE `bookId` IN ($doomed)")
                }
                db.execSQL("DELETE FROM `books` WHERE `folderPath` LIKE '%::epub::%'")
            }
        }

        /** Whether [table] already has [column], via `PRAGMA table_info` — the only way to ask,
         *  since SQLite has no `ADD COLUMN IF NOT EXISTS`. */
        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean =
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex < 0) return false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return true
                }
                false
            }
    }
}
