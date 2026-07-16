package com.betteraudio.util

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.betteraudio.MainActivity
import com.betteraudio.data.db.entities.Book
import kotlin.math.abs

/**
 * Pins a launcher shortcut straight to a book's player, bypassing Home. Resolved back to a book
 * at launch by [Book.folderPath] (the portable identity key — see BackupMatcher) rather than the
 * DB row id, since ids aren't stable across a rescan; MainActivity shows a "book not found" toast
 * if the folder no longer resolves to anything (moved, deleted, or rescanned away).
 */
object BookShortcuts {
    const val EXTRA_BOOK_PATH = "com.betteraudio.extra.SHORTCUT_BOOK_PATH"

    fun canPin(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    fun requestPin(context: Context, book: Book) {
        if (!canPin(context)) {
            Toast.makeText(context, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_BOOK_PATH, book.folderPath)
        }
        val shortcutId = "book_${abs(book.folderPath.hashCode())}"
        val label = book.displayTitle
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutId)
            .setShortLabel(label.take(25))
            .setLongLabel(label)
            .setIcon(loadIcon(context, book.coverArtPath))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    private fun loadIcon(context: Context, coverArtPath: String?): IconCompat {
        if (coverArtPath != null) {
            val bitmap = runCatching { BitmapFactory.decodeFile(coverArtPath) }.getOrNull()
            if (bitmap != null) return IconCompat.createWithAdaptiveBitmap(bitmap)
        }
        return IconCompat.createWithResource(context, com.betteraudio.R.mipmap.ic_launcher)
    }
}
