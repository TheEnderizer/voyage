package com.betteraudio.widget.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.betteraudio.widget.model.HorizontalTextAlign
import com.betteraudio.widget.model.TextStyle

/** Text layout/paint helpers shared by [WidgetPainter]. */
object TextPaints {

    /** [weight] 100..900 (TextStyle exposes 400..800 in the editor). Falls back to plain/bold
     *  typefaces below API 28, where variable-weight Typeface.create(Typeface, int, boolean)
     *  isn't available (minSdk 26). */
    fun typefaceFor(weight: Int, italic: Boolean): Typeface {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.DEFAULT, weight, italic)
        } else {
            val style = when {
                weight >= 600 && italic -> Typeface.BOLD_ITALIC
                weight >= 600 -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(Typeface.DEFAULT, style)
        }
    }

    fun paintFor(style: TextStyle, textSizePx: Float, color: Int): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = textSizePx
            typeface = typefaceFor(style.weight, style.italic)
            if (style.shadow) setShadowLayer(textSizePx * 0.12f, 0f, textSizePx * 0.06f, 0x99000000.toInt())
        }

    /** Draws [text] inside [rect], vertically centered, ellipsized/wrapped to [TextStyle.maxLines]. */
    fun draw(canvas: Canvas, text: String, rect: RectF, style: TextStyle, textSizePx: Float, color: Int) {
        if (text.isBlank()) return
        val paint = paintFor(style, textSizePx, color)
        val width = rect.width().toInt().coerceAtLeast(1)
        val align = when (style.align) {
            HorizontalTextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            HorizontalTextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            HorizontalTextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val ellipsized = if (style.maxLines <= 1) {
            TextUtils.ellipsize(text, paint, width.toFloat(), TextUtils.TruncateAt.END)
        } else {
            text
        }
        val layout = StaticLayout.Builder
            .obtain(ellipsized, 0, ellipsized.length, paint, width)
            .setAlignment(align)
            .setMaxLines(style.maxLines.coerceAtLeast(1))
            .setEllipsize(if (style.maxLines > 1) TextUtils.TruncateAt.END else null)
            .setIncludePad(false)
            .build()

        val top = rect.top + (rect.height() - layout.height) / 2f
        canvas.save()
        canvas.translate(rect.left, top)
        layout.draw(canvas)
        canvas.restore()
    }
}
