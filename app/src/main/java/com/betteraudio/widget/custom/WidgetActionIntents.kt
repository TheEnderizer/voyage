package com.betteraudio.widget.custom

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.betteraudio.playback.PlaybackService

/**
 * Maps an interactive [WidgetElement] to the [PendingIntent] its tap-grid cells should fire.
 * PendingIntent identity ignores extras, so every intent is scoped with a unique `data` Uri and
 * request code per (appWidgetId, element type) — otherwise placing the same design twice, or two
 * different action buttons on one design, would collide under FLAG_UPDATE_CURRENT.
 */
object WidgetActionIntents {

    private val SERVICE_ACTIONS: Map<WidgetElementType, String> = mapOf(
        WidgetElementType.PLAY_PAUSE to PlaybackService.ACTION_TOGGLE_PLAY_PAUSE,
        WidgetElementType.SKIP_FORWARD to PlaybackService.ACTION_SKIP_FORWARD,
        WidgetElementType.SKIP_BACK to PlaybackService.ACTION_SKIP_BACK,
        WidgetElementType.CHAPTER_FORWARD to PlaybackService.ACTION_CHAPTER_FORWARD,
        WidgetElementType.CHAPTER_BACK to PlaybackService.ACTION_CHAPTER_BACK,
        WidgetElementType.SPEED_UP to PlaybackService.ACTION_SPEED_UP,
        WidgetElementType.SPEED_DOWN to PlaybackService.ACTION_SPEED_DOWN,
        WidgetElementType.BOOST_UP to PlaybackService.ACTION_BOOST_UP,
        WidgetElementType.BOOST_DOWN to PlaybackService.ACTION_BOOST_DOWN,
        WidgetElementType.QUICK_BOOKMARK to PlaybackService.ACTION_QUICK_BOOKMARK,
        WidgetElementType.CLOSE_BOOK to PlaybackService.ACTION_CLOSE_BOOK,
        WidgetElementType.SLEEP_TIMER to PlaybackService.ACTION_SLEEP_TIMER_TOGGLE
    )

    /** Null for non-interactive element types (text/covers/custom image). */
    fun forElement(context: Context, appWidgetId: Int, element: WidgetElement): PendingIntent? {
        val action = SERVICE_ACTIONS[element.type] ?: return null
        val reqCode = appWidgetId * 100 + element.type.ordinal
        val intent = Intent(context, PlaybackService::class.java).apply {
            this.action = action
            data = Uri.parse("betteraudio://w/$appWidgetId/${element.type}")
            if (element.type == WidgetElementType.SLEEP_TIMER) {
                putExtra(PlaybackService.EXTRA_SLEEP_DURATION_MS, element.durationMs ?: 15 * 60_000L)
            }
        }
        return PendingIntent.getService(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
