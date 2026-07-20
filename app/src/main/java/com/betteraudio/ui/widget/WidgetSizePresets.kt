package com.betteraudio.ui.widget

/** A home-screen footprint the user can design against. The actual placed widget is free-size and
 *  the user resizes it to whatever they like — this only sets the design's native aspect ratio
 *  (how elements are laid out) and the editor's preview shape. Two footprints with the same aspect
 *  lay out identically, so the presets are chosen to each have a distinct shape. */
data class WidgetSizePreset(val label: String, val cols: Int, val rows: Int) {
    val aspect: Float get() = cols.toFloat() / rows.toFloat()
}

val WIDGET_SIZE_PRESETS: List<WidgetSizePreset> = listOf(
    WidgetSizePreset("Banner", 4, 1),   // 4.0
    WidgetSizePreset("Wide", 4, 2),     // 2.0
    WidgetSizePreset("Medium", 3, 2),   // 1.5
    WidgetSizePreset("Large", 4, 3),    // 1.33
    WidgetSizePreset("Square", 2, 2),   // 1.0
    WidgetSizePreset("Tall", 2, 3),     // 0.667
)
