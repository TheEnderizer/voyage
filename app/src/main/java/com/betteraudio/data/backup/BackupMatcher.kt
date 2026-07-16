package com.betteraudio.data.backup

/**
 * Pure matching logic for backup restore — no Android types, so it's covered by JVM unit tests
 * (`app/src/test/.../BackupMatcherTest.kt`) instead of only on-device verification. This is the
 * riskiest code in the backup feature: book row ids are NOT stable across a rescan or a fresh
 * install, so restore has to re-identify each backed-up book against the current library by path
 * and title/author instead.
 */

/** Portable identity for a book, carried in the backup file and computed fresh from the current
 *  library at restore time. [folderPath] is opaque — it may be a synthetic "<dir>::<stem>" for a
 *  multi-book folder — so it's only ever compared as a string, never turned into a [java.io.File]. */
data class BookIdentity(
    val folderPath: String,
    val relPath: String,
    val title: String,
    val author: String
)

/** A candidate book already in the current DB, paired with its id. */
data class BookCandidate(val id: Long, val identity: BookIdentity)

/** A file belonging to a matched book, as a restore target. */
data class FileCandidate(val id: Long, val fileName: String, val durationMs: Long)

sealed class MatchResult {
    data class Matched(val bookId: Long) : MatchResult()
    object NoMatch : MatchResult()
    object Ambiguous : MatchResult()
}

object BackupMatcher {

    private fun norm(s: String) = s.trim().lowercase()

    /**
     * Matches each backup book identity to a current-library book, in order:
     *  1. exact `folderPath` (opaque string equality)
     *  2. `relPath` (portable across a moved/renamed library root)
     *  3. normalized `{title, author}` — trimmed + case-folded so trivial formatting differences
     *     don't defeat "unique"
     * Ambiguous when more than one current candidate matches at the same tier; NoMatch when none
     * matches at any tier. Index-aligned with [backupBooks].
     */
    fun matchBooks(backupBooks: List<BookIdentity>, currentBooks: List<BookCandidate>): List<MatchResult> =
        backupBooks.map { backup -> matchOne(backup, currentBooks) }

    private fun matchOne(backup: BookIdentity, currentBooks: List<BookCandidate>): MatchResult {
        byExact(currentBooks) { it.identity.folderPath == backup.folderPath }?.let { return it }
        if (backup.relPath.isNotBlank()) {
            byExact(currentBooks) { it.identity.relPath == backup.relPath }?.let { return it }
        }
        val bTitle = norm(backup.title)
        val bAuthor = norm(backup.author)
        byExact(currentBooks) { norm(it.identity.title) == bTitle && norm(it.identity.author) == bAuthor }
            ?.let { return it }
        return MatchResult.NoMatch
    }

    private inline fun byExact(candidates: List<BookCandidate>, predicate: (BookCandidate) -> Boolean): MatchResult? {
        val hits = candidates.filter(predicate)
        return when (hits.size) {
            0 -> null // no match at this tier — fall through to the next
            1 -> MatchResult.Matched(hits[0].id)
            else -> MatchResult.Ambiguous
        }
    }

    /**
     * Resolves a backed-up file reference to a file in the matched book, by basename first
     * (case-insensitive fallback), tie-broken by the closest duration when several files share a
     * name. Returns null if nothing matches — callers should degrade gracefully (e.g. restore
     * progress without a currentFileId) rather than guess.
     */
    fun matchFile(fileName: String, durationMs: Long, candidates: List<FileCandidate>): Long? {
        if (candidates.isEmpty()) return null
        val exact = candidates.filter { it.fileName == fileName }
        val pool = exact.ifEmpty { candidates.filter { it.fileName.equals(fileName, ignoreCase = true) } }
        if (pool.isEmpty()) return null
        return pool.minByOrNull { kotlin.math.abs(it.durationMs - durationMs) }?.id
    }
}
