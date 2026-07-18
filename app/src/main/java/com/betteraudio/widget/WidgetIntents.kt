package com.betteraudio.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.betteraudio.MainActivity
import com.betteraudio.playback.PlaybackService
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.TapAction

/** Builds every PendingIntent the widget can fire: control-element actions, TEXT/IMAGE/SHAPE tap
 *  actions (the "more interactivity" for those element types), and app-launch intents. Every
 *  service intent is scoped with a unique `data` Uri keyed on the element's own stable UUID, so
 *  two buttons of the same type — or the same design placed on two home-screen widgets — never
 *  collide under PendingIntent's (requestCode, Intent#filterEquals) identity rules. */
object WidgetIntents {

    const val EXTRA_OPEN_PLAYER = "extra_open_player"
    const val EXTRA_OPEN_WIDGET_GALLERY = "extra_open_widget_gallery"

    private val CONTROL_ACTIONS: Map<ElementType, String> = mapOf(
        ElementType.PLAY_PAUSE to PlaybackService.ACTION_TOGGLE_PLAY_PAUSE,
        ElementType.SKIP_FORWARD to PlaybackService.ACTION_SKIP_FORWARD,
        ElementType.SKIP_BACK to PlaybackService.ACTION_SKIP_BACK,
        ElementType.CHAPTER_FORWARD to PlaybackService.ACTION_CHAPTER_FORWARD,
        ElementType.CHAPTER_BACK to PlaybackService.ACTION_CHAPTER_BACK,
        ElementType.SPEED_UP to PlaybackService.ACTION_SPEED_UP,
        ElementType.SPEED_DOWN to PlaybackService.ACTION_SPEED_DOWN,
        ElementType.BOOST_UP to PlaybackService.ACTION_BOOST_UP,
        ElementType.BOOST_DOWN to PlaybackService.ACTION_BOOST_DOWN,
        ElementType.QUICK_BOOKMARK to PlaybackService.ACTION_QUICK_BOOKMARK,
        ElementType.CLOSE_BOOK to PlaybackService.ACTION_CLOSE_BOOK,
        ElementType.SLEEP_TIMER to PlaybackService.ACTION_SLEEP_TIMER_TOGGLE,
    )

    private val TAP_ACTION_SERVICE_ACTIONS: Map<TapAction, String> = mapOf(
        TapAction.PLAY_PAUSE to PlaybackService.ACTION_TOGGLE_PLAY_PAUSE,
        TapAction.SKIP_FORWARD to PlaybackService.ACTION_SKIP_FORWARD,
        TapAction.SKIP_BACK to PlaybackService.ACTION_SKIP_BACK,
        TapAction.NEXT_CHAPTER to PlaybackService.ACTION_CHAPTER_FORWARD,
        TapAction.PREV_CHAPTER to PlaybackService.ACTION_CHAPTER_BACK,
    )

    /** Null for non-interactive control types (shouldn't happen — every ElementType.isControl has
     *  an entry) or missing element data. */
    fun forControl(context: Context, appWidgetId: Int, element: ElementSpec): PendingIntent? {
        val action = CONTROL_ACTIONS[element.type] ?: return null
        return serviceIntent(context, appWidgetId, element.id, action) {
            if (element.type == ElementType.SLEEP_TIMER) {
                putExtra(PlaybackService.EXTRA_SLEEP_DURATION_MS, element.sleepDurationMs ?: 15 * 60_000L)
            }
        }
    }

    /** Null when the element's [ElementSpec.tapAction] is NONE, OPEN_APP, or OPEN_PLAYER (those
     *  are handled by [forTapAction] via activity intents) or unrecognized. */
    private fun forTapActionService(context: Context, appWidgetId: Int, element: ElementSpec): PendingIntent? {
        val action = TAP_ACTION_SERVICE_ACTIONS[element.tapAction] ?: return null
        return serviceIntent(context, appWidgetId, element.id, action)
    }

    /** Resolves a TEXT/IMAGE/SHAPE element's tap behavior. Returns null for [TapAction.NONE] —
     *  callers should skip such elements entirely when building hit-grid claims, so taps on them
     *  fall through to the root open-app intent instead of claiming cells for nothing. */
    fun forTapAction(context: Context, appWidgetId: Int, element: ElementSpec): PendingIntent? =
        when (element.tapAction) {
            TapAction.NONE -> null
            TapAction.OPEN_APP -> openAppIntent(context)
            TapAction.OPEN_PLAYER -> openPlayerIntent(context)
            else -> forTapActionService(context, appWidgetId, element)
        }

    private fun serviceIntent(
        context: Context,
        appWidgetId: Int,
        elementId: String,
        action: String,
        configure: Intent.() -> Unit = {},
    ): PendingIntent {
        val reqCode = (appWidgetId * 31 + elementId.hashCode()) and 0x7FFFFFFF
        val intent = Intent(context, PlaybackService::class.java).apply {
            this.action = action
            data = Uri.parse("voyage://w/$appWidgetId/$elementId")
            configure()
        }
        return PendingIntent.getService(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun openPlayerIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun openGalleryIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_WIDGET_GALLERY, true)
        }
        return PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
