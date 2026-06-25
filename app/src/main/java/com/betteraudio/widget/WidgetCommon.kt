package com.betteraudio.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.betteraudio.MainActivity
import com.betteraudio.R
import com.betteraudio.playback.PlaybackService
import kotlin.math.max

data class WidgetState(
    val title: String = "",
    val author: String = "",
    val isPlaying: Boolean = false,
    val coverArtUri: String? = null
)

/**
 * Shared rendering for every now-playing widget size. The dynamic, accent-coloured pieces are
 * drawn to bitmaps in code (RemoteViews can't use a Compose theme) so all widget shapes recolour
 * to the playing book's cover art.
 */
object WidgetRender {

    const val ACTION_UPDATE_WIDGET = "com.betteraudio.action.UPDATE_WIDGET"
    const val EXTRA_BOOK_TITLE     = "extra_book_title"
    const val EXTRA_BOOK_AUTHOR    = "extra_book_author"
    const val EXTRA_IS_PLAYING     = "extra_is_playing"
    const val EXTRA_COVER_ART_URI  = "extra_cover_art_uri"
    const val EXTRA_OPEN_PLAYER    = "extra_open_player"

    private const val DEFAULT_ACCENT = 0xFFFFA552.toInt()

    /** Cached so resize/options-change re-renders keep the current track. */
    @Volatile var lastState: WidgetState = WidgetState()

    fun stateFrom(intent: Intent) = WidgetState(
        title = intent.getStringExtra(EXTRA_BOOK_TITLE) ?: "",
        author = intent.getStringExtra(EXTRA_BOOK_AUTHOR) ?: "",
        isPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false),
        coverArtUri = intent.getStringExtra(EXTRA_COVER_ART_URI)
    )

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    // ── Click intents ───────────────────────────────────────────────────────

    fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun serviceIntent(context: Context, reqCode: Int, action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ── Accent / colour ─────────────────────────────────────────────────────

    /** Decoded cover (or null) + the accent colour + a deep accent-tinted card colour. */
    fun palette(context: Context, coverUri: String?): Triple<Bitmap?, Int, Int> {
        val cover = decodeCover(context, coverUri)
        val accent = cover?.let { paletteAccent(it) } ?: DEFAULT_ACCENT
        return Triple(cover, accent, darkTint(accent))
    }

    private fun paletteAccent(bmp: Bitmap): Int {
        val p = Palette.from(bmp).maximumColorCount(16).generate()
        val c = p.getVibrantColor(0).takeIf { it != 0 }
            ?: p.getLightVibrantColor(0).takeIf { it != 0 }
            ?: p.getDominantColor(DEFAULT_ACCENT)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(c, hsl)
        hsl[1] = max(hsl[1], 0.45f)
        hsl[2] = hsl[2].coerceIn(0.55f, 0.72f)
        return ColorUtils.HSLToColor(hsl)
    }

    fun darkTint(accent: Int): Int {
        val r = (Color.red(accent) * 0.16f + 12).toInt()
        val g = (Color.green(accent) * 0.16f + 11).toInt()
        val b = (Color.blue(accent) * 0.16f + 11).toInt()
        return Color.rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
    }

    // ── Bitmap rendering ────────────────────────────────────────────────────

    fun renderCardBackground(w: Int, h: Int, color: Int, radius: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        return bmp
    }

    fun renderButton(
        context: Context, size: Int, accent: Int, glyphColor: Int, iconRes: Int, filled: Boolean
    ): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (filled) accent else ColorUtils.setAlphaComponent(accent, 0x40)
        })
        val pad = (size * 0.28f).toInt()
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.apply {
            setTint(glyphColor)
            setBounds(pad, pad, size - pad, size - pad)
            draw(canvas)
        }
        return bmp
    }

    fun renderNoteBadge(context: Context, size: Int, accent: Int, glyphColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = size / 2f
        canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
        val pad = (size * 0.26f).toInt()
        ContextCompat.getDrawable(context, R.drawable.ic_widget_note)?.mutate()?.apply {
            setTint(glyphColor)
            setBounds(pad, pad, size - pad, size - pad)
            draw(canvas)
        }
        return bmp
    }

    fun decodeCover(context: Context, coverUri: String?): Bitmap? {
        if (coverUri.isNullOrBlank()) return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(coverUri))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) { null }
    }

    fun renderPlaceholder(context: Context, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        ContextCompat.getDrawable(context, R.drawable.ic_audiobook_placeholder)?.apply {
            setBounds(0, 0, size, size)
            draw(canvas)
        }
        return bmp
    }

    /** Center-cropped, rounded square cover (falls back to placeholder). */
    fun coverBitmap(context: Context, source: Bitmap?, size: Int, radius: Float): Bitmap {
        val src = source ?: renderPlaceholder(context, size)
        val scale = max(size.toFloat() / src.width, size.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(size)
        val sh = (src.height * scale).toInt().coerceAtLeast(size)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        val x = ((scaled.width - size) / 2).coerceAtLeast(0)
        val y = ((scaled.height - size) / 2).coerceAtLeast(0)
        val square = Bitmap.createBitmap(scaled, x, y, size, size)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(square, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        return out
    }
}
