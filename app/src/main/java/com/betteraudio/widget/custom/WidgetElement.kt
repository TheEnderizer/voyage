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

/** Canvas width/height ratio this bucket renders at (both in the editor preview, via
 *  `Modifier.aspectRatio`, and on the real widget, whose granted pixel size targets this same
 *  ratio) — lets an icon-type [WidgetElement] pick a normalized `w`/`h` pair that comes out truly
 *  square in actual pixels, since `w` and `h` are fractions of two axes of different pixel length. */
val WidgetSizeBucket.aspect: Float get() = cellsW.toFloat() / cellsH.toFloat()

/** Derives a `w`/`h` pair that renders as a true square in actual pixels for a canvas of the given
 *  [aspect] (width/height ratio), given a desired square side as a fraction of the canvas HEIGHT.
 *  Since `w` and `h` are each a fraction of a *different* axis (and those axes differ in pixel
 *  length on WIDE/TALL buckets), `w == h` alone does not draw a square — this does, because
 *  `w * (aspect * canvasHeightPx) == h * canvasHeightPx` when `w = h / aspect`. */
fun squareWidthHeight(aspect: Float, heightFrac: Float): Pair<Float, Float> {
    val h = heightFrac.coerceIn(0.05f, 1f)
    val w = (h / aspect).coerceIn(0.05f, 1f)
    return w to h
}

/**
 * The rect this element actually occupies: for icon-type ([WidgetElementType.isInteractive])
 * elements, re-derives a bucket-aspect-corrected SQUARE around the stored rect's own center via
 * [squareWidthHeight], instead of trusting the stored `w`/`h` directly.
 *
 * This matters for elements saved BEFORE the square-icon fix (or from an older app version): their
 * stored footprint can be a much larger, non-square rectangle than the icon actually drawn inside
 * it (icons render at `min(w,h)`, centered) — which on the real widget means the invisible
 * tap-target cells assigned to that button (see `WidgetGrid.colRange`/`rowRange` in
 * `CustomWidgetProvider`) can extend well past the visible glyph and overlap space a neighboring
 * element visually occupies, so tapping what looks like a different button can silently fire
 * this one's action instead — a very plausible explanation for a "sudden, unreproducible skip"
 * bug report. Deriving the effective rect here — and using it everywhere an element's rect
 * matters (render, tap-cell mapping, editor preview) — fixes existing saved designs transparently,
 * with no migration needed. Text/image elements are returned as-is (their rect legitimately IS a
 * rectangle, not meant to be square).
 *
 * [aspect] is the canvas width/height ratio to correct against — pass the REAL granted pixel
 * aspect (`pxW.toFloat() / pxH`) when it's known (rendering/tap-mapping the actual widget), since
 * that can differ slightly from the bucket's nominal [WidgetSizeBucket.aspect] depending on what
 * the launcher actually grants; fall back to the nominal bucket aspect only where no real pixel
 * size exists yet (the editor canvas, which is itself drawn at the nominal ratio).
 */
fun WidgetElement.effectiveRect(aspect: Float): FloatArray {
    if (!type.isInteractive) return floatArrayOf(x, y, w, h)
    val cx = x + w / 2f
    val cy = y + h / 2f
    val (newW, newH) = squareWidthHeight(aspect, h)
    val nx = (cx - newW / 2f).coerceIn(0f, 1f - newW)
    val ny = (cy - newH / 2f).coerceIn(0f, 1f - newH)
    return floatArrayOf(nx, ny, newW, newH)
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
