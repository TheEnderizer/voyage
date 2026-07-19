package com.betteraudio.ui.widget

import com.betteraudio.widget.model.BackgroundSpec
import com.betteraudio.widget.model.CANVAS_UNITS
import com.betteraudio.widget.model.ContainerShape
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.IconStyle
import com.betteraudio.widget.model.ImageStyle
import com.betteraudio.widget.model.ShapeStyle
import com.betteraudio.widget.model.TextStyle
import com.betteraudio.widget.model.WidgetDesignDoc

/** A new design's starting point: cover background, title/author, and a play button — enough to
 *  be immediately useful and demonstrate the canvas, without being a blank void. Laid out relative
 *  to the chosen [aspect] so the starter looks sensible whatever size the user picked. */
fun starterWidgetDesignDoc(aspect: Float = 2f): WidgetDesignDoc {
    val canvasH = CANVAS_UNITS / aspect
    val margin = 40f
    val play = 110f.coerceAtMost(canvasH * 0.5f)
    return WidgetDesignDoc(
        background = BackgroundSpec(),
        elements = listOf(
            ElementSpec(
                type = ElementType.BOOK_TITLE, x = margin, y = canvasH - 190f, w = CANVAS_UNITS - 2 * margin - play - 40f, h = 70f,
                text = TextStyle(sizeUnits = 60f, weight = 700),
            ),
            ElementSpec(
                type = ElementType.AUTHOR, x = margin, y = canvasH - 120f, w = CANVAS_UNITS - 2 * margin - play - 40f, h = 50f,
                text = TextStyle(sizeUnits = 38f, weight = 500),
            ),
            ElementSpec(
                type = ElementType.PLAY_PAUSE, x = CANVAS_UNITS - margin - play, y = canvasH - margin - play, w = play, h = play,
                icon = IconStyle(container = ContainerShape.CIRCLE, containerColor = 0x40FFFFFF),
            ),
        ),
    )
}

fun defaultElementFor(type: ElementType, aspectRatio: Float): ElementSpec {
    val canvasH = CANVAS_UNITS / aspectRatio
    val cx = CANVAS_UNITS / 2f
    val cy = canvasH / 2f
    return when {
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
