package com.betteraudio.companion.model

import com.betteraudio.data.diskstore.captureUnknown
import com.betteraudio.data.diskstore.putUnknown
import org.json.JSONObject
import java.util.UUID

/** org.json <-> [PackEditsDoc] conversion, same rules as [CompanionPackCodec]: hand-rolled
 *  org.json, forward-compatible via unknown-field capture, ids generated on decode if missing. */
object CompanionEditsCodec {

    private const val TYPE_KEY = "type"
    private val TOP_KEYS = setOf("schemaVersion", "baseRevision", "ops")

    fun encode(doc: PackEditsDoc): JSONObject = JSONObject().apply {
        put("schemaVersion", doc.schemaVersion)
        put("baseRevision", doc.baseRevision)
        put("ops", org.json.JSONArray().apply { doc.ops.forEach { put(encodeOp(it)) } })
        putUnknown(doc.unknown)
    }

    fun encodeToString(doc: PackEditsDoc, indent: Int = 2): String = encode(doc).toString(indent)

    fun decodeOrNull(text: String): PackEditsDoc? = runCatching { decode(JSONObject(text)) }.getOrNull()

    private fun decode(root: JSONObject): PackEditsDoc {
        val ops = root.optJSONArray("ops")?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decodeOp) }
        } ?: emptyList()
        return PackEditsDoc(
            schemaVersion = root.optInt("schemaVersion", PackEditsDoc.CURRENT_SCHEMA_VERSION),
            baseRevision = root.optInt("baseRevision", 1),
            ops = ops,
            unknown = root.captureUnknown(TOP_KEYS)
        )
    }

    private fun encodeOp(op: PackEditOp): JSONObject = JSONObject().apply {
        put("opId", op.opId)
        when (op) {
            is PackEditOp.AddEntity -> { put(TYPE_KEY, "addEntity"); put("entity", encodeEntity(op.entity)) }
            is PackEditOp.EditEntity -> { put(TYPE_KEY, "editEntity"); put("entityId", op.entityId); put("fields", JSONObject(op.fields)); put("rev", op.rev) }
            is PackEditOp.DeleteEntity -> { put(TYPE_KEY, "deleteEntity"); put("entityId", op.entityId); put("rev", op.rev) }
            is PackEditOp.HideEntity -> { put(TYPE_KEY, "hideEntity"); put("entityId", op.entityId) }
            is PackEditOp.AddFact -> { put(TYPE_KEY, "addFact"); put("fact", encodeFact(op.fact)) }
            is PackEditOp.EditFact -> { put(TYPE_KEY, "editFact"); put("factId", op.factId); put("fields", JSONObject(op.fields)); put("rev", op.rev) }
            is PackEditOp.DeleteFact -> { put(TYPE_KEY, "deleteFact"); put("factId", op.factId); put("rev", op.rev) }
            is PackEditOp.EditBoard -> { put(TYPE_KEY, "editBoard"); put("boardId", op.boardId); put("fields", JSONObject(op.fields)); put("rev", op.rev) }
            is PackEditOp.AddElement -> { put(TYPE_KEY, "addElement"); put("boardId", op.boardId); put("element", encodeElement(op.element)) }
            is PackEditOp.EditElement -> { put(TYPE_KEY, "editElement"); put("boardId", op.boardId); put("elementId", op.elementId); put("fields", JSONObject(op.fields)) }
            is PackEditOp.DeleteElement -> { put(TYPE_KEY, "deleteElement"); put("boardId", op.boardId); put("elementId", op.elementId) }
        }
    }

    private fun decodeOp(o: JSONObject): PackEditOp? {
        val opId = o.optString("opId").ifBlank { UUID.randomUUID().toString() }
        return when (o.optString(TYPE_KEY)) {
            "addEntity" -> o.optJSONObject("entity")?.let { PackEditOp.AddEntity(opId, decodeEntity(it)) }
            "editEntity" -> PackEditOp.EditEntity(opId, o.optString("entityId"), o.optJSONObject("fields").toMapOrEmpty(), o.optInt("rev", 1))
            "deleteEntity" -> PackEditOp.DeleteEntity(opId, o.optString("entityId"), o.optInt("rev", 1))
            "hideEntity" -> PackEditOp.HideEntity(opId, o.optString("entityId"))
            "addFact" -> o.optJSONObject("fact")?.let { PackEditOp.AddFact(opId, decodeFact(it) ?: return null) }
            "editFact" -> PackEditOp.EditFact(opId, o.optString("factId"), o.optJSONObject("fields").toMapOrEmpty(), o.optInt("rev", 1))
            "deleteFact" -> PackEditOp.DeleteFact(opId, o.optString("factId"), o.optInt("rev", 1))
            "editBoard" -> PackEditOp.EditBoard(opId, o.optString("boardId"), o.optJSONObject("fields").toMapOrEmpty(), o.optInt("rev", 1))
            "addElement" -> o.optJSONObject("element")?.let { PackEditOp.AddElement(opId, o.optString("boardId"), decodeElement(it)) }
            "editElement" -> PackEditOp.EditElement(opId, o.optString("boardId"), o.optString("elementId"), o.optJSONObject("fields").toMapOrEmpty())
            "deleteElement" -> PackEditOp.DeleteElement(opId, o.optString("boardId"), o.optString("elementId"))
            else -> null
        }
    }

    // Reuses the exact field sets CompanionPackCodec uses so an AddEntity/AddFact/AddElement op
    // round-trips its payload identically to how it would appear in pack.json itself.
    private fun encodeEntity(e: PackEntity): JSONObject = JSONObject().apply {
        put("entityId", e.entityId); put("kind", e.kind.name); put("name", e.name); put("media", e.media)
    }

    private fun decodeEntity(o: JSONObject): PackEntity = PackEntity(
        entityId = o.optString("entityId").ifBlank { UUID.randomUUID().toString() },
        kind = runCatching { EntityKind.valueOf(o.optString("kind")) }.getOrDefault(EntityKind.CONCEPT),
        name = o.optString("name"),
        media = if (o.has("media") && !o.isNull("media")) o.optString("media") else null
    )

    private fun encodeFact(f: PackFact): JSONObject = JSONObject().apply {
        put("factId", f.factId); put("entityId", f.entityId); put("field", f.field); put("value", f.value)
        put("anchor", JSONObject().apply {
            put("bookRef", f.anchor.bookRef)
            put("fileKey", f.anchor.fileKey); put("offsetMs", f.anchor.offsetMs)
            put("globalMs", f.anchor.globalMs); put("chapter", f.anchor.chapter)
            put("chapterOffsetMs", f.anchor.chapterOffsetMs); put("quote", f.anchor.quote); put("ratio", f.anchor.ratio)
        })
        put("storyAt", f.storyAt); put("importance", f.importance.name); put("origin", f.origin); put("rev", f.rev)
    }

    private fun decodeFact(o: JSONObject): PackFact? {
        val a = o.optJSONObject("anchor") ?: return null
        return PackFact(
            factId = o.optString("factId").ifBlank { UUID.randomUUID().toString() },
            entityId = o.optString("entityId"),
            field = o.optString("field"),
            value = o.optString("value"),
            anchor = FactAnchor(
                bookRef = a.optString("bookRef"),
                fileKey = if (a.has("fileKey") && !a.isNull("fileKey")) a.optString("fileKey") else null,
                offsetMs = if (a.has("offsetMs") && !a.isNull("offsetMs")) a.optLong("offsetMs") else null,
                globalMs = if (a.has("globalMs") && !a.isNull("globalMs")) a.optLong("globalMs") else null,
                chapter = if (a.has("chapter") && !a.isNull("chapter")) a.optInt("chapter") else null,
                chapterOffsetMs = if (a.has("chapterOffsetMs") && !a.isNull("chapterOffsetMs")) a.optLong("chapterOffsetMs") else null,
                quote = if (a.has("quote") && !a.isNull("quote")) a.optString("quote") else null,
                ratio = if (a.has("ratio") && !a.isNull("ratio")) a.optDouble("ratio").toFloat() else null
            ),
            storyAt = if (o.has("storyAt") && !o.isNull("storyAt")) o.optLong("storyAt") else null,
            importance = runCatching { Importance.valueOf(o.optString("importance")) }.getOrDefault(Importance.NOTABLE),
            origin = if (o.has("origin") && !o.isNull("origin")) o.optString("origin") else null,
            rev = o.optInt("rev", 1)
        )
    }

    private fun encodeElement(e: PackBoardElement): JSONObject = JSONObject().apply {
        put("id", e.id); put("kind", e.kind)
        put("x", e.x); put("y", e.y); put("w", e.w); put("h", e.h)
        put("rotationDeg", e.rotationDeg); put("opacity", e.opacity)
        put("entityRef", e.entityRef); put("field", e.field)
        put("imagePath", e.imagePath); put("text", e.text)
    }

    private fun decodeElement(o: JSONObject): PackBoardElement = PackBoardElement(
        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
        kind = o.optString("kind"),
        x = o.optDouble("x").toFloat(), y = o.optDouble("y").toFloat(),
        w = o.optDouble("w").toFloat(), h = o.optDouble("h").toFloat(),
        rotationDeg = o.optDouble("rotationDeg", 0.0).toFloat(),
        opacity = o.optDouble("opacity", 1.0).toFloat(),
        entityRef = if (o.has("entityRef") && !o.isNull("entityRef")) o.optString("entityRef") else null,
        field = if (o.has("field") && !o.isNull("field")) o.optString("field") else null,
        imagePath = if (o.has("imagePath") && !o.isNull("imagePath")) o.optString("imagePath") else null,
        text = if (o.has("text") && !o.isNull("text")) o.optString("text") else null
    )

    private fun JSONObject?.toMapOrEmpty(): Map<String, Any?> {
        if (this == null) return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        keys().forEach { k -> result[k] = if (isNull(k)) null else get(k) }
        return result
    }
}
