package com.betteraudio.companion.model

import com.betteraudio.data.diskstore.captureUnknown
import com.betteraudio.data.diskstore.putUnknown
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * org.json <-> [CompanionPackDoc] conversion — hand-rolled, not kotlinx-serialization, same
 * choice as every other on-disk document in this codebase (`BookDataCodec`, `LibraryDataCodec`,
 * `WidgetsDataCodec`), which is also what makes `pack.json` a format the BetterAudioAlign PC tool
 * can target directly with its own JSON writer (docs/companion-packs.md §13) rather than needing
 * a documented spec separate from this implementation.
 *
 * Every id is generated on decode if missing/blank rather than left null — see
 * [CompanionPackDoc]'s kdoc on why a fact/entity/board id must always exist and must always be a
 * UUID, never a Room row id.
 */
object CompanionPackCodec {

    private val TOP_KEYS = setOf(
        "schemaVersion", "packId", "revision", "scope", "title", "authorHandle", "derivedFrom",
        "payload", "members", "entities", "facts", "boards"
    )
    private val MEMBER_KEYS = setOf("bookRef", "title", "author", "seriesOrder", "durationMs", "fileCount", "fileKeys")
    private val ENTITY_KEYS = setOf("entityId", "kind", "name", "media")
    private val ANCHOR_KEYS = setOf("bookRef", "fileKey", "offsetMs", "globalMs", "chapter", "chapterOffsetMs", "quote", "ratio")
    private val FACT_KEYS = setOf("factId", "entityId", "field", "value", "anchor", "storyAt", "importance", "origin", "rev")
    private val BOARD_KEYS = setOf(
        "boardId", "kind", "presentation", "title", "artMedia", "artRevisions", "artTone", "elements"
    )
    private val BOARD_ART_KEYS = setOf("artId", "media", "anchor", "label")
    private val ELEMENT_KEYS = setOf("id", "kind", "x", "y", "w", "h", "rotationDeg", "opacity", "entityRef", "field", "fillColor", "textColor", "imagePath", "text")
    private val COLOR_KEYS = setOf("role", "raw")

    fun encode(doc: CompanionPackDoc): JSONObject = JSONObject().apply {
        put("schemaVersion", doc.schemaVersion)
        put("packId", doc.packId)
        put("revision", doc.revision)
        put("scope", doc.scope.name)
        put("title", doc.title)
        put("authorHandle", doc.authorHandle)
        put("derivedFrom", doc.derivedFrom?.let { d ->
            JSONObject().apply {
                put("packId", d.packId)
                put("revision", d.revision)
                put("authorHandle", d.authorHandle)
            }
        })
        put("payload", doc.payload.name)
        put("members", JSONArray().apply { doc.members.forEach { put(encodeMember(it)) } })
        put("entities", JSONArray().apply { doc.entities.forEach { put(encodeEntity(it)) } })
        put("facts", JSONArray().apply { doc.facts.forEach { put(encodeFact(it)) } })
        put("boards", JSONArray().apply { doc.boards.forEach { put(encodeBoard(it)) } })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: CompanionPackDoc, indent: Int = 2): String = encode(doc).toString(indent)

    fun decodeOrNull(text: String): CompanionPackDoc? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): CompanionPackDoc {
        val members = root.optJSONArray("members")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeMember) }
        } ?: emptyList()

        val entities = root.optJSONArray("entities")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeEntity) }
        } ?: emptyList()

        val facts = root.optJSONArray("facts")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeFact) }
        } ?: emptyList()

        val boards = root.optJSONArray("boards")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeBoard) }
        } ?: emptyList()

        return CompanionPackDoc(
            schemaVersion = root.optInt("schemaVersion", CompanionPackDoc.CURRENT_SCHEMA_VERSION),
            packId = root.optString("packId").ifBlank { UUID.randomUUID().toString() },
            revision = root.optInt("revision", 1),
            scope = root.optString("scope").toPackScopeOrDefault(),
            title = root.optString("title"),
            authorHandle = root.optStringOrNull("authorHandle"),
            derivedFrom = root.optJSONObject("derivedFrom")?.let { d ->
                DerivedFrom(
                    packId = d.optString("packId"),
                    revision = d.optInt("revision", 1),
                    authorHandle = d.optStringOrNull("authorHandle")
                )
            },
            payload = root.optString("payload").toPackPayloadOrDefault(),
            members = members,
            entities = entities,
            facts = facts,
            boards = boards,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }

    // ── members ────────────────────────────────────────────────────────────
    private fun encodeMember(m: PackMember): JSONObject = JSONObject().apply {
        put("bookRef", m.bookRef)
        put("title", m.title)
        put("author", m.author)
        put("seriesOrder", m.seriesOrder)
        put("durationMs", m.durationMs)
        put("fileCount", m.fileCount)
        put("fileKeys", JSONArray(m.fileKeys))
        putUnknown(m.unknown)
    }

    private fun decodeMember(o: JSONObject): PackMember = PackMember(
        bookRef = o.optString("bookRef"),
        title = o.optString("title"),
        author = o.optString("author"),
        seriesOrder = if (o.has("seriesOrder") && !o.isNull("seriesOrder")) o.optDouble("seriesOrder").toFloat() else null,
        durationMs = o.optLong("durationMs"),
        fileCount = o.optInt("fileCount"),
        fileKeys = o.optJSONArray("fileKeys")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
        unknown = o.captureUnknown(MEMBER_KEYS)
    )

    // ── entities ───────────────────────────────────────────────────────────
    private fun encodeEntity(e: PackEntity): JSONObject = JSONObject().apply {
        put("entityId", e.entityId)
        put("kind", e.kind.name)
        put("name", e.name)
        put("media", e.media)
        putUnknown(e.unknown)
    }

    private fun decodeEntity(o: JSONObject): PackEntity = PackEntity(
        entityId = o.optString("entityId").ifBlank { UUID.randomUUID().toString() },
        kind = o.optString("kind").toEntityKindOrDefault(),
        name = o.optString("name"),
        media = o.optStringOrNull("media"),
        unknown = o.captureUnknown(ENTITY_KEYS)
    )

    // ── facts / anchors ────────────────────────────────────────────────────
    private fun encodeAnchor(a: FactAnchor): JSONObject = JSONObject().apply {
        put("bookRef", a.bookRef)
        put("fileKey", a.fileKey)
        put("offsetMs", a.offsetMs)
        put("globalMs", a.globalMs)
        put("chapter", a.chapter)
        put("chapterOffsetMs", a.chapterOffsetMs)
        put("quote", a.quote)
        put("ratio", a.ratio)
        putUnknown(a.unknown)
    }

    private fun decodeAnchor(o: JSONObject): FactAnchor = FactAnchor(
        bookRef = o.optString("bookRef"),
        fileKey = o.optStringOrNull("fileKey"),
        offsetMs = o.optLongOrNull("offsetMs"),
        globalMs = o.optLongOrNull("globalMs"),
        chapter = o.optIntOrNull("chapter"),
        chapterOffsetMs = o.optLongOrNull("chapterOffsetMs"),
        quote = o.optStringOrNull("quote"),
        ratio = o.optFloatOrNull("ratio"),
        unknown = o.captureUnknown(ANCHOR_KEYS)
    )

    private fun encodeFact(f: PackFact): JSONObject = JSONObject().apply {
        put("factId", f.factId)
        put("entityId", f.entityId)
        put("field", f.field)
        put("value", f.value)
        put("anchor", encodeAnchor(f.anchor))
        put("storyAt", f.storyAt)
        put("importance", f.importance.name)
        put("origin", f.origin)
        put("rev", f.rev)
        putUnknown(f.unknown)
    }

    private fun decodeFact(o: JSONObject): PackFact? {
        val anchorObj = o.optJSONObject("anchor") ?: return null
        return PackFact(
            factId = o.optString("factId").ifBlank { UUID.randomUUID().toString() },
            entityId = o.optString("entityId"),
            field = o.optString("field"),
            value = o.optString("value"),
            anchor = decodeAnchor(anchorObj),
            storyAt = o.optLongOrNull("storyAt"),
            importance = o.optString("importance").toImportanceOrDefault(),
            origin = o.optStringOrNull("origin"),
            rev = o.optInt("rev", 1),
            unknown = o.captureUnknown(FACT_KEYS)
        )
    }

    // ── boards / elements ──────────────────────────────────────────────────
    private fun encodeColor(c: ElementColor): JSONObject = JSONObject().apply {
        put("role", c.role)
        put("raw", c.raw)
    }

    private fun decodeColor(o: JSONObject): ElementColor = ElementColor(
        role = o.optStringOrNull("role"),
        raw = o.optLongOrNull("raw")
    )

    private fun encodeElement(e: PackBoardElement): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("kind", e.kind)
        put("x", e.x); put("y", e.y); put("w", e.w); put("h", e.h)
        put("rotationDeg", e.rotationDeg)
        put("opacity", e.opacity)
        put("entityRef", e.entityRef)
        put("field", e.field)
        put("fillColor", e.fillColor?.let(::encodeColor))
        put("textColor", e.textColor?.let(::encodeColor))
        put("imagePath", e.imagePath)
        put("text", e.text)
        putUnknown(e.unknown)
    }

    private fun decodeElement(o: JSONObject): PackBoardElement = PackBoardElement(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        kind = o.optString("kind"),
        x = o.optDouble("x").toFloat(),
        y = o.optDouble("y").toFloat(),
        w = o.optDouble("w").toFloat(),
        h = o.optDouble("h").toFloat(),
        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat(),
        opacity = o.optDouble("opacity", 1.0).toFloat(),
        entityRef = o.optStringOrNull("entityRef"),
        field = o.optStringOrNull("field"),
        fillColor = o.optJSONObject("fillColor")?.let(::decodeColor),
        textColor = o.optJSONObject("textColor")?.let(::decodeColor),
        imagePath = o.optStringOrNull("imagePath"),
        text = o.optStringOrNull("text"),
        unknown = o.captureUnknown(ELEMENT_KEYS)
    )

    private fun encodeBoardArt(a: BoardArt): JSONObject = JSONObject().apply {
        put("artId", a.artId)
        put("media", a.media)
        put("anchor", encodeAnchor(a.anchor))
        put("label", a.label)
        putUnknown(a.unknown)
    }

    private fun decodeBoardArt(o: JSONObject): BoardArt? {
        val anchorObj = o.optJSONObject("anchor") ?: return null
        val media = o.optStringOrNull("media") ?: return null
        return BoardArt(
            artId = o.optString("artId").ifBlank { UUID.randomUUID().toString() },
            media = media,
            anchor = decodeAnchor(anchorObj),
            label = o.optStringOrNull("label"),
            unknown = o.captureUnknown(BOARD_ART_KEYS)
        )
    }

    private fun encodeBoard(b: PackBoard): JSONObject = JSONObject().apply {
        put("boardId", b.boardId)
        put("kind", b.kind.name)
        put("presentation", b.presentation.name)
        put("title", b.title)
        put("artMedia", b.artMedia)
        put("artRevisions", JSONArray().apply { b.artRevisions.forEach { put(encodeBoardArt(it)) } })
        put("artTone", b.artTone?.name)
        put("elements", JSONArray().apply { b.elements.forEach { put(encodeElement(it)) } })
        putUnknown(b.unknown)
    }

    private fun decodeBoard(o: JSONObject): PackBoard = PackBoard(
        boardId = o.optString("boardId").ifBlank { UUID.randomUUID().toString() },
        kind = o.optString("kind").toBoardKindOrDefault(),
        presentation = o.optString("presentation").toPresentationOrDefault(),
        title = o.optStringOrNull("title"),
        artMedia = o.optStringOrNull("artMedia"),
        artRevisions = o.optJSONArray("artRevisions")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeBoardArt) }
        } ?: emptyList(),
        artTone = o.optStringOrNull("artTone")?.let { runCatching { ArtTone.valueOf(it) }.getOrNull() },
        elements = o.optJSONArray("elements")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeElement) }
        } ?: emptyList(),
        unknown = o.captureUnknown(BOARD_KEYS)
    )

    // ── enum coercion: an unrecognised/missing value falls back to a safe default rather than
    //    throwing, so one bad field never fails the whole pack decode ─────────────────────────
    private fun String.toPackScopeOrDefault() = runCatching { PackScope.valueOf(this) }.getOrDefault(PackScope.BOOK)
    private fun String.toPackPayloadOrDefault() = runCatching { PackPayload.valueOf(this) }.getOrDefault(PackPayload.DATA_ONLY)
    private fun String.toEntityKindOrDefault() = runCatching { EntityKind.valueOf(this) }.getOrDefault(EntityKind.CONCEPT)
    private fun String.toImportanceOrDefault() = runCatching { Importance.valueOf(this) }.getOrDefault(Importance.NOTABLE)
    private fun String.toBoardKindOrDefault() = runCatching { BoardKind.valueOf(this) }.getOrDefault(BoardKind.CAST)
    private fun String.toPresentationOrDefault() = runCatching { Presentation.valueOf(this) }.getOrDefault(Presentation.SHEET)

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optFloatOrNull(key: String): Float? =
        if (has(key) && !isNull(key)) optDouble(key).toFloat() else null
}
