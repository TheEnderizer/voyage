package com.betteraudio.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.betteraudio.R
import com.betteraudio.playback.PlaybackService

/** Wide card (≈ 4×2): note badge + title/author + transport on the left, cover on the right. */
class NowPlayingWidget : BaseNowPlayingWidget() {

    override fun buildViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        state: WidgetState
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_now_playing)
        val r = WidgetRender
        fun dp(v: Int) = r.dp(context, v)

        views.setOnClickPendingIntent(R.id.widget_container, r.openAppIntent(context))
        views.setOnClickPendingIntent(R.id.btn_play_pause,
            r.serviceIntent(context, 1, PlaybackService.ACTION_TOGGLE_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.btn_skip_forward,
            r.serviceIntent(context, 2, PlaybackService.ACTION_SKIP_FORWARD))
        views.setOnClickPendingIntent(R.id.btn_skip_back,
            r.serviceIntent(context, 3, PlaybackService.ACTION_SKIP_BACK))

        val (cover, accent, cardBg) = r.palette(context, state.coverArtUri)

        views.setTextViewText(R.id.tv_title,
            state.title.ifBlank { context.getString(R.string.widget_no_book) })
        views.setTextViewText(R.id.tv_author, state.author)
        views.setTextColor(R.id.tv_title, accent)
        views.setTextColor(R.id.tv_author, 0xFFB6AB9C.toInt())

        val opts = manager.getAppWidgetOptions(widgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 280).coerceIn(120, 600)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120).coerceIn(70, 320)
        views.setImageViewBitmap(R.id.iv_bg, r.renderCardBackground(dp(wDp), dp(hDp), cardBg, dp(26).toFloat()))

        views.setImageViewBitmap(R.id.iv_note, r.renderNoteBadge(context, dp(26), accent, cardBg))
        views.setImageViewBitmap(R.id.btn_skip_back,
            r.renderButton(context, dp(42), accent, accent, R.drawable.ic_skip_back, filled = false))
        views.setImageViewBitmap(R.id.btn_play_pause,
            r.renderButton(context, dp(52), accent, cardBg,
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, filled = true))
        views.setImageViewBitmap(R.id.btn_skip_forward,
            r.renderButton(context, dp(42), accent, accent, R.drawable.ic_skip_forward, filled = false))

        views.setImageViewBitmap(R.id.iv_cover_art, r.coverBitmap(context, cover, dp(86), dp(20).toFloat()))
        return views
    }
}
