package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import com.betteraudio.R
import com.betteraudio.playback.PlaybackService

/** 2×2 full-bleed cover widget: no card/border — the cover fills the widget entirely.
 *  A gradient scrim fades the bottom for the title; play/pause sits centre. */
class NowPlayingWidget2x2 : BaseNowPlayingWidget() {

    override fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        state: WidgetState
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing_2x2)
        val r = WidgetRender
        fun dp(v: Int) = r.dp(context, v)

        views.setOnClickPendingIntent(R.id.widget_container, r.openAppIntent(context))
        views.setOnClickPendingIntent(
            R.id.btn_play_pause,
            r.serviceIntent(context, 10, PlaybackService.ACTION_TOGGLE_PLAY_PAUSE)
        )

        val opts = manager.getAppWidgetOptions(widgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 140).coerceIn(80, 400)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 140).coerceIn(80, 400)
        val w = dp(wDp)
        val h = dp(hDp)

        val (cover, _, _) = r.palette(context, state.coverArtUri)

        // Full-bleed cover — no rounding, let the Android 12+ launcher handle corners
        views.setImageViewBitmap(R.id.iv_cover_art, r.coverBitmapRect(context, cover, w, h))

        // Bottom gradient scrim so title is readable over any cover art
        views.setImageViewBitmap(R.id.iv_scrim, r.renderScrim(w, h))

        // Title (white text over the scrim)
        views.setTextViewText(
            R.id.tv_title,
            state.title.ifBlank { context.getString(R.string.widget_no_book) }
        )

        // Circle: 70% transparent (30% opaque) white; icon: 20% transparent (80% opaque) white
        val iconRes = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        views.setImageViewBitmap(
            R.id.btn_play_pause,
            r.renderButton(
                context, dp(44),
                Color.argb(77, 255, 255, 255),  // 30% opaque white circle
                Color.argb(204, 255, 255, 255), // 80% opaque white icon
                iconRes,
                filled = true
            )
        )

        return views
    }
}
