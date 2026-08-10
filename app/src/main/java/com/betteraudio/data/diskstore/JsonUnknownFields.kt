package com.betteraudio.data.diskstore

import org.json.JSONObject

/**
 * Forward-compatibility helper for the disk document codecs: captures every key in a JSONObject
 * NOT in [known] so a newer build's extra fields survive an older build round-tripping the file
 * (decode -> encode) instead of being silently dropped. Values are left as whatever org.json
 * handed back (String, Number, Boolean, JSONObject, JSONArray, or the JSONObject.NULL sentinel
 * for a JSON null) — never interpreted, just carried through unchanged.
 */
internal fun JSONObject.captureUnknown(known: Set<String>): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    keys().forEach { key -> if (key !in known) result[key] = get(key) }
    return result
}

/** Re-injects a captured-unknown map on encode. Never overwrites a key the encoder already wrote
 *  — the current build's own fields always win over a stale unknown-field snapshot. */
internal fun JSONObject.putUnknown(unknown: Map<String, Any?>) {
    unknown.forEach { (key, value) -> if (!has(key)) put(key, value ?: JSONObject.NULL) }
}
