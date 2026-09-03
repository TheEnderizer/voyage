package com.betteraudio.companion

import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.Importance
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.data.db.entities.Book

/** The receiver-side "notify me about" setting (docs/companion-packs.md §8.1) — the author's
 *  sense of MAJOR won't match every listener's, so this is a per-listener filter applied on top
 *  of each fact's own author-set [Importance], not a replacement for it. Ordinal order
 *  (MAJOR_ONLY < NOTABLE_PLUS < NEVER) is deliberately NOT used for comparison against
 *  [Importance]'s ordinal — see [RevealDigest.since]. */
enum class ReceiverThreshold { MAJOR_ONLY, NOTABLE_PLUS, NEVER }

/**
 * "Since you last looked" (§8.1) — what changed between two reveal-cursor positions, filtered by
 * the receiver's own notification threshold. Backs both the passive badge (its own existence:
 * `since(...).isNotEmpty()`) and the digest view a companion opens on.
 */
object RevealDigest {

    data class Item(val entity: PackEntity, val fact: PackFact, val resolvedMs: Long)

    /** Facts whose resolved position falls in `(sinceMs, uptoMs]` — i.e. newly revealed since the
     *  last time the companion was opened — that also clear [threshold]. [Importance.QUIET] never
     *  appears regardless of threshold: it is authored to mean "no signal", not "low priority". */
    fun since(doc: CompanionPackDoc, book: Book, sinceMs: Long, uptoMs: Long, threshold: ReceiverThreshold): List<Item> {
        if (threshold == ReceiverThreshold.NEVER || uptoMs <= sinceMs) return emptyList()
        val entityById = doc.entities.associateBy { it.entityId }
        val minImportance = if (threshold == ReceiverThreshold.MAJOR_ONLY) Importance.MAJOR else Importance.NOTABLE

        return doc.facts.mapNotNull { fact ->
            if (fact.importance == Importance.QUIET) return@mapNotNull null
            if (fact.importance.ordinal < minImportance.ordinal) return@mapNotNull null
            val resolved = AnchorResolver.resolve(fact.anchor, book) ?: return@mapNotNull null
            if (resolved.bookGlobalMs !in (sinceMs + 1)..uptoMs) return@mapNotNull null
            val entity = entityById[fact.entityId] ?: return@mapNotNull null
            Item(entity, fact, resolved.bookGlobalMs)
        }.sortedBy { it.resolvedMs }
    }
}
