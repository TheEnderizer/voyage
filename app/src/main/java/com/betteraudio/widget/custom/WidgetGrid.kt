package com.betteraudio.widget.custom

import android.content.Context
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The static 6×6 transparent tap-grid overlaid on every custom widget's rendered bitmap.
 * RemoteViews can't position views per design at runtime (setViewLayoutMargin/Width/Height are
 * API 31+, this app targets minSdk 26), so instead every custom widget host layout declares a
 * fixed grid of clickable cells (`cell_<row>_<col>`, see widget_custom_host.xml) and each
 * interactive element's PendingIntent is attached to the cells its rect overlaps.
 */
object WidgetGrid {
    const val COLS = 6
    const val ROWS = 6

    /** Resolves the resource id of grid cell (row, col), e.g. R.id.cell_2_3. */
    fun cellId(context: Context, row: Int, col: Int): Int =
        context.resources.getIdentifier("cell_${row}_$col", "id", context.packageName)

    /** Column/row span [start, end] (inclusive) covered by a normalized element rect. */
    fun colRange(x: Float, w: Float): IntRange {
        val start = floor(x * COLS).toInt().coerceIn(0, COLS - 1)
        val end = (ceil((x + w) * COLS).toInt() - 1).coerceIn(start, COLS - 1)
        return start..end
    }

    fun rowRange(y: Float, h: Float): IntRange {
        val start = floor(y * ROWS).toInt().coerceIn(0, ROWS - 1)
        val end = (ceil((y + h) * ROWS).toInt() - 1).coerceIn(start, ROWS - 1)
        return start..end
    }

    /** Snaps a normalized rect's edges to grid cell boundaries (used by the editor for interactive elements). */
    fun snap(x: Float, y: Float, w: Float, h: Float): FloatArray {
        val cols = colRange(x, w)
        val rows = rowRange(y, h)
        val sx = cols.first.toFloat() / COLS
        val sy = rows.first.toFloat() / ROWS
        val sw = (cols.last - cols.first + 1).toFloat() / COLS
        val sh = (rows.last - rows.first + 1).toFloat() / ROWS
        return floatArrayOf(sx, sy, sw, sh)
    }
}
