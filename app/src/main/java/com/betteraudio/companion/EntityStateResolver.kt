package com.betteraudio.companion

import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.data.db.entities.Book

/** One entity's resolved state as of a reveal cursor position — the newest [PackFact] per field
 *  with a resolved position `<= revealedMs`, per docs/companion-packs.md §3 ("An entity has no
 *  state of its own — its state at position *t* is the newest fact per field with `revealAt <= t`").
 *  [firstRevealedMs] is the earliest resolved fact for this entity, i.e. when it was first
 *  introduced — the natural sort key for a cast strip (introduced-so-far, in story order). */
data class CompanionEntityState(
    val entity: PackEntity,
    val fields: Map<String, PackFact>,
    val firstRevealedMs: Long
)

/**
 * Pure resolver: given a pack document and a reveal cursor position, computes which entities are
 * visible and what their current field values are. Never sees anything past [revealedMs] — this
 * is the actual enforcement point of §1's "spoiler-proof by construction" claim, not a filter
 * layered on top of a UI that could otherwise see everything.
 */
object EntityStateResolver {

    fun resolve(doc: CompanionPackDoc, book: Book, revealedMs: Long): List<CompanionEntityState> {
        val entityById = doc.entities.associateBy { it.entityId }
        val resolvedFactsByEntity = HashMap<String, MutableList<Pair<PackFact, Long>>>()

        for (fact in doc.facts) {
            val resolved = AnchorResolver.resolve(fact.anchor, book) ?: continue
            if (resolved.bookGlobalMs > revealedMs) continue
            resolvedFactsByEntity.getOrPut(fact.entityId) { mutableListOf() }.add(fact to resolved.bookGlobalMs)
        }

        return resolvedFactsByEntity.mapNotNull { (entityId, resolvedFacts) ->
            val entity = entityById[entityId] ?: return@mapNotNull null
            // Latest value per field: sort ascending by (resolved position, then rev as a
            // tie-break for two facts anchored at the exact same position), last write wins.
            val latestByField = LinkedHashMap<String, PackFact>()
            resolvedFacts.sortedWith(compareBy({ it.second }, { it.first.rev })).forEach { (fact, _) ->
                latestByField[fact.field] = fact
            }
            CompanionEntityState(
                entity = entity,
                fields = latestByField,
                firstRevealedMs = resolvedFacts.minOf { it.second }
            )
        }.sortedBy { it.firstRevealedMs }
    }
}
