package com.betteraudio.companion.model

import java.util.UUID

/**
 * In-memory shape of `pack.json` (docs/companion-packs.md §4) — a portable document of what is
 * true in a story and when the listener is allowed to know it, plus the boards that draw it.
 *
 * **Every id in this tree is a UUID minted at authoring time, never a Room row id.** This is the
 * single most important constraint in the whole design: the moment a fact's identity is a local
 * autoincrement, editing, updating and sharing all break simultaneously and there is no repair
 * path, because the author's id and the recipient's id would be different facts. [CompanionPackCodec]
 * enforces this on decode (a missing/blank id is generated, never left null) rather than relying
 * on every call site remembering to mint one.
 *
 * Loaded whole into memory when a companion becomes active — a generous pack (40 characters × 20
 * facts × 10 books) is a few thousand records, kilobytes, so there is deliberately no relational
 * storage for any of this (see [com.betteraudio.data.db.entities.CompanionPack], the thin Room
 * registry row this document sits behind).
 */
data class CompanionPackDoc(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val packId: String = UUID.randomUUID().toString(),
    /** Monotonic, bumped by the owner on every export (docs/companion-packs.md §9.2). */
    val revision: Int = 1,
    val scope: PackScope = PackScope.BOOK,
    val title: String = "",
    val authorHandle: String? = null,
    /** Set only when this pack was created via "Duplicate as my own" (§9.2) — attribution back to
     *  the pack it was forked from. */
    val derivedFrom: DerivedFrom? = null,
    val payload: PackPayload = PackPayload.DATA_ONLY,
    /** Exactly one entry for [PackScope.BOOK]; several for [PackScope.SERIES]. */
    val members: List<PackMember> = emptyList(),
    val entities: List<PackEntity> = emptyList(),
    val facts: List<PackFact> = emptyList(),
    val boards: List<PackBoard> = emptyList(),
    /** Unrecognised top-level keys from a newer schema version, carried through unchanged on a
     *  decode→encode round trip (see [com.betteraudio.data.diskstore.captureUnknown]). */
    val unknown: Map<String, Any?> = emptyMap()
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class PackScope { BOOK, SERIES }

/** DATA_ONLY ships no audio (the common share); FULL bundles the audio alongside (docs
 *  §10.1) — only meaningful on the exported `.voyagepack` container, not persisted once a pack is
 *  attached to the library (the audio, if any, has already been placed by then). */
enum class PackPayload { DATA_ONLY, FULL }

data class DerivedFrom(
    val packId: String,
    val revision: Int,
    val authorHandle: String?
)

/**
 * One book this pack references. [bookRef] is a short id local to this pack file (NOT a Room
 * book id — meaningless on another device), used by every [FactAnchor.bookRef]. Everything else
 * is the fingerprint used to resolve [bookRef] against the recipient's own library (§10.3 step 2,
 * [com.betteraudio.data.backup.BackupMatcher]).
 */
data class PackMember(
    val bookRef: String,
    val title: String,
    val author: String,
    val seriesOrder: Float? = null,
    val durationMs: Long = 0L,
    val fileCount: Int = 0,
    /** `sha1(size || first 64 KB || last 64 KB)` per file, in playback order — see
     *  [com.betteraudio.companion.model.FileKey]. Deliberately excludes duration (decoder-derived,
     *  not file-derived — see §5's note on why that would make the "exact" anchor tier silently
     *  unreliable). */
    val fileKeys: List<String> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
)

enum class EntityKind { CHARACTER, PLACE, FACTION, ITEM, CONCEPT }

/** A named thing with fields (§3). Has no state of its own — its state at position *t* is the
 *  newest [PackFact] per field with a resolved reveal time `<= t` (see RevealCursor). [media] is a
 *  path relative to the pack's `media/` directory, e.g. "media/kelsier.webp". */
data class PackEntity(
    val entityId: String,
    val kind: EntityKind,
    val name: String,
    val media: String? = null,
    val unknown: Map<String, Any?> = emptyMap()
)

/**
 * Where a fact is anchored in the story, in resolution-fallback order (§5) — tried top to bottom,
 * first that resolves wins:
 *  1. [fileKey] + [offsetMs] — exact, survives folder renames/file re-sorting.
 *  2. [globalMs] — exact, requires an identical file set.
 *  3. [chapter] + [chapterOffsetMs] — exact if chapter counts match, survives file re-splits.
 *  4. [quote] — a text anchor resolved through SyncAnchor/mapping.json; the only tier that
 *     survives a different edition/narrator, at paragraph precision. Stretch-goal tier — needs a
 *     linked EPUB, a completed alignment, AND a quote→locator search this codebase does not yet
 *     have (see docs/companion-packs.md §14).
 *  5. [ratio] — fraction of total book duration; ±minutes, works against anything.
 * Not every tier need be populated — an author-side pack normally carries #1/#2/#3 together (all
 * three are cheap to compute from one playback position) and #4/#5 only when hand-added for
 * edition robustness.
 */
data class FactAnchor(
    val bookRef: String,
    val fileKey: String? = null,
    val offsetMs: Long? = null,
    val globalMs: Long? = null,
    val chapter: Int? = null,
    val chapterOffsetMs: Long? = null,
    val quote: String? = null,
    val ratio: Float? = null,
    val unknown: Map<String, Any?> = emptyMap()
)

enum class Importance { QUIET, NOTABLE, MAJOR }

/**
 * The atom (§3). Asserts one [field] of one [entityId] as of one point in the story, with two
 * independent timestamps: [anchor]'s resolved position is *when the listener is allowed to know
 * it* (`revealAt`); [storyAt] is *when it became true in the story*, null meaning "same as
 * revealAt" (the common case — most facts are not twists). Rendering "the world as of now" filters
 * on the resolved anchor; a chronology view would sort on [storyAt].
 *
 * [origin] is the [CompanionPackDoc.packId] this fact originated from — populated even in a
 * single-pack file (it's just that pack's own id) so the value is already correct the moment
 * multi-pack layering (§13) or the edits overlay (§9.2) needs to tell "whose fact is this" apart.
 * [rev] is the pack [CompanionPackDoc.revision] this fact last changed in — the basis for §9.2's
 * conflict detection: an edit op records the `rev` it was authored against, and a conflict is
 * `op.rev != incoming.rev`.
 */
data class PackFact(
    val factId: String,
    val entityId: String,
    val field: String,
    val value: String,
    val anchor: FactAnchor,
    val storyAt: Long? = null,
    val importance: Importance = Importance.NOTABLE,
    val origin: String? = null,
    val rev: Int = 1,
    val unknown: Map<String, Any?> = emptyMap()
)

enum class BoardKind { MAP, SHEET, CAST, GRAPH, GLOSSARY }

/** FULLSCREEN is a persistent overlay (not a nav destination — see §8); SHEET is a bottom sheet
 *  over the player; INLINE sits inside the player itself (the cast strip). */
enum class Presentation { FULLSCREEN, SHEET, INLINE }

/** Hint for which side of light/dark the board's own art reads correctly on, so the chrome around
 *  it (not the art itself) can pick a readable side. */
enum class ArtTone { LIGHT, DARK, NEUTRAL }

/** A drawable surface referencing entities (§3) — a map with pins, a character sheet layout, a
 *  relationship graph, a cast strip. [elements] is the design-unit canvas (§4.1), the same model
 *  the widget maker uses — a fixed [com.betteraudio.companion.model.CANVAS_UNITS]-wide space,
 *  z-ordered by list order. Board *rendering* is P5 in the build phases; this shape is frozen now
 *  so sharing (P4) never has to ship a second pack format. */
data class PackBoard(
    val boardId: String,
    val kind: BoardKind,
    val presentation: Presentation,
    /** Shown in the deck's board switcher. Null for a pack authored before boards were nameable —
     *  the UI falls back to "Map", which is what a single unnamed board always was. */
    val title: String? = null,
    /**
     * Path relative to the pack's `media/` directory: the board's art *before any revision*.
     *
     * Kept as a plain field rather than folded into [artRevisions] because it is the one image
     * with no anchor — it is true from the first second of the book, so giving it a position on
     * the timeline would be inventing one. Every later state of the map is a revision.
     */
    val artMedia: String? = null,
    /** Later states of this board's art, each anchored on the timeline (§5). See [BoardArt]. */
    val artRevisions: List<BoardArt> = emptyList(),
    val artTone: ArtTone? = null,
    val elements: List<PackBoardElement> = emptyList(),
    val unknown: Map<String, Any?> = emptyMap()
)

/**
 * One timed state of a board's backdrop — the map after the war, after the flood, after the city
 * burns.
 *
 * Pins have always moved over the story, because a pin's position is a fact and facts are anchored.
 * The picture underneath them could not, which made the board a snapshot with moving parts on it.
 * A revision is the same idea applied to the art: an image plus the [anchor] it becomes true at,
 * resolved by the same [com.betteraudio.companion.AnchorResolver] and filtered against the same
 * reveal cursor, so a map the listener has not reached yet cannot be shown to them.
 *
 * This is a document field rather than a [PackFact] because a fact asserts a value *of an entity*,
 * and a board is not an entity. The reveal semantics are borrowed; the ownership is not.
 */
data class BoardArt(
    val artId: String,
    /** Path relative to the pack's `media/` directory. */
    val media: String,
    val anchor: FactAnchor,
    /** Optional author's caption — "After the Throne War". Shown in the deck's revision strip. */
    val label: String? = null,
    val unknown: Map<String, Any?> = emptyMap()
)

/** Either a semantic role resolved against the recipient's live theme (the default an author
 *  should reach for) or a raw ARGB value as the escape hatch for "this faction is literally red" —
 *  see §4.1's divergence from `WidgetDesignDoc`, which only has raw colors and is fine for that
 *  because a widget is designed against the user's own wallpaper, not shared. Exactly one of
 *  [role]/[raw] should be set; [role] wins if, invalidly, both are. */
data class ElementColor(
    val role: String? = null,
    val raw: Long? = null
)

/**
 * One placed board element, in design units (0..[CANVAS_UNITS] / [CANVAS_UNITS]/aspectRatio — see
 * `WidgetDesignDoc.CANVAS_UNITS`'s kdoc for why a fixed unit space beats normalized per-axis
 * fractions). [kind] is an open string rather than a closed enum on purpose: the element
 * vocabulary a map board needs (PIN, LABEL) differs from what a character sheet needs (PORTRAIT,
 * FIELD), and neither is built yet (P5) — a string lets P5 extend the vocabulary without a schema
 * migration, at the cost of validating known kinds only where they're actually interpreted.
 *
 * [entityRef]/[field] bind this element to a live entity value — a MAP pin's position tracks
 * [PackEntity]'s `location` field this way, a SHEET element tracks whichever field it displays —
 * so an element can render the CURRENT (as of the reveal cursor) value without itself carrying
 * per-timestamp state; the timestamps live on the [PackFact]s, not here.
 */
data class PackBoardElement(
    val id: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    val entityRef: String? = null,
    val field: String? = null,
    val fillColor: ElementColor? = null,
    val textColor: ElementColor? = null,
    val imagePath: String? = null,
    val text: String? = null,
    val unknown: Map<String, Any?> = emptyMap()
)

/** Design-unit canvas width; height is this / a board's own aspect ratio. Mirrors
 *  `com.betteraudio.widget.model.CANVAS_UNITS` exactly — kept as a separate constant (not a shared
 *  import) because the widget maker's canvas is a `widget/` concept and companion boards are a
 *  `companion/` one; the two are allowed to diverge later without becoming a cross-feature
 *  breaking change. */
const val CANVAS_UNITS = 1000f
