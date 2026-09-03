package com.betteraudio.companion

import com.betteraudio.companion.model.BoardArt
import com.betteraudio.companion.model.CompanionPackDoc
import com.betteraudio.companion.model.PackBoard
import com.betteraudio.companion.model.PackBoardElement
import com.betteraudio.companion.model.PackEntity
import com.betteraudio.companion.model.PackFact
import com.betteraudio.data.db.entities.Book

/** One entity's pin, already placed at its as-of-[EntityStateResolver] resolved coordinates —
 *  see [PackBoardResolver.resolvePins]'s kdoc for how a pin "moves". */
data class ResolvedPin(
    val entity: PackEntity,
    val elementId: String,
    val xUnits: Float,
    val yUnits: Float,
    val locationFact: PackFact
)

/**
 * Pure board-layout resolution (docs/companion-packs.md §4.1/§5, P5) — sits on top of
 * [EntityStateResolver] rather than re-walking [CompanionPackDoc.facts] itself, so a pin's
 * spoiler-safety is inherited for free from the one place that enforcement actually lives.
 *
 * **How a pin moves**, resolving the P0 kdoc's "a MAP pin's position tracks `PackEntity`'s
 * `location` field" note into a concrete rule: a [PIN_KIND] [PackBoardElement] is a *marker*, not a
 * position — its own `x`/`y` are unused. Its real, as-of-now coordinates come from the bound
 * entity's newest resolved fact on [PackBoardElement.field] (default [DEFAULT_LOCATION_FIELD]),
 * whose `value` is `"<xUnits>,<yUnits>"` in the same [com.betteraudio.companion.model.CANVAS_UNITS]
 * design space every other board element uses. That is what lets a character's pin walk across the
 * map over the course of the book: each waypoint is just another fact, anchored at the moment they
 * arrive there — placing/dragging a pin in the editor is authoring a fact, not moving an element
 * (see [com.betteraudio.companion.CompanionAuthorRepository.addFact]). A pin renders nothing until
 * the entity has a resolved value for that field — never a stray marker at (0,0).
 *
 * Non-pin elements (LABEL/IMAGE/SHAPE/BACKGROUND art) are author-placed decoration with no moving
 * part — [staticElements] returns them as-authored, no reveal filtering.
 */
object PackBoardResolver {
    const val PIN_KIND = "PIN"
    const val DEFAULT_LOCATION_FIELD = "location"

    fun resolvePins(doc: CompanionPackDoc, board: PackBoard, book: Book, revealedMs: Long): List<ResolvedPin> {
        val states = EntityStateResolver.resolve(doc, book, revealedMs).associateBy { it.entity.entityId }
        return board.elements
            .filter { it.kind == PIN_KIND && it.entityRef != null }
            .mapNotNull { element ->
                val state = states[element.entityRef] ?: return@mapNotNull null
                val field = element.field?.takeIf { it.isNotBlank() } ?: DEFAULT_LOCATION_FIELD
                val fact = state.fields[field] ?: return@mapNotNull null
                val coords = parseCoords(fact.value) ?: return@mapNotNull null
                ResolvedPin(state.entity, element.id, coords.first, coords.second, fact)
            }
    }

    /**
     * The board's backdrop as of [revealedMs]: its newest revision the listener has reached, or
     * [PackBoard.artMedia] before any of them.
     *
     * Same rule and same enforcement point as a fact — a revision anchored past the cursor is not
     * "hidden by the UI", it is never returned. That matters more here than for a pin: a map of the
     * capital after it burns is a spoiler even with no pins on it at all.
     *
     * Returns a **pack-relative** path; the caller resolves it against the pack directory.
     */
    fun resolveArt(board: PackBoard, book: Book, revealedMs: Long): String? =
        board.artRevisions
            .mapNotNull { art ->
                val at = AnchorResolver.resolve(art.anchor, book)?.bookGlobalMs ?: return@mapNotNull null
                if (at > revealedMs) null else art to at
            }
            .maxByOrNull { it.second }
            ?.first?.media
            ?: board.artMedia

    /** Every revision with its resolved position, oldest first — the editor's revision strip.
     *  Unfiltered: an author is by definition ahead of their own reveal cursor. */
    fun artTimeline(board: PackBoard, book: Book): List<Pair<BoardArt, Long>> =
        board.artRevisions
            .mapNotNull { art ->
                AnchorResolver.resolve(art.anchor, book)?.bookGlobalMs?.let { art to it }
            }
            .sortedBy { it.second }

    fun staticElements(board: PackBoard): List<PackBoardElement> = board.elements.filterNot { it.kind == PIN_KIND }

    fun parseCoords(value: String): Pair<Float, Float>? {
        val parts = value.split(",")
        if (parts.size != 2) return null
        val x = parts[0].trim().toFloatOrNull() ?: return null
        val y = parts[1].trim().toFloatOrNull() ?: return null
        return x to y
    }

    fun formatCoords(x: Float, y: Float): String = "${x.toInt()},${y.toInt()}"
}
