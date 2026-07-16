package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A *confirmed* navigation jump within a book — chapter selection, bookmark jump, a confirmed
 * long scrubber drag, "Listen from here" / "Read from here", or a reading-side chapter/TOC jump.
 * Fixed-amount skip-button taps and unconfirmed scrubs are intentionally NOT recorded here.
 *
 * [kind] discriminates which side of a linked audio+epub book the jump happened on — "AUDIO"
 * (the original shape: `fromPositionMs`/`toPositionMs`/`chapterIndex`/`chapterName`) or "TEXT"
 * (`fromSpineIndex`/`fromFraction` → `toSpineIndex`/`toFraction`/`toSpineTitle`). Both kinds share
 * one table keyed by `bookId` so a linked book's history is naturally one merged, time-ordered
 * list — connecting/disconnecting an epub doesn't move or split anything.
 */
@Entity(
    tableName = "skip_events",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class SkipEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val atMs: Long = System.currentTimeMillis(),
    val kind: String = "AUDIO",
    // How this entry was recorded: "jump" (chapter/bookmark/scrub — the original confirmed-jump
    // meaning above), "skip_button" (coalesced fixed-amount skip taps), or "auto" (a periodic
    // checkpoint every ~10 continuous listening minutes, so "where was I an hour ago" works even
    // without an explicit jump). Pruned at different rates — see ListeningHistoryDao.pruneBySource.
    val source: String = "jump",
    // ── Audio-side jump (kind == "AUDIO") ────────────────────────────────────
    val fromPositionMs: Long = 0,
    val toPositionMs: Long = 0,
    val chapterIndex: Int = -1,
    val chapterName: String = "",
    // ── Text-side jump (kind == "TEXT") ──────────────────────────────────────
    val fromSpineIndex: Int? = null,
    val fromFraction: Float? = null,
    val toSpineIndex: Int? = null,
    val toFraction: Float? = null,
    val toSpineTitle: String? = null
)
