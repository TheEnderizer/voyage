package com.betteraudio.widget.custom

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Home-screen footprint bucket a custom widget design targets. */
enum class WidgetSizeBucket(val minWidthDp: Int, val minHeightDp: Int, val cellsW: Int, val cellsH: Int) {
    SMALL(110, 110, 2, 2),
    WIDE(250, 110, 4, 2),
    TALL(110, 250, 2, 4),
    LARGE(250, 250, 4, 4)
}

/** Every element type the widget editor can place. */
enum class WidgetElementType {
    PLAY_PAUSE, SKIP_FORWARD, SKIP_BACK, CHAPTER_FORWARD, CHAPTER_BACK,
    SPEED_UP, SPEED_DOWN, BOOST_UP, BOOST_DOWN, SLEEP_TIMER,
    QUICK_BOOKMARK, BOOK_NAME, AUTHOR_NAME, CHAPTER_NAME, SERIES_NAME,
    BOOK_COVER, SERIES_COVER, CUSTOM_IMAGE, CLOSE_BOOK;

    val isText: Boolean get() = this in TEXT_TYPES
    val isInteractive: Boolean get() = this in INTERACTIVE_TYPES
    val isImage: Boolean get() = this == BOOK_COVER || this == SERIES_COVER || this == CUSTOM_IMAGE

    companion object {
        val TEXT_TYPES = setOf(BOOK_NAME, AUTHOR_NAME, CHAPTER_NAME, SERIES_NAME)
        val INTERACTIVE_TYPES = setOf(
            PLAY_PAUSE, SKIP_FORWARD, SKIP_BACK, CHAPTER_FORWARD, CHAPTER_BACK,
            SPEED_UP, SPEED_DOWN, BOOST_UP, BOOST_DOWN, SLEEP_TIMER,
            QUICK_BOOKMARK, CLOSE_BOOK
        )
    }
}

enum class WidgetBackground { BOOK_COVER, SERIES_COVER, CUSTOM_IMAGE, APP_COLOR, CUSTOM_COLOR }

/**
 * One placed element. Position/size are normalized to the widget canvas (0f..1f) so the same
 * design renders correctly at any placed pixel size.
 */
@Serializable
data class WidgetElement(
    val type: WidgetElementType,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val fontSizeSp: Float? = null,
    val imagePath: String? = null,
    val durationMs: Long? = null
)

/** JSON (de)serialization for the element list, mirroring ThemeSeedPaletteCodec's usage of kotlinx.serialization. */
object WidgetElementCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(elements: List<WidgetElement>): String = json.encodeToString(elements)

    fun decode(text: String?): List<WidgetElement> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<WidgetElement>>(text)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
