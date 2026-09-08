package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a [ReaderMark] is. Stored as a string so a third kind can be added without a migration. */
object ReaderMarkKind {
    const val BOOKMARK = "BOOKMARK"
    const val HIGHLIGHT = "HIGHLIGHT"
}

/**
 * A place in an EPUB the reader wants back: a bookmarked page, or a highlighted paragraph.
 *
 * Deliberately NOT folded into [Bookmark], which looks superficially similar. That table is
 * audio-only in a way that goes past naming — it is keyed on `fileId` and stores two millisecond
 * positions, neither of which a book with no audio has. A shared table would mean every reader
 * mark carrying three permanently-null audio columns and every audio bookmark carrying four
 * permanently-null text ones, and the "which half of this row is real" check leaking into both
 * features. Two small tables cost one migration and keep both queries honest.
 *
 * **Position is stored twice, on purpose.** [renderStart]/[renderEnd] are offsets into the spine
 * item's render stream — the same coordinate `Page.blocks[].renderStart` uses — and are what the
 * renderer needs to actually paint a highlight over the right words. [textFraction] is the
 * extractor-stream fraction, which is the coordinate `EbookReaderViewModel.jumpToSpine` takes, and
 * is what makes jumping to a mark work when the chapter is not the one currently loaded (the
 * render document for another spine item is not in memory, so its render offsets cannot be
 * resolved without parsing it first). One is for drawing, the other is for travelling.
 */
@Entity(
    tableName = "reader_marks",
    foreignKeys = [ForeignKey(
        entity = Book::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class ReaderMark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val spineIndex: Int,
    /** [ReaderMarkKind]. */
    val kind: String,
    val renderStart: Int,
    val renderEnd: Int,
    /** Extractor-stream fraction within the spine item — see the class doc. */
    val textFraction: Float,
    /** Highlight tint as ARGB; 0 for a bookmark, which has no colour of its own. */
    val colorArgb: Int = 0,
    val note: String = "",
    /** The marked text, trimmed for the list — so Contents reads without reopening the chapter. */
    val preview: String = "",
    val chapterTitle: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
