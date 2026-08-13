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
import com.betteraudio.util.log.LogCat
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
            AppLog.w(LogCat.UI, "requestPin: launcher doesn't support pinned shortcuts, book=${book.id}")
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
        val requested = runCatching { ShortcutManagerCompat.requestPinShortcut(context, shortcut, null) }
            .onFailure { AppLog.w(LogCat.UI, "requestPin: pin request threw for book=${book.id}: ${it.message}") }
            .getOrDefault(false)
        AppLog.i(LogCat.UI, "requestPin: book=${book.id} '$label' requested=$requested")
    }

    private fun loadIcon(context: Context, coverArtPath: String?): IconCompat {
        if (coverArtPath != null) {
            val bitmap = runCatching { BitmapFactory.decodeFile(coverArtPath) }.getOrNull()
            if (bitmap != null) return IconCompat.createWithAdaptiveBitmap(bitmap)
            AppLog.w(LogCat.UI, "requestPin: could not decode cover '$coverArtPath', using launcher icon")
        }
        return IconCompat.createWithResource(context, com.betteraudio.R.mipmap.ic_launcher)
    }
}
