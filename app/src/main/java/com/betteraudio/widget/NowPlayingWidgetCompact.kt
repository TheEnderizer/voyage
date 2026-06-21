package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.betteraudio.R
import com.betteraudio.playback.PlaybackService

/** Compact pill (≈ 3×1): play button + title/author + cover. */
class NowPlayingWidgetCompact : BaseNowPlayingWidget() {

    override fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        state: WidgetState
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing_compact)
        val r = WidgetRender
        fun dp(v: Int) = r.dp(context, v)

        views.setOnClickPendingIntent(R.id.widget_container, r.openAppIntent(context))
        views.setOnClickPendingIntent(R.id.btn_play_pause,
            r.serviceIntent(context, 1, PlaybackService.ACTION_TOGGLE_PLAY_PAUSE))

        val (cover, accent, cardBg) = r.palette(context, state.coverArtUri)

        views.setTextViewText(R.id.tv_title,
            state.title.ifBlank { context.getString(R.string.widget_no_book) })
        views.setTextViewText(R.id.tv_author, state.author)
        views.setTextColor(R.id.tv_title, accent)
        views.setTextColor(R.id.tv_author, 0xFFB6AB9C.toInt())

        val opts = manager.getAppWidgetOptions(widgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).coerceIn(90, 500)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60).coerceIn(48, 160)
        // Pill: fully rounded ends
        val radius = (dp(hDp) / 2f)
        views.setImageViewBitmap(R.id.iv_bg, r.renderCardBackground(dp(wDp), dp(hDp), cardBg, radius))

        views.setImageViewBitmap(R.id.btn_play_pause,
            r.renderButton(context, dp(46), accent, cardBg,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, filled = true))

        views.setImageViewBitmap(R.id.iv_cover_art, r.coverBitmap(context, cover, dp(48), dp(16).toFloat()))
        return views
    }
}
