package com.betteraudio.widget.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Design-unit canvas: width is always [CANVAS_UNITS], height is [CANVAS_UNITS] / aspectRatio.
 *  Every element rect lives in this fixed space, so `w == h` is always a true square regardless
 *  of the design's aspect ratio or the real granted widget pixel size — one uniform scale maps
 *  design units to pixels at render time (see WidgetPainter.contentBox/unitScale). This replaces
 *  the old system's two-different-axis fractional coordinates, which needed a whole
 *  aspect-correction workaround (WidgetElement.effectiveRect) to keep icons square. */
const val CANVAS_UNITS = 1000f

@Serializable
data class WidgetDesignDoc(
    val schemaVersion: Int = 2,
    /** List order = z-order; last element is drawn (and tapped) on top. The widget itself is
     *  always a plain rectangle — a [ElementType.BACKGROUND_LAYER] element is an ordinary
     *  freely-placed/resized/rotated element like any other, just usually defaulted to full-bleed
     *  and the bottom of the stack when first added (see WidgetEditorViewModel.addElement). A
     *  design can have zero, one, or several. An empty list is a genuinely blank widget. */
    val elements: List<ElementSpec> = emptyList(),
)

enum class BgSource { BOOK_COVER, SERIES_COVER, CUSTOM_IMAGE, SOLID, GRADIENT, TRANSPARENT }

/** Fill style for a [ElementType.BACKGROUND_LAYER] element — cover/color/gradient fill plus
 *  dim/blur/opacity, and its own [shapeKind]/[cornerRadius] clipping only its own rect (never the
 *  whole widget bitmap, which is always a plain rectangle). */
@Serializable
data class BackgroundLayerStyle(
    val source: BgSource = BgSource.BOOK_COVER,
    val imagePath: String? = null,
    val color: Long = 0xFF1A1A22,
    val colorEnd: Long? = null,
    val gradientAngleDeg: Float = 90f,
    /** 0f..0.8f black overlay for text legibility over busy covers. */
    val dim: Float = 0f,
    /** Box-blur radius in design units (0 = off); see [com.betteraudio.widget.render.BlurUtil]. */
    val blurRadius: Float = 0f,
    val opacity: Float = 1f,
    val shapeKind: ShapeKind = ShapeKind.RECT,
    /** Corner radius in design units, used when [shapeKind] == RECT. */
    val cornerRadius: Float = 60f,
)

/** Every element type the widget editor can place. */
enum class ElementType {
    // Interactive controls — axis-aligned only, map to PlaybackService.ACTION_* (see WidgetIntents).
    PLAY_PAUSE, SKIP_FORWARD, SKIP_BACK, CHAPTER_FORWARD, CHAPTER_BACK,
    SPEED_UP, SPEED_DOWN, BOOST_UP, BOOST_DOWN, SLEEP_TIMER,
    QUICK_BOOKMARK, CLOSE_BOOK,
    // Text
    BOOK_TITLE, AUTHOR, CHAPTER_TITLE, SERIES_NAME, SPEED_LABEL,
    TIME_REMAINING_BOOK, TIME_REMAINING_CHAPTER, PROGRESS_PERCENT, CUSTOM_TEXT,
    // Images
    BOOK_COVER, SERIES_COVER, CUSTOM_IMAGE,
    // Shapes
    RECT, PROGRESS_BAR,
    // Background — a placeable fill/shape layer; see WidgetDesignDoc's elements-list doc comment.
    BACKGROUND_LAYER;

    val isControl: Boolean get() = this in CONTROL_TYPES
    val isText: Boolean get() = this in TEXT_TYPES
    val isImage: Boolean get() = this in IMAGE_TYPES
    val isShape: Boolean get() = this == RECT || this == PROGRESS_BAR
    val isBackgroundLayer: Boolean get() = this == BACKGROUND_LAYER
    /** Text/image/shape/background-layer elements can rotate; controls stay axis-aligned so tap
     *  mapping is exact. */
    val canRotate: Boolean get() = !isControl

    companion object {
        val CONTROL_TYPES = setOf(
            PLAY_PAUSE, SKIP_FORWARD, SKIP_BACK, CHAPTER_FORWARD, CHAPTER_BACK,
            SPEED_UP, SPEED_DOWN, BOOST_UP, BOOST_DOWN, SLEEP_TIMER,
            QUICK_BOOKMARK, CLOSE_BOOK
        )
        val TEXT_TYPES = setOf(
            BOOK_TITLE, AUTHOR, CHAPTER_TITLE, SERIES_NAME, SPEED_LABEL,
            TIME_REMAINING_BOOK, TIME_REMAINING_CHAPTER, PROGRESS_PERCENT, CUSTOM_TEXT
        )
        val IMAGE_TYPES = setOf(BOOK_COVER, SERIES_COVER, CUSTOM_IMAGE)
    }
}

/** Tap behavior for TEXT/IMAGE/SHAPE elements (controls have an intrinsic action instead). Lets
 *  a photo or text element be interactive on the real widget — e.g. tap the cover to play/pause,
 *  tap the title to open the player — per the "more interactivity" requirement for those types. */
enum class TapAction {
    NONE, OPEN_APP, OPEN_PLAYER, PLAY_PAUSE, SKIP_FORWARD, SKIP_BACK, NEXT_CHAPTER, PREV_CHAPTER
}

enum class ContainerShape { NONE, CIRCLE, ROUNDED, SQUIRCLE }
enum class HorizontalTextAlign { LEFT, CENTER, RIGHT }
enum class ImageFit { COVER, CONTAIN }
enum class ShapeKind { RECT, PILL, CIRCLE, SQUIRCLE }

/** How a [ElementType.PROGRESS_BAR] element renders: a straight bar, or progress sweeping around
 *  the perimeter of a ring/square/rounded-square outline. */
enum class ProgressShape { LINE, RING, SQUARE, ROUNDED_SQUARE }

/** What a [ElementType.PROGRESS_BAR] element's fraction tracks: the whole book, or just the
 *  current chapter (resets to 0 at each chapter boundary). */
enum class ProgressSource { BOOK, CHAPTER }

@Serializable
data class IconStyle(
    val glyphColor: Long = 0xFFFFFFFF,
    val glyphUsesAccent: Boolean = true,
    val container: ContainerShape = ContainerShape.NONE,
    val containerColor: Long = 0x33FFFFFF,
    val containerUsesAccent: Boolean = false,
)

@Serializable
data class TextStyle(
    /** Glyph height in design units — scales with the widget like everything else. */
    val sizeUnits: Float = 55f,
    val weight: Int = 600,
    val italic: Boolean = false,
    val color: Long = 0xFFFFFFFF,
    val usesAccent: Boolean = false,
    val align: HorizontalTextAlign = HorizontalTextAlign.LEFT,
    val maxLines: Int = 1,
    val shadow: Boolean = true,
)

@Serializable
data class ImageStyle(
    val fit: ImageFit = ImageFit.COVER,
    val cornerRadius: Float = 40f,
    val borderWidth: Float = 0f,
    val borderColor: Long = 0xFFFFFFFF,
    val shadow: Boolean = false,
    /** When set, resizing this element preserves this width/height ratio (e.g. 1f for a perfect
     *  square/circle) instead of resizing the two axes independently. Null = unlocked. */
    val lockedAspect: Float? = null,
)

@Serializable
data class ShapeStyle(
    val kind: ShapeKind = ShapeKind.RECT,
    val fillColor: Long = 0x66000000,
    val cornerRadius: Float = 30f,
    val trackColor: Long = 0x4DFFFFFF,
    val fillColorBar: Long = 0xFFFFFFFF,
    /** Only meaningful on a PROGRESS_BAR element. */
    val progressShape: ProgressShape = ProgressShape.LINE,
    /** Stroke width in design units, used by RING/SQUARE/ROUNDED_SQUARE progress shapes. */
    val strokeWidth: Float = 24f,
    /** Only meaningful on a PROGRESS_BAR element. Defaulting to BOOK keeps every design saved
     *  before this field existed rendering exactly as before (opaque JSON field, no migration). */
    val progressSource: ProgressSource = ProgressSource.BOOK,
)

/**
 * One placed element. Position/size are in design units (0..CANVAS_UNITS / CANVAS_UNITS/aspect),
 * NOT normalized fractions of two different axes — see [CANVAS_UNITS]. [id] is a stable UUID used
 * for undo/selection identity and for scoping PendingIntents (WidgetIntents), so two elements of
 * the same type on one design (or the same design placed twice) never collide.
 */
@Serializable
data class ElementSpec(
    val id: String = UUID.randomUUID().toString(),
    val type: ElementType,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float = 0f,
    val opacity: Float = 1f,
    val icon: IconStyle? = null,
    val text: TextStyle? = null,
    val image: ImageStyle? = null,
    val shape: ShapeStyle? = null,
    val backgroundLayer: BackgroundLayerStyle? = null,
    val sleepDurationMs: Long? = null,
    val showCountdown: Boolean = true,
    val customText: String? = null,
    val imagePath: String? = null,
    val tapAction: TapAction = TapAction.NONE,
)
