package com.betteraudio.widget.model

import com.betteraudio.util.AppLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON (de)serialization for a design document, mirroring ThemeSeedPaletteCodec's usage of
 *  kotlinx.serialization. Optional style blocks on ElementSpec (rather than sealed polymorphism)
 *  keep this forward-compatible as styling grows — ignoreUnknownKeys lets an older app read a doc
 *  saved by a newer one, dropping fields it doesn't understand instead of failing to parse. */
object WidgetDesignCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(doc: WidgetDesignDoc): String = json.encodeToString(doc)

    fun decode(text: String?): WidgetDesignDoc {
        if (text.isNullOrBlank()) return WidgetDesignDoc()
        return try {
            json.decodeFromString<WidgetDesignDoc>(text)
        } catch (e: Exception) {
            AppLog.e("Widget", "failed to decode design document, falling back to empty", e)
            WidgetDesignDoc()
        }
    }
}
