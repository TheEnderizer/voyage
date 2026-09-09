package com.betteraudio.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Migration coverage from version 19 forward — the earliest version with an exported schema
 * (see CLAUDE.md's Room section). Versions 3->18 predate `exportSchema` and their schema JSONs
 * were never captured, so they can't be tested this way; that gap is permanent, not deferred —
 * retroactively producing them would need checking out each historical tag to regenerate its
 * schema, which isn't worth doing for migrations that have been running in production for years
 * with no reported corruption.
 */
class MigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /**
     * 19->20 adds four indexes (schema-validated by runMigrationsAndValidate against 20.json)
     * plus a real data backfill: playback_progress.filesBeforeCurrentMs, computed by summing the
     * durations of every audio file before the book's currentFileId. This is the part a pure
     * schema diff can't catch — a wrong backfill still validates, it just serves wrong progress
     * bars — so seed a book paused on its third file and assert the sum of the first two.
     */
    @Test
    fun migrate19To20_backfillsFilesBeforeCurrentMs() {
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO books (id, title, author, folderPath, totalDurationMs, addedDateMs, " +
                    "status, fileCount, isIgnored, skipSilenceEnabled, ebookSpineCount) " +
                    "VALUES (1, 'Book', 'Author', '/x', 9000, 0, 'NOT_STARTED', 3, 0, 0, 0)"
            )
            execSQL(
                "INSERT INTO audio_files (id, bookId, filePath, fileName, trackNumber, durationMs, fileSizeBytes) " +
                    "VALUES (1, 1, '/x/1.mp3', '1.mp3', 1, 3000, 100)"
            )
            execSQL(
                "INSERT INTO audio_files (id, bookId, filePath, fileName, trackNumber, durationMs, fileSizeBytes) " +
                    "VALUES (2, 1, '/x/2.mp3', '2.mp3', 2, 4000, 100)"
            )
            execSQL(
                "INSERT INTO audio_files (id, bookId, filePath, fileName, trackNumber, durationMs, fileSizeBytes) " +
                    "VALUES (3, 1, '/x/3.mp3', '3.mp3', 3, 2000, 100)"
            )
            // Paused partway into file 3 (id=3) — expected filesBeforeCurrentMs = 3000 + 4000 = 7000.
            execSQL(
                "INSERT INTO playback_progress (bookId, currentFileId, positionMs, lastPlayedMs, " +
                    "playbackSpeed, boostDb, isCompleted, lastPausedAt, textOverallFraction, lastMode) " +
                    "VALUES (1, 3, 500, 0, 1.0, 0, 0, 0, 0.0, 'AUDIO')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, AppDatabase.MIGRATION_19_20)
        db.query("SELECT filesBeforeCurrentMs FROM playback_progress WHERE bookId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(7000L, cursor.getLong(0))
        }
    }

    /** A book with no in-progress playback (currentFileId NULL) must not be touched by the
     *  backfill loop — it's filtered out by the migration's own WHERE currentFileId IS NOT NULL,
     *  so it should just keep the column's DEFAULT 0. */
    @Test
    fun migrate19To20_leavesUnstartedBookAtZero() {
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO books (id, title, author, folderPath, totalDurationMs, addedDateMs, " +
                    "status, fileCount, isIgnored, skipSilenceEnabled, ebookSpineCount) " +
                    "VALUES (1, 'Book', 'Author', '/x', 3000, 0, 'NOT_STARTED', 1, 0, 0, 0)"
            )
            execSQL(
                "INSERT INTO audio_files (id, bookId, filePath, fileName, trackNumber, durationMs, fileSizeBytes) " +
                    "VALUES (1, 1, '/x/1.mp3', '1.mp3', 1, 3000, 100)"
            )
            execSQL(
                "INSERT INTO playback_progress (bookId, currentFileId, positionMs, lastPlayedMs, " +
                    "playbackSpeed, boostDb, isCompleted, lastPausedAt, textOverallFraction, lastMode) " +
                    "VALUES (1, NULL, 0, 0, 1.0, 0, 0, 0, 0.0, 'AUDIO')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 20, true, AppDatabase.MIGRATION_19_20)
        db.query("SELECT filesBeforeCurrentMs FROM playback_progress WHERE bookId = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0L, cursor.getLong(0))
        }
    }

    /**
     * 27->28 drops the EPUB reader from the schema: two whole tables and eight columns across
     * `books` and `playback_progress`. Both tables have to be rebuilt to lose a column (minSdk 26
     * ships SQLite 3.19, and `DROP COLUMN` needs 3.35), which makes this the migration most able
     * to quietly lose data — a copy that forgets a column still validates against 28.json, it
     * just serves an empty library.
     *
     * So: seed a book that has every kind of state worth keeping (audio progress mid-file, a
     * reveal cursor, audio settings) alongside the epub state being dropped, and assert the
     * survivors came through intact and the foreign key still points somewhere real.
     */
    @Test
    fun migrate27To28_dropsEpubColumnsAndKeepsEverythingElse() {
        helper.createDatabase(dbName, 27).apply {
            execSQL(
                "INSERT INTO books (id, title, author, folderPath, totalDurationMs, addedDateMs, " +
                    "status, fileCount, isIgnored, skipSilenceEnabled, ebookPath, ebookSpineCount, " +
                    "chapterMapJson, dataAppliedAtMs) " +
                    "VALUES (1, 'Book', 'Author', '/x', 9000, 5, 'IN_PROGRESS', 3, 0, 1, " +
                    "'/x/book.epub', 62, '[0,1,2]', 7)"
            )
            execSQL(
                "INSERT INTO audio_files (id, bookId, filePath, fileName, trackNumber, durationMs, " +
                    "fileSizeBytes) VALUES (10, 1, '/x/03.mp3', '03.mp3', 3, 3000, 4096)"
            )
            execSQL(
                "INSERT INTO playback_progress (bookId, currentFileId, positionMs, lastPlayedMs, " +
                    "playbackSpeed, boostDb, isCompleted, lastPausedAt, textSpineIndex, textFraction, " +
                    "textCharOffset, textOverallFraction, lastMode, filesBeforeCurrentMs, revealedMs) " +
                    "VALUES (1, 10, 1234, 99, 1.4, 3, 1, 88, 12, 0.4, 8842, 0.19, 'TEXT', 6000, 7200)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 28, true, AppDatabase.MIGRATION_27_28)

        db.query("SELECT * FROM playback_progress WHERE bookId = 1").use { c ->
            assertTrue("progress row was lost by the table rebuild", c.moveToFirst())
            assertEquals(10L, c.getLong(c.getColumnIndexOrThrow("currentFileId")))
            assertEquals(1234L, c.getLong(c.getColumnIndexOrThrow("positionMs")))
            assertEquals(1.4f, c.getFloat(c.getColumnIndexOrThrow("playbackSpeed")), 0.001f)
            assertEquals(3, c.getInt(c.getColumnIndexOrThrow("boostDb")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("isCompleted")))
            assertEquals(88L, c.getLong(c.getColumnIndexOrThrow("lastPausedAt")))
            assertEquals(6000L, c.getLong(c.getColumnIndexOrThrow("filesBeforeCurrentMs")))
            assertEquals(7200L, c.getLong(c.getColumnIndexOrThrow("revealedMs")))
            assertEquals(-1, c.getColumnIndex("textSpineIndex"))
            assertEquals(-1, c.getColumnIndex("lastMode"))
        }
        db.query("SELECT * FROM books WHERE id = 1").use { c ->
            assertTrue("book row was lost by the table rebuild", c.moveToFirst())
            assertEquals("Book", c.getString(c.getColumnIndexOrThrow("title")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("skipSilenceEnabled")))
            assertEquals(7L, c.getLong(c.getColumnIndexOrThrow("dataAppliedAtMs")))
            assertEquals(-1, c.getColumnIndex("ebookPath"))
            assertEquals(-1, c.getColumnIndex("chapterMapJson"))
        }
        // The rebuild renames tables with foreign keys off; if the child's REFERENCES clause had
        // been repointed at the temp table, this would come back non-empty.
        db.query("PRAGMA foreign_key_check").use { c ->
            assertTrue("migration left a broken foreign key", !c.moveToFirst())
        }
    }

    /**
     * The whole chain, 19 to 28, over one seeded book.
     *
     * The per-step tests above each prove one migration in isolation; this proves they compose.
     * That matters most for 27->28, which is the first migration here that *rebuilds* tables
     * rather than adding to them — it is the one step that can drop a column another migration
     * added five versions earlier, and no isolated test would notice.
     *
     * A real install arriving at 28 has walked exactly this path, so what it asserts is the thing
     * a user actually cares about: the book is still there, and it is still paused where they
     * left it.
     */
    @Test
    fun migrate19To28_wholeChainKeepsTheBookAndItsPosition() {
        helper.createDatabase(dbName, 19).apply {
            execSQL(
                "INSERT INTO books (id, title, author, folderPath, totalDurationMs, addedDateMs, " +
                    "status, fileCount, isIgnored, skipSilenceEnabled, ebookSpineCount) " +
                    "VALUES (1, 'Chain Book', 'Author', '/x', 9000, 5, 'IN_PROGRESS', 1, 0, 1, 0)"
            )
            execSQL(
                "INSERT INTO audio_files (id, bookId, filePath, fileName, trackNumber, durationMs, " +
                    "fileSizeBytes) VALUES (10, 1, '/x/01.mp3', '01.mp3', 1, 9000, 4096)"
            )
            execSQL(
                "INSERT INTO playback_progress (bookId, currentFileId, positionMs, lastPlayedMs, " +
                    "playbackSpeed, boostDb, isCompleted, lastPausedAt, textOverallFraction, lastMode) " +
                    "VALUES (1, 10, 4321, 99, 1.4, 3, 0, 88, 0.5, 'TEXT')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 28, true, AppDatabase.MIGRATION_19_20, AppDatabase.MIGRATION_20_21, AppDatabase.MIGRATION_21_22, AppDatabase.MIGRATION_22_23, AppDatabase.MIGRATION_23_25, AppDatabase.MIGRATION_24_25, AppDatabase.MIGRATION_25_26, AppDatabase.MIGRATION_26_27, AppDatabase.MIGRATION_27_28)

        db.query("SELECT * FROM books WHERE id = 1").use { c ->
            assertTrue("the book did not survive 19 -> 28", c.moveToFirst())
            assertEquals("Chain Book", c.getString(c.getColumnIndexOrThrow("title")))
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("skipSilenceEnabled")))
        }
        db.query("SELECT * FROM playback_progress WHERE bookId = 1").use { c ->
            assertTrue("the saved position did not survive 19 -> 28", c.moveToFirst())
            assertEquals(4321L, c.getLong(c.getColumnIndexOrThrow("positionMs")))
            assertEquals(10L, c.getLong(c.getColumnIndexOrThrow("currentFileId")))
            assertEquals(1.4f, c.getFloat(c.getColumnIndexOrThrow("playbackSpeed")), 0.001f)
        }
        db.query("PRAGMA foreign_key_check").use { c ->
            assertTrue("the chain left a broken foreign key", !c.moveToFirst())
        }
    }
}
