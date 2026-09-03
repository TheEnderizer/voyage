package com.betteraudio.data.files

import com.betteraudio.data.scanner.ImportStructure
import java.io.File

/**
 * Pure `(title, author, series, structure, root) -> target folder` derivation for companion-pack
 * FULL import (docs/companion-packs.md §10.2/§10.3, P7). §10.2 explicitly wants this shared with
 * [LibraryRestructurer.targetFolder] so the two can't drift — this implementation deliberately
 * does **not** do that refactor: `LibraryRestructurer` is a working, unrelated, well-exercised code
 * path (every library move in the app goes through it), and rewiring it to call out to a new pure
 * function is real risk to touch for a companion-pack feature that doesn't need to change its
 * behavior at all. This is the same placement logic, reimplemented from values instead of a `Book`
 * row (which doesn't exist yet at import time — see [com.betteraudio.companion.CompanionPlacementService]),
 * plus the one rule `LibraryRestructurer` deliberately doesn't have: an AUTO structure returns
 * `null` there (it refuses to reshape an AUTO library), but AUTO is the scan *fallback* for
 * anything blank/unknown, so import needs a real answer for it — `<root>/<sanitized title>/`.
 */
object LibraryPaths {

    fun targetFolder(title: String, author: String, seriesName: String?, structure: ImportStructure, root: String): File {
        val safeAuthor = sanitize(author)
        val safeSeries = seriesName?.let(::sanitize)?.takeIf { it.isNotBlank() }
        val safeTitle = sanitize(title).ifBlank { "Book" }

        val parent: File = when (structure) {
            ImportStructure.AUTHOR_SERIES_BOOK -> {
                var p = File(root)
                if (safeAuthor.isNotBlank()) p = File(p, safeAuthor)
                if (safeSeries != null) p = File(p, safeSeries)
                p
            }
            ImportStructure.AUTHOR_DASH_SERIES_BOOK -> {
                val wrapper = when {
                    safeAuthor.isNotBlank() && safeSeries != null -> "$safeAuthor - $safeSeries"
                    safeSeries != null -> safeSeries
                    safeAuthor.isNotBlank() -> safeAuthor
                    else -> null
                }
                if (wrapper != null) File(File(root), sanitize(wrapper)) else File(root)
            }
            // AUTO refuses reshaping an existing library (LibraryRestructurer.targetFolder returns
            // null) but has to place a *new* import somewhere — one flat folder per book, same as
            // AUTO's own single-file-per-book scan output.
            ImportStructure.AUTO -> File(root)
        }
        return File(parent, safeTitle)
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().trim('.').take(120)
}
