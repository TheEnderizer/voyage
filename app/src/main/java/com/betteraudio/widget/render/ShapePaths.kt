package com.betteraudio.widget.render

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Path builders shared by every container/shape/border draw call in [WidgetPainter], so the
 *  editor preview and the real widget bitmap always outline elements identically. */
object ShapePaths {

    fun roundedRect(rect: RectF, radius: Float): Path = Path().apply {
        val r = radius.coerceIn(0f, min(rect.width(), rect.height()) / 2f)
        addRoundRect(rect, r, r, Path.Direction.CW)
    }

    fun pill(rect: RectF): Path = roundedRect(rect, min(rect.width(), rect.height()) / 2f)

    fun circle(rect: RectF): Path = Path().apply {
        addOval(rect, Path.Direction.CW)
    }

    /** A superellipse ("squircle") — a rounded-rect look with continuous curvature, no flat/arc
     *  seam. `n` = 4 is the classic iOS-style squircle exponent. */
    fun squircle(rect: RectF, n: Double = 4.0, steps: Int = 64): Path {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val a = rect.width() / 2f
        val b = rect.height() / 2f
        val path = Path()
        for (i in 0..steps) {
            val t = (i.toDouble() / steps) * 2.0 * PI
            val ct = cos(t)
            val st = sin(t)
            val x = cx + (sign(ct) * abs(ct).pow(2.0 / n) * a).toFloat()
            val y = cy + (sign(st) * abs(st).pow(2.0 / n) * b).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun sign(v: Double): Double = if (v < 0) -1.0 else 1.0

    fun forContainer(shape: com.betteraudio.widget.model.ContainerShape, rect: RectF, fallbackRadius: Float): Path =
        when (shape) {
            com.betteraudio.widget.model.ContainerShape.NONE -> roundedRect(rect, 0f)
            com.betteraudio.widget.model.ContainerShape.CIRCLE -> circle(rect)
            com.betteraudio.widget.model.ContainerShape.ROUNDED -> roundedRect(rect, fallbackRadius)
            com.betteraudio.widget.model.ContainerShape.SQUIRCLE -> squircle(rect)
        }

    fun forShapeKind(kind: com.betteraudio.widget.model.ShapeKind, rect: RectF, cornerRadius: Float): Path =
        when (kind) {
            com.betteraudio.widget.model.ShapeKind.RECT -> roundedRect(rect, cornerRadius)
            com.betteraudio.widget.model.ShapeKind.PILL -> pill(rect)
            com.betteraudio.widget.model.ShapeKind.CIRCLE -> circle(rect)
        }
}
