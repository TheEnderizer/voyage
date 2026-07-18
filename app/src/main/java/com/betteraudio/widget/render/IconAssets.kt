package com.betteraudio.widget.render

import com.betteraudio.R
import com.betteraudio.widget.model.ElementType

/** Maps a control [ElementType] to its glyph drawable. PLAY_PAUSE and SLEEP_TIMER swap between
 *  two resources based on live state (playing/paused, timer active/inactive) — see WidgetPainter. */
object IconAssets {
    private val STATIC: Map<ElementType, Int> = mapOf(
        ElementType.SKIP_FORWARD to R.drawable.ic_w_skip_forward,
        ElementType.SKIP_BACK to R.drawable.ic_w_skip_back,
        ElementType.CHAPTER_FORWARD to R.drawable.ic_w_chapter_next,
        ElementType.CHAPTER_BACK to R.drawable.ic_w_chapter_prev,
        ElementType.SPEED_UP to R.drawable.ic_w_speed_up,
        ElementType.SPEED_DOWN to R.drawable.ic_w_speed_down,
        ElementType.BOOST_UP to R.drawable.ic_w_boost_up,
        ElementType.BOOST_DOWN to R.drawable.ic_w_boost_down,
        ElementType.QUICK_BOOKMARK to R.drawable.ic_w_bookmark_add,
        ElementType.CLOSE_BOOK to R.drawable.ic_w_close,
    )

    fun resFor(type: ElementType, isPlaying: Boolean, sleepActive: Boolean): Int? = when (type) {
        ElementType.PLAY_PAUSE -> if (isPlaying) R.drawable.ic_w_pause else R.drawable.ic_w_play
        ElementType.SLEEP_TIMER -> if (sleepActive) R.drawable.ic_w_sleep_active else R.drawable.ic_w_sleep
        else -> STATIC[type]
    }

    val placeholder: Int get() = R.drawable.ic_w_widget_placeholder
}
