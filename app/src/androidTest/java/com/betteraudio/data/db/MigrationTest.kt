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
}
