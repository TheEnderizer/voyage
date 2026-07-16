package com.betteraudio.widget.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import com.betteraudio.R
import com.betteraudio.data.db.entities.CustomWidgetDesign
import com.betteraudio.ui.theme.ThemeSeedPaletteCodec
import com.betteraudio.widget.WidgetRender
import com.betteraudio.widget.WidgetState
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a [CustomWidgetDesign] + current [WidgetState] to a single bitmap. Reuses the
 * bitmap-drawing helpers in [WidgetRender] (RemoteViews can't use a Compose theme, so every
 * visual — including custom-widget elements — is painted to a Canvas).
 */
object CustomWidgetRenderer {

    private const val MAX_EDGE_PX = 1200

    private val ELEMENT_ICONS: Map<WidgetElementType, Int> = mapOf(
        WidgetElementType.PLAY_PAUSE to R.drawable.ic_play, // swapped to ic_pause when isPlaying
        WidgetElementType.SKIP_FORWARD to R.drawable.ic_skip_forward,
        WidgetElementType.SKIP_BACK to R.drawable.ic_skip_back,
        WidgetElementType.CHAPTER_FORWARD to R.drawable.ic_chapter_forward,
        WidgetElementType.CHAPTER_BACK to R.drawable.ic_chapter_back,
        WidgetElementType.SPEED_UP to R.drawable.ic_speed_up,
        WidgetElementType.SPEED_DOWN to R.drawable.ic_speed_down,
        WidgetElementType.BOOST_UP to R.drawable.ic_boost_up,
        WidgetElementType.BOOST_DOWN to R.drawable.ic_boost_down,
        WidgetElementType.QUICK_BOOKMARK to R.drawable.ic_bookmark_add,
        WidgetElementType.CLOSE_BOOK to R.drawable.ic_close_book
    )

    /**
     * @param hideWhenIdle if true and nothing is playing, returns a fully transparent bitmap
     * (per Settings → Widget → "Hide widgets when nothing is playing").
     */
    fun render(
        context: Context,
        design: CustomWidgetDesign,
        state: WidgetState,
        appColor: Int,
        hideWhenIdle: Boolean,
        pxW: Int,
        pxH: Int
    ): Bitmap {
        val scale = MAX_EDGE_PX.toFloat() / max(pxW, pxH).coerceAtLeast(1)
        val w = if (scale < 1f) (pxW * scale).toInt().coerceAtLeast(1) else pxW
        val h = if (scale < 1f) (pxH * scale).toInt().coerceAtLeast(1) else pxH

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (hideWhenIdle && !state.isPlaying) return bmp

        val canvas = Canvas(bmp)
        val density = context.resources.displayMetrics.density
        val background = runCatching { WidgetBackground.valueOf(design.backgroundType) }
            .getOrDefault(WidgetBackground.BOOK_COVER)

        val accent = when (background) {
            WidgetBackground.APP_COLOR, WidgetBackground.CUSTOM_COLOR -> appColor
            else -> WidgetRender.palette(context, state.coverArtUri ?: state.bookCoverPath).second
        }

        drawBackground(context, canvas, background, design.backgroundValue, state, appColor, w, h)

        val elements = WidgetElementCodec.decode(design.elementsJson)
        val hasImageBackground = background == WidgetBackground.BOOK_COVER ||
            background == WidgetBackground.SERIES_COVER || background == WidgetBackground.CUSTOM_IMAGE
        if (hasImageBackground && elements.any { it.type.isText }) {
            val scrim = WidgetRender.renderScrim(w, h)
            canvas.drawBitmap(scrim, 0f, 0f, null)
        }

        for (el in elements) {
            val rect = RectF(el.x * w, el.y * h, (el.x + el.w) * w, (el.y + el.h) * h)
            if (rect.width() <= 0f || rect.height() <= 0f) continue
            drawElement(context, canvas, el, rect, state, accent, density)
        }

        return bmp
    }

    private fun drawBackground(
        context: Context,
        canvas: Canvas,
        background: WidgetBackground,
        backgroundValue: String,
        state: WidgetState,
        appColor: Int,
        w: Int,
        h: Int
    ) {
        when (background) {
            WidgetBackground.BOOK_COVER -> {
                val src = WidgetRender.decodeFile(state.bookCoverPath) ?: WidgetRender.decodeCover(context, state.coverArtUri)
                canvas.drawBitmap(WidgetRender.coverBitmapRect(context, src, w, h), 0f, 0f, null)
            }
            WidgetBackground.SERIES_COVER -> {
                val src = WidgetRender.decodeFile(state.seriesCoverPath)
                    ?: WidgetRender.decodeFile(state.bookCoverPath)
                    ?: WidgetRender.decodeCover(context, state.coverArtUri)
                canvas.drawBitmap(WidgetRender.coverBitmapRect(context, src, w, h), 0f, 0f, null)
            }
            WidgetBackground.CUSTOM_IMAGE -> {
                val src = WidgetRender.decodeFile(backgroundValue)
                canvas.drawBitmap(WidgetRender.coverBitmapRect(context, src, w, h), 0f, 0f, null)
            }
            WidgetBackground.APP_COLOR -> {
                // A dark, accent-tinted tone (like the app's Material You dark surface) rather
                // than the raw bright primary color, which would be too loud as a fill.
                canvas.drawColor(WidgetRender.darkTint(appColor))
            }
            WidgetBackground.CUSTOM_COLOR -> {
                canvas.drawColor(resolveCustomColor(backgroundValue, appColor))
            }
        }
    }

    private fun resolveCustomColor(value: String, fallback: Int): Int {
        if (value.isBlank()) return fallback
        if (value.startsWith("seedPalette:")) {
            val palette = ThemeSeedPaletteCodec.decodeFromPreference(value) ?: return fallback
            return palette.primary.toArgb()
        }
        return try { Color.parseColor(value) } catch (_: Exception) { fallback }
    }

    private fun drawElement(
        context: Context,
        canvas: Canvas,
        el: WidgetElement,
        rect: RectF,
        state: WidgetState,
        accent: Int,
        density: Float
    ) {
        when {
            el.type.isText -> drawText(canvas, textFor(el.type, state), rect, el.fontSizeSp ?: 14f, density)
            el.type == WidgetElementType.BOOK_COVER -> {
                val src = WidgetRender.decodeFile(state.bookCoverPath) ?: WidgetRender.decodeCover(context, state.coverArtUri)
                drawImageRect(context, canvas, src, rect)
            }
            el.type == WidgetElementType.SERIES_COVER -> {
                val src = WidgetRender.decodeFile(state.seriesCoverPath) ?: WidgetRender.decodeFile(state.bookCoverPath)
                drawImageRect(context, canvas, src, rect)
            }
            el.type == WidgetElementType.CUSTOM_IMAGE -> {
                val src = WidgetRender.decodeFile(el.imagePath)
                drawImageRect(context, canvas, src, rect)
            }
            el.type == WidgetElementType.PLAY_PAUSE -> {
                val icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                drawButton(context, canvas, rect, accent, icon)
            }
            el.type == WidgetElementType.SLEEP_TIMER -> {
                val active = state.sleepTimerRemainingMs > 0L
                val icon = if (active) R.drawable.ic_sleep_active else R.drawable.ic_sleep
                drawButton(context, canvas, rect, accent, icon)
                if (active) {
                    val totalSec = state.sleepTimerRemainingMs / 1000
                    val mm = totalSec / 60
                    val ss = totalSec % 60
                    val label = String.format("%d:%02d", mm, ss)
                    val labelRect = RectF(rect.left, rect.bottom, rect.right, rect.bottom + rect.height() * 0.4f)
                    drawText(canvas, label, labelRect, (rect.height() / density) * 0.28f, density, center = true)
                }
            }
            el.type in ELEMENT_ICONS -> drawButton(context, canvas, rect, accent, ELEMENT_ICONS.getValue(el.type))
        }
    }

    private fun textFor(type: WidgetElementType, state: WidgetState): String = when (type) {
        WidgetElementType.BOOK_NAME -> state.title
        WidgetElementType.AUTHOR_NAME -> state.author
        WidgetElementType.CHAPTER_NAME -> state.chapterTitle
        WidgetElementType.SERIES_NAME -> state.seriesName
        else -> ""
    }

    private fun drawImageRect(context: Context, canvas: Canvas, src: Bitmap?, rect: RectF) {
        val w = rect.width().toInt().coerceAtLeast(1)
        val h = rect.height().toInt().coerceAtLeast(1)
        val radius = min(w, h) * 0.12f
        val bmp = WidgetRender.coverBitmapRect(context, src, w, h, radius)
        canvas.drawBitmap(bmp, rect.left, rect.top, null)
    }

    private fun drawButton(context: Context, canvas: Canvas, rect: RectF, accent: Int, iconRes: Int) {
        val size = min(rect.width(), rect.height()).toInt().coerceAtLeast(1)
        val bmp = WidgetRender.renderButton(context, size, accent, Color.WHITE, iconRes, filled = true)
        val left = rect.left + (rect.width() - size) / 2f
        val top = rect.top + (rect.height() - size) / 2f
        canvas.drawBitmap(bmp, left, top, null)
    }

    private fun drawText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        fontSizeSp: Float,
        density: Float,
        center: Boolean = false
    ) {
        if (text.isBlank()) return
        val paint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontSizeSp * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        }
        val ellipsized = android.text.TextUtils.ellipsize(
            text, paint, rect.width(), android.text.TextUtils.TruncateAt.END
        ).toString()
        val metrics = paint.fontMetrics
        val baseline = rect.top + (rect.height() - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
        val x = if (center) rect.centerX() else rect.left
        canvas.drawText(ellipsized, x, baseline, paint)
    }
}
