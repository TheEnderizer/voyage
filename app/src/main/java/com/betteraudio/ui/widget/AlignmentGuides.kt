package com.betteraudio.ui.widget

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.betteraudio.widget.model.CANVAS_UNITS
import com.betteraudio.widget.model.ElementSpec
import kotlin.math.abs

private const val THRESHOLD_UNITS = 6f
private val GUIDE_COLOR = Color(0xFFFF4FA3)

/**
 * Purely visual alignment hints — thin lines flash near canvas center/thirds or another
 * element's center/edge when the dragged element is within [THRESHOLD_UNITS] design units.
 * They NEVER move the element (the user explicitly rejected snapping); this only helps the eye.
 */
@Composable
fun AlignmentGuides(
    box: RectF,
    scale: Float,
    canvasHeightUnits: Float,
    selected: ElementSpec,
    others: List<ElementSpec>,
) {
    Canvas(Modifier.fillMaxSize()) {
        val cx = selected.x + selected.w / 2f
        val cy = selected.y + selected.h / 2f

        val verticalTargets = buildList {
            add(CANVAS_UNITS / 2f)
            add(CANVAS_UNITS / 3f)
            add(CANVAS_UNITS * 2f / 3f)
            others.forEach { o -> add(o.x + o.w / 2f); add(o.x); add(o.x + o.w) }
        }
        val horizontalTargets = buildList {
            add(canvasHeightUnits / 2f)
            add(canvasHeightUnits / 3f)
            add(canvasHeightUnits * 2f / 3f)
            others.forEach { o -> add(o.y + o.h / 2f); add(o.y); add(o.y + o.h) }
        }

        verticalTargets.firstOrNull { abs(it - cx) < THRESHOLD_UNITS }?.let { target ->
            val xPx = box.left + target * scale
            drawLine(GUIDE_COLOR, Offset(xPx, 0f), Offset(xPx, size.height), strokeWidth = 2.5f)
        }
        horizontalTargets.firstOrNull { abs(it - cy) < THRESHOLD_UNITS }?.let { target ->
            val yPx = box.top + target * scale
            drawLine(GUIDE_COLOR, Offset(0f, yPx), Offset(size.width, yPx), strokeWidth = 2.5f)
        }
    }
}
