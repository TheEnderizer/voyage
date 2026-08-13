package com.betteraudio.data.diskstore

import com.betteraudio.data.backup.BackupMatcher
import com.betteraudio.data.backup.BookCandidate
import com.betteraudio.data.backup.BookIdentity
import com.betteraudio.data.backup.FileCandidate
import com.betteraudio.data.backup.MatchResult
import com.betteraudio.data.db.entities.Bookmark
import com.betteraudio.data.db.entities.ListeningSession
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.SkipEvent
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** How strongly a [BookDocument]/[LibraryDocument] should override what's already in the DB. */
enum class ApplyMode {
    /** A brand-new row built from a fresh scan — the doc is the only source of precious data
     *  there is; every field it carries wins outright. */
    FRESH_IMPORT,
    /** An existing row, applied because the doc looks newer (scan-time reconciliation) —
     *  conservative: the DB's own value wins except where it's null/blank and the doc's isn't;
     *  `isIgnored` is never re-applied (reconcileAgainstDisk only ever hides, never un-hides).
     *  Progress only overwrites when the doc's lastPlayedMs is strictly newer. Collections
     *  (bookmarks/sessions/skips) are always additive + deduped, independent of mode. */
    MERGE,
    /** User-requested "overwrite anyway" (backup restore) — like FRESH_IMPORT for scalars and
     *  progress, but applied to a row that already exists. */
    FORCE
}

data class BookApplyCounts(
    val bookmarksInserted: Int = 0,
    val sessionsInserted: Int = 0,
    val skipEventsInserted: Int = 0,
    /** true when the doc had progress but the local copy was newer/kept (MERGE only). */
    val progressKeptLocal: Boolean = false
)

/**
 * The one implementation of "apply a disk/backup document to the DB", shared by
 * [com.betteraudio.data.backup.BackupManager] (restoring a manually exported/shared backup) and
 * the scanner (reconciling a book's `data/book.json` at scan time) — extracted so the two paths
 * can never drift out of sync on the freshness/dedup rules, which are the riskiest part of either
 * feature. Every rule here mirrors what `BackupManager.restoreBook` originally did, corrected to
 * take an explicit [ApplyMode] instead of a single `forceOverwrite` boolean that only ever guarded
 * progress — see the storage-redesign plan for why that was a bug waiting to happen once this
 * logic started running on every rescan instead of only on an explicit user-triggered restore.
 */
@Singleton
class RestoreOps @Inject constructor(
    private val repository: AudiobookRepository,
    private val seriesRepository: SeriesRepository
) {
    suspend fun applyBookDocument(bookId: Long, doc: BookDocument, mode: ApplyMode): BookApplyCounts {
        val docWins = mode != ApplyMode.MERGE
        val current = repository.getBookOnce(bookId)

        val titleOverride = resolveOverride(docWins, current?.titleOverride, doc.titleOverride)
        val authorOverride = resolveOverride(docWins, current?.authorOverride, doc.authorOverride)
        if (titleOverride != null || authorOverride != null) {
            repository.updateBookMetadata(bookId, titleOverride ?: current?.titleOverride, authorOverride ?: current?.authorOverride)
        }
        resolveOverride(docWins, current?.narrator, doc.narrator)?.let { repository.updateBookNarrator(bookId, it) }
        resolveOverride(docWins, current?.synopsis, doc.synopsis)?.let { repository.updateSynopsis(bookId, it) }
        if (docWins) {
            repository.setSkipSilenceEnabled(bookId, doc.skipSilenceEnabled)
            repository.setBookIgnored(bookId, doc.isIgnored)
        }
        // isIgnored is never re-applied in MERGE — reconcileAgainstDisk already owns "is this book
        // still present on disk", and a stale doc must never re-hide a book whose files are
        // demonstrably here right now.

        val fileCandidates = repository.getAudioFilesOnce(bookId).map { FileCandidate(it.id, it.fileName, it.durationMs) }

        var progressKeptLocal = false
        doc.progress?.let { p ->
            val existing = repository.getProgressForBookOnce(bookId)
            val shouldWrite = docWins || existing == null || p.lastPlayedMs > existing.lastPlayedMs
            if (shouldWrite) {
                val currentFileId = p.currentFile?.let { cf -> BackupMatcher.matchFile(cf.fileName, cf.durationMs, fileCandidates) }
                repository.saveProgress(
                    PlaybackProgress(
                        bookId = bookId,
                        currentFileId = currentFileId,
                        positionMs = p.positionMs,
                        lastPlayedMs = p.lastPlayedMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        playbackSpeed = p.playbackSpeed,
                        boostDb = p.boostDb,
                        eqBandsJson = p.eqBandsJson,
                        isCompleted = p.isCompleted,
                        completedDateMs = p.completedDateMs,
                        lastPausedAt = p.lastPausedAt,
                        textSpineIndex = p.textSpineIndex,
                        textFraction = p.textFraction,
                        textOverallFraction = p.textOverallFraction,
                        lastMode = p.lastMode
                    )
                )
                val status = BackupMatcher.deriveBookStatus(doc.status, p.isCompleted, p.positionMs)
                repository.updateBookStatus(bookId, status)
            } else {
                progressKeptLocal = true
            }
        }

        val bookmarksInserted = applyBookmarks(bookId, doc.bookmarks, fileCandidates)
        val sessionsInserted = applySessions(bookId, doc.sessions)
        val skipEventsInserted = applySkipEvents(bookId, doc.skipEvents)

        AppLog.d(LogCat.DISK) {
            "applyBookDocument book=$bookId mode=$mode bookmarks=+$bookmarksInserted sessions=+$sessionsInserted " +
                "skipEvents=+$skipEventsInserted progressKeptLocal=$progressKeptLocal"
        }
        return BookApplyCounts(bookmarksInserted, sessionsInserted, skipEventsInserted, progressKeptLocal)
    }

    /** null = "don't touch this field" (MERGE with nothing worth overriding); non-null (including
     *  "") = the value to write. In MERGE, a doc value only wins when the current one is blank. */
    private fun resolveOverride(docWins: Boolean, currentValue: String?, docValue: String?): String? {
        if (docWins) return docValue
        return docValue?.takeIf { it.isNotBlank() && currentValue.isNullOrBlank() }
    }

    private suspend fun applyBookmarks(bookId: Long, entries: List<BookDocument.BookmarkEntry>, fileCandidates: List<FileCandidate>): Int {
        if (entries.isEmpty()) return 0
        val existingKeys = repository.getBookmarksForBook(bookId).first().map { it.createdAt to it.absolutePositionMs }.toSet()
        var inserted = 0
        entries.forEach { bm ->
            if ((bm.createdAt to bm.absolutePositionMs) in existingKeys) return@forEach
            val fileId = BackupMatcher.matchFile(bm.fileName, 0L, fileCandidates) ?: return@forEach
            repository.addBookmark(
                Bookmark(
                    bookId = bookId, fileId = fileId, positionInFileMs = bm.positionInFileMs,
                    absolutePositionMs = bm.absolutePositionMs, comment = bm.comment, createdAt = bm.createdAt
                )
            )
            inserted++
        }
        return inserted
    }

    private suspend fun applySessions(bookId: Long, entries: List<BookDocument.SessionEntry>): Int {
        if (entries.isEmpty()) return 0
        val existingStarts = repository.getSessionsForBook(bookId).first().map { it.startMs }.toSet()
        var inserted = 0
        entries.forEach { s ->
            if (s.startMs in existingStarts) return@forEach
            repository.insertListeningSession(
                ListeningSession(
                    bookId = bookId, startMs = s.startMs, endMs = s.endMs,
                    startChapterIndex = s.startChapterIndex, startChapterName = s.startChapterName,
                    endChapterIndex = s.endChapterIndex, endChapterName = s.endChapterName,
                    startPositionInChapterMs = s.startPositionInChapterMs, endPositionInChapterMs = s.endPositionInChapterMs,
                    endBookPositionMs = s.endBookPositionMs, listenedMs = s.listenedMs
                )
            )
            inserted++
        }
        return inserted
    }

    private suspend fun applySkipEvents(bookId: Long, entries: List<BookDocument.SkipEventEntry>): Int {
        if (entries.isEmpty()) return 0
        val existingAt = repository.getSkipsForBook(bookId).first().map { it.atMs }.toSet()
        var inserted = 0
        entries.forEach { sk ->
            if (sk.atMs in existingAt) return@forEach
            repository.insertSkipEvent(
                SkipEvent(
                    bookId = bookId, atMs = sk.atMs, kind = sk.kind, source = sk.source,
                    fromPositionMs = sk.fromPositionMs, toPositionMs = sk.toPositionMs,
                    chapterIndex = sk.chapterIndex, chapterName = sk.chapterName,
                    fromSpineIndex = sk.fromSpineIndex, fromFraction = sk.fromFraction,
                    toSpineIndex = sk.toSpineIndex, toFraction = sk.toFraction, toSpineTitle = sk.toSpineTitle
                )
            )
            inserted++
        }
        return inserted
    }

    // ── Library-wide (presets/authors/series) ───────────────────────────────────────────────

    suspend fun applyPresets(entries: List<LibraryDocument.PresetEntry>): Int {
        if (entries.isEmpty()) return 0
        val existing = repository.getAllAudioPresets().first()
        val backupHasDefault = entries.any { it.isDefault }
        var defaultName: String? = null
        entries.forEach { entry ->
            val existingMatch = existing.find { it.name == entry.name }
            val preset = com.betteraudio.data.db.entities.AudioPreset(
                id = existingMatch?.id ?: 0L, name = entry.name, type = entry.type,
                speedMult = entry.speedMult, boostDb = entry.boostDb, eqBandsJson = entry.eqBandsJson,
                isDefault = if (backupHasDefault) false else (existingMatch?.isDefault ?: false)
            )
            if (preset.id != 0L) repository.updateAudioPreset(preset) else repository.insertAudioPreset(preset)
            if (entry.isDefault) defaultName = entry.name
        }
        if (backupHasDefault) {
            defaultName?.let { name ->
                repository.getAllAudioPresets().first().find { it.name == name }?.let { repository.setDefaultAudioPreset(it.id) }
            }
        }
        return entries.size
    }

    suspend fun applyAuthors(entries: List<LibraryDocument.AuthorEntry>) {
        val existingNames = repository.getAllAuthorMetaOnce().map { it.name }.toSet()
        entries.forEach { entry ->
            if (entry.name !in existingNames) {
                repository.upsertAuthorMeta(com.betteraudio.data.db.entities.AuthorMeta(name = entry.name))
            }
        }
    }

    suspend fun applySeries(entries: List<LibraryDocument.SeriesEntry>, currentBooks: List<BookCandidate>): Int {
        var count = 0
        entries.forEach { entry ->
            val seriesId = seriesRepository.getOrCreateSeriesByName(entry.name, entry.author)
            seriesRepository.getSeriesOnce(seriesId)?.let { existing ->
                seriesRepository.updateSeries(
                    existing.copy(
                        narrator = entry.narrator ?: existing.narrator,
                        description = entry.description ?: existing.description,
                        playbackSpeed = entry.playbackSpeed ?: existing.playbackSpeed,
                        boostDb = entry.boostDb ?: existing.boostDb,
                        eqBandsJson = entry.eqBandsJson ?: existing.eqBandsJson,
                        skipSilenceEnabled = entry.skipSilenceEnabled ?: existing.skipSilenceEnabled
                    )
                )
            }
            entry.members.forEach { m ->
                val identity = BookIdentity(m.folderPath, m.relPath, m.title, m.author)
                val result = BackupMatcher.matchBooks(listOf(identity), currentBooks).single()
                if (result is MatchResult.Matched) {
                    seriesRepository.addBookToSeries(result.bookId, seriesId, m.seriesOrder)
                }
            }
            count++
        }
        return count
    }
}
