package com.betteraudio.widget

import android.content.Context
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * The static 16×16 transparent tap-grid overlaid on the widget's rendered bitmap (see
 * res/layout/widget_hit_grid.xml, ids `hit_<row>_<col>`). RemoteViews can't position views per
 * design at runtime (setViewLayoutMargin/Width/Height are API 31+, minSdk is 26), so free element
 * placement is approximated by this much finer grid than the old system's 6×6.
 *
 * A cell is claimed by an element iff the element's visible rect contains the cell's CENTER, or
 * covers at least 40% of the cell's area — whichever element claims a cell last (by z-order, i.e.
 * later in the input list) wins. This bounds a tap's error to at most half a cell (~3% of the
 * widget on a 16×16 grid) past the element's true visible bounds, replacing the old system's
 * ceil/floor cell-span rule that could over-claim a whole extra row/column and cause taps on one
 * button to silently fire a neighboring one.
 */
object HitGrid {
    const val ROWS = 16
    const val COLS = 16

    fun cellId(context: Context, row: Int, col: Int): Int =
        context.resources.getIdentifier("hit_${row}_$col", "id", context.packageName)

    /** Resolves which of the ROWS*COLS cells each [claims] entry (element bounding rect → payload,
     *  e.g. a PendingIntent) owns. Entries later in the list win any cell also claimed by an
     *  earlier entry, matching top-of-z-order-wins. Returns a flat list indexed `row * COLS + col`. */
    fun <T> assign(outW: Int, outH: Int, claims: List<Pair<RectF, T>>): List<T?> {
        val cellW = outW.toFloat() / COLS
        val cellH = outH.toFloat() / ROWS
        val result = MutableList<T?>(ROWS * COLS) { null }
        if (cellW <= 0f || cellH <= 0f) return result

        for ((rect, payload) in claims) {
            if (rect.width() <= 0f || rect.height() <= 0f) continue
            val colStart = (Math.floor((rect.left / cellW).toDouble()).toInt() - 1).coerceIn(0, COLS - 1)
            val colEnd = (Math.ceil((rect.right / cellW).toDouble()).toInt() + 1).coerceIn(0, COLS - 1)
            val rowStart = (Math.floor((rect.top / cellH).toDouble()).toInt() - 1).coerceIn(0, ROWS - 1)
            val rowEnd = (Math.ceil((rect.bottom / cellH).toDouble()).toInt() + 1).coerceIn(0, ROWS - 1)

            for (r in rowStart..rowEnd) {
                for (c in colStart..colEnd) {
                    val cellLeft = c * cellW
                    val cellTop = r * cellH
                    val cell = RectF(cellLeft, cellTop, cellLeft + cellW, cellTop + cellH)
                    if (cellIsClaimedBy(cell, rect)) result[r * COLS + c] = payload
                }
            }
        }
        return result
    }

    private fun cellIsClaimedBy(cell: RectF, elementRect: RectF): Boolean {
        if (elementRect.contains(cell.centerX(), cell.centerY())) return true
        val left = max(cell.left, elementRect.left)
        val top = max(cell.top, elementRect.top)
        val right = min(cell.right, elementRect.right)
        val bottom = min(cell.bottom, elementRect.bottom)
        if (right <= left || bottom <= top) return false
        val overlapArea = (right - left) * (bottom - top)
        val cellArea = cell.width() * cell.height()
        return cellArea > 0f && overlapArea / cellArea >= 0.4f
    }
}
