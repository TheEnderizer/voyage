package com.betteraudio.data.scanner

/**
 * How the scanner interprets the library's folder layout.
 *
 *  - [AUTO] — the heuristic scanner (embedded ALBUM tags → filename clustering → series
 *    containers). Best for mixed/unknown libraries.
 *  - [AUTHOR_SERIES_BOOK] — `root/author/[series/]book/files`. The series level is optional:
 *    an author-level folder that holds audio directly is a standalone book.
 *  - [AUTHOR_DASH_SERIES_BOOK] — `root/(author - series)/book/files`. The wrapper folder name
 *    is split on `" - "` into author + series; with no dash the whole name is the series and
 *    the author is read from the first file's tags.
 *
 * In both structured modes every folder that directly contains audio is exactly one book and
 * its files are that book's chapters — folders are never split into multiple books by tags.
 */
enum class ImportStructure {
    AUTO,
    AUTHOR_SERIES_BOOK,
    AUTHOR_DASH_SERIES_BOOK;

    companion object {
        /** Parse a stored name, treating anything unknown/blank as [AUTO]. */
        fun fromName(name: String?): ImportStructure =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}
