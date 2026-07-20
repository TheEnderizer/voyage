package com.betteraudio.ui.widget

import com.betteraudio.widget.model.BackgroundLayerStyle
import com.betteraudio.widget.model.CANVAS_UNITS
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.IconStyle
import com.betteraudio.widget.model.ImageStyle
import com.betteraudio.widget.model.ShapeStyle
import com.betteraudio.widget.model.TextStyle
import com.betteraudio.widget.model.WidgetDesignDoc

/** A new design starts genuinely blank — no default background, no starter elements. The user's
 *  first step is deliberately adding a Background layer from the element picker, then building up
 *  from there; [aspect] only affects how later default-sized elements are centered. */
fun starterWidgetDesignDoc(aspect: Float = 2f): WidgetDesignDoc = WidgetDesignDoc(elements = emptyList())

fun defaultElementFor(type: ElementType, aspectRatio: Float): ElementSpec {
    val canvasH = CANVAS_UNITS / aspectRatio
    val cx = CANVAS_UNITS / 2f
    val cy = canvasH / 2f
    return when {
        type == ElementType.BACKGROUND_LAYER -> ElementSpec(
            type = type, x = 0f, y = 0f, w = CANVAS_UNITS, h = canvasH, backgroundLayer = BackgroundLayerStyle(),
        )
        type.isControl -> ElementSpec(type = type, x = cx - 55f, y = cy - 55f, w = 110f, h = 110f, icon = IconStyle())
        type.isText -> ElementSpec(
            type = type, x = 40f, y = 40f, w = 500f, h = 90f, text = TextStyle(),
            customText = if (type == ElementType.CUSTOM_TEXT) "Custom text" else null,
        )
        type.isImage -> ElementSpec(type = type, x = cx - 150f, y = cy - 150f, w = 300f, h = 300f, image = ImageStyle())
        type == ElementType.PROGRESS_BAR -> ElementSpec(
            type = type, x = 40f, y = canvasH - 60f, w = CANVAS_UNITS - 80f, h = 16f, shape = ShapeStyle()
        )
        else -> ElementSpec(type = type, x = cx - 150f, y = cy - 60f, w = 300f, h = 120f, shape = ShapeStyle())
    }
}
