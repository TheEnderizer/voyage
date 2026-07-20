package com.betteraudio.widget.model

import com.betteraudio.util.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** JSON (de)serialization for a design document, mirroring ThemeSeedPaletteCodec's usage of
 *  kotlinx.serialization. Optional style blocks on ElementSpec (rather than sealed polymorphism)
 *  keep this forward-compatible as styling grows — ignoreUnknownKeys lets an older app read a doc
 *  saved by a newer one, dropping fields it doesn't understand instead of failing to parse. */
object WidgetDesignCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(doc: WidgetDesignDoc): String = json.encodeToString(doc)

    /** A doc saved before the background-as-element change has a top-level "background" object
     *  that the current [WidgetDesignDoc] shape no longer has a field for — ignoreUnknownKeys would
     *  silently drop it, losing the design's whole look. Detect it and migrate into a synthetic
     *  BACKGROUND_LAYER element at index 0 instead, so old designs render pixel-identical. */
    fun decode(text: String?): WidgetDesignDoc {
        if (text.isNullOrBlank()) return WidgetDesignDoc()
        return try {
            val root = json.parseToJsonElement(text)
            if (root is JsonObject && root.containsKey("background")) {
                migrateLegacy(root)
            } else {
                json.decodeFromString<WidgetDesignDoc>(text)
            }
        } catch (e: Exception) {
            AppLog.e("Widget", "failed to decode design document, falling back to empty", e)
            WidgetDesignDoc()
        }
    }

    private fun migrateLegacy(root: JsonObject): WidgetDesignDoc {
        val legacy = json.decodeFromJsonElement<LegacyWidgetDesignDoc>(root)
        val bg = legacy.background
        val backgroundElement = ElementSpec(
            type = ElementType.BACKGROUND_LAYER,
            x = 0f, y = 0f, w = CANVAS_UNITS, h = CANVAS_UNITS,
            backgroundLayer = BackgroundLayerStyle(
                source = bg.source,
                imagePath = bg.imagePath,
                color = bg.color,
                colorEnd = bg.colorEnd,
                gradientAngleDeg = bg.gradientAngleDeg,
                dim = bg.dim,
                blurRadius = bg.blurRadius,
                opacity = bg.opacity,
                shapeKind = ShapeKind.RECT,
                cornerRadius = bg.cornerRadius,
            ),
        )
        return WidgetDesignDoc(elements = listOf(backgroundElement) + legacy.elements)
    }

    /** Mirrors the pre-migration WidgetDesignDoc/BackgroundSpec shape — read-only, used solely to
     *  parse old saved designs during [migrateLegacy]. Never written. */
    @Serializable
    private data class LegacyBackgroundSpec(
        val source: BgSource = BgSource.BOOK_COVER,
        val imagePath: String? = null,
        val color: Long = 0xFF1A1A22,
        val colorEnd: Long? = null,
        val gradientAngleDeg: Float = 90f,
        val dim: Float = 0f,
        val blurRadius: Float = 0f,
        val cornerRadius: Float = 60f,
        val opacity: Float = 1f,
    )

    @Serializable
    private data class LegacyWidgetDesignDoc(
        val schemaVersion: Int = 1,
        val background: LegacyBackgroundSpec = LegacyBackgroundSpec(),
        val elements: List<ElementSpec> = emptyList(),
    )
}
