package com.betteraudio.companion

import com.betteraudio.companion.model.PackMember
import com.betteraudio.data.db.entities.Book

/**
 * Pure title/author matching for companion-pack import (docs/companion-packs.md §10.3 step 2),
 * extracted out of [CompanionImportService] so the matching heuristic itself is unit-testable
 * without a database — same rationale as [com.betteraudio.playback.JumpClassifier]/
 * [AnchorResolver] staying pure. See [CompanionImportService]'s kdoc for why this is
 * title+author matching rather than the full `fileKey` fingerprint match §10.3 describes.
 */
object CompanionPackMatcher {

    /** Prefers a title+author match; falls back to title-only when the member carries no author
     *  (or none of the title matches have a matching author either). Null when nothing matches. */
    fun bestMatch(candidates: List<Book>, member: PackMember): Book? {
        val wantTitle = normalize(member.title)
        val wantAuthor = normalize(member.author)
        if (wantTitle.isBlank()) return null
        candidates.firstOrNull { normalize(it.displayTitle) == wantTitle && (wantAuthor.isBlank() || normalize(it.displayAuthor) == wantAuthor) }
            ?.let { return it }
        return candidates.firstOrNull { normalize(it.displayTitle) == wantTitle }
    }

    fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
}
