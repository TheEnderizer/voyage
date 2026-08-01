package com.betteraudio.widget.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.betteraudio.R
import com.betteraudio.util.AppLog
import com.betteraudio.widget.model.BackgroundLayerStyle
import com.betteraudio.widget.model.BgSource
import com.betteraudio.widget.model.CANVAS_UNITS
import com.betteraudio.widget.model.ContainerShape
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.HorizontalTextAlign
import com.betteraudio.widget.model.IconStyle
import com.betteraudio.widget.model.ImageFit
import com.betteraudio.widget.model.ImageStyle
import com.betteraudio.widget.model.ProgressShape
import com.betteraudio.widget.model.ProgressSource
import com.betteraudio.widget.model.ShapeKind
import com.betteraudio.widget.model.ShapeStyle
import com.betteraudio.widget.model.TextStyle
import com.betteraudio.widget.model.WidgetDesignDoc
import com.betteraudio.widget.model.WidgetSnapshot
import kotlin.math.max
import kotlin.math.min

/**
 * The single pure painter shared verbatim by the widget provider, the editor's live canvas, and
 * gallery/configure thumbnails: `(doc, aspectRatio, snapshot, sizePx) -> Bitmap`. Because every
 * consumer calls the exact same function, the editor preview IS the widget render — WYSIWYG by
 * construction, unlike RemoteViews-based approaches that can only approximate a Compose layout.
 */
object WidgetPainter {

    private const val DEFAULT_ACCENT = 0xFFFFA552.toInt()

    data class PaintOptions(
        val accentFallback: Int = DEFAULT_ACCENT,
        val hideWhenIdle: Boolean = false,
        val defaultCoverPath: String? = null,
    )

    /** The largest [aspectRatio] (width/height) rect that fits inside an [outW]x[outH] box,
     *  centered. Elements render inside this box at one uniform scale; the background separately
     *  fills the FULL outer box (see [paint]) so stretching a widget never shows letterbox bars. */
    fun contentBox(outW: Int, outH: Int, aspectRatio: Float): RectF {
        val targetAspect = aspectRatio.coerceIn(0.1f, 10f)
        val outAspect = outW.toFloat() / outH.toFloat()
        return if (outAspect > targetAspect) {
            val w = outH * targetAspect
            val left = (outW - w) / 2f
            RectF(left, 0f, left + w, outH.toFloat())
        } else {
            val h = outW / targetAspect
            val top = (outH - h) / 2f
            RectF(0f, top, outW.toFloat(), top + h)
        }
    }

    /** Pixels per design unit — uniform in both axes since [contentBox] always matches the
     *  design's own aspect ratio exactly. */
    fun unitScale(contentBox: RectF): Float = contentBox.width() / CANVAS_UNITS

    fun paint(
        context: Context,
        doc: WidgetDesignDoc,
        aspectRatio: Float,
        snapshot: WidgetSnapshot,
        outWpx: Int,
        outHpx: Int,
        opts: PaintOptions = PaintOptions(),
    ): Bitmap {
        val w = outWpx.coerceAtLeast(1)
        val h = outHpx.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val box = contentBox(w, h, aspectRatio)
        val scale = unitScale(box)

        // The widget bitmap is always a plain rectangle — its own bounds are the only clip needed.
        // BACKGROUND_LAYER elements (there may be zero, one, or several) are ordinary freely-placed
        // elements drawn in z-order like anything else; see drawBackgroundLayerElement.
        val accentCover = resolveAccentCover(doc, snapshot, opts, w, h)
        val accent = resolveAccent(accentCover, opts.accentFallback)

        if (opts.hideWhenIdle && !snapshot.isPlaying) {
            return bmp
        }

        for (el in doc.elements) {
            try {
                drawElement(context, canvas, el, box, scale, snapshot, accent, opts)
            } catch (e: Exception) {
                AppLog.e("Widget", "failed to draw element ${el.id} (${el.type})", e)
            }
        }
        return bmp
    }

    /** The pixel rect an element occupies before rotation — shared with [com.betteraudio.widget.HitGrid]
     *  so tap-cell assignment and the actual paint always agree on where an element visually sits. */
    fun elementRect(el: ElementSpec, box: RectF, scale: Float): RectF = RectF(
        box.left + el.x * scale,
        box.top + el.y * scale,
        box.left + (el.x + el.w) * scale,
        box.top + (el.y + el.h) * scale,
    )

    /** Axis-aligned bounding box of the element's rect AFTER rotation — used for hit-testing
     *  rotatable text/image/shape elements with a tap action, since the underlying RemoteViews hit
     *  grid can only claim axis-aligned cells. Controls never rotate, so this equals [elementRect]
     *  for them. */
    fun elementBoundingBox(el: ElementSpec, box: RectF, scale: Float): RectF {
        val rect = elementRect(el, box, scale)
        if (!el.type.canRotate || el.rotationDeg == 0f) return rect
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rad = Math.toRadians(el.rotationDeg.toDouble())
        val cos = Math.cos(rad)
        val sin = Math.sin(rad)
        val corners = listOf(
            rect.left to rect.top, rect.right to rect.top,
            rect.right to rect.bottom, rect.left to rect.bottom
        )
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for ((x, y) in corners) {
            val dx = x - cx
            val dy = y - cy
            val rx = (cx + dx * cos - dy * sin).toFloat()
            val ry = (cy + dx * sin + dy * cos).toFloat()
            minX = min(minX, rx); maxX = max(maxX, rx)
            minY = min(minY, ry); maxY = max(maxY, ry)
        }
        return RectF(minX, minY, maxX, maxY)
    }

    private fun drawElement(
        context: Context,
        canvas: Canvas,
        el: ElementSpec,
        box: RectF,
        scale: Float,
        snapshot: WidgetSnapshot,
        accent: Int,
        opts: PaintOptions,
    ) {
        val rect = elementRect(el, box, scale)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        canvas.save()
        if (el.type.canRotate && el.rotationDeg != 0f) {
            canvas.rotate(el.rotationDeg, rect.centerX(), rect.centerY())
        }
        val alpha = (el.opacity.coerceIn(0f, 1f) * 255).toInt()
        val layered = alpha < 255
        if (layered) canvas.saveLayerAlpha(RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), alpha)

        when {
            el.type.isControl -> drawControl(context, canvas, el, rect, snapshot, accent, scale)
            el.type.isText -> drawText(el, rect, snapshot, accent, scale, canvas)
            el.type.isImage -> drawImage(context, canvas, el, rect, snapshot, opts, scale)
            el.type.isShape -> drawShape(canvas, el, rect, snapshot, scale)
            el.type.isBackgroundLayer -> drawBackgroundLayerElement(context, canvas, el, rect, snapshot, opts, scale)
        }

        if (layered) canvas.restore()
        canvas.restore()
    }

    // ── Controls ────────────────────────────────────────────────────────────

    private fun drawControl(
        context: Context,
        canvas: Canvas,
        el: ElementSpec,
        rect: RectF,
        snapshot: WidgetSnapshot,
        accent: Int,
        scale: Float,
    ) {
        val sleepActive = snapshot.sleepEndAtElapsedMs > 0L || snapshot.sleepRemainingMs > 0L
        val resId = IconAssets.resFor(el.type, snapshot.isPlaying, sleepActive) ?: return
        val style = el.icon ?: IconStyle()
        val glyphColor = if (style.glyphUsesAccent) accent else style.glyphColor.toInt()

        val glyphRect: RectF
        if (style.container != ContainerShape.NONE) {
            val containerColor = if (style.containerUsesAccent) accent else style.containerColor.toInt()
            val path = ShapePaths.forContainer(style.container, rect, min(rect.width(), rect.height()) * 0.3f)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = containerColor })
            val inset = min(rect.width(), rect.height()) * 0.26f
            glyphRect = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        } else {
            // No container: the glyph fills the rect exactly — zero renderer-added padding, unlike
            // the old system's fixed 16-28% inset stacked on top of the drawable's own inset.
            glyphRect = rect
        }
        drawGlyph(context, canvas, resId, glyphRect, glyphColor)

        if (el.type == ElementType.SLEEP_TIMER && sleepActive && el.showCountdown) {
            val remainingMs = if (snapshot.sleepEndAtElapsedMs > 0L) {
                (snapshot.sleepEndAtElapsedMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            } else snapshot.sleepRemainingMs
            val totalSec = remainingMs / 1000
            val label = String.format("%d:%02d", totalSec / 60, totalSec % 60)
            val labelRect = RectF(rect.left, rect.bottom, rect.right, rect.bottom + rect.height() * 0.45f)
            val paint = TextPaints.paintFor(
                TextStyle(sizeUnits = 0f, shadow = true, align = HorizontalTextAlign.CENTER),
                rect.height() * 0.3f,
                glyphColor
            ).apply { textAlign = Paint.Align.CENTER }
            val metrics = paint.fontMetrics
            val baseline = labelRect.top + (labelRect.height() - (metrics.descent - metrics.ascent)) / 2f - metrics.ascent
            canvas.drawText(label, labelRect.centerX(), baseline, paint)
        }
    }

    private fun drawGlyph(context: Context, canvas: Canvas, resId: Int, rect: RectF, tint: Int) {
        val drawable = ContextCompat.getDrawable(context, resId)?.mutate() ?: return
        drawable.setTint(tint)
        drawable.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        drawable.draw(canvas)
    }

    // ── Text ────────────────────────────────────────────────────────────────

    private fun drawText(el: ElementSpec, rect: RectF, snapshot: WidgetSnapshot, accent: Int, scale: Float, canvas: Canvas) {
        val style = el.text ?: TextStyle()
        val text = textFor(el, snapshot)
        if (text.isBlank()) return
        val color = if (style.usesAccent) accent else style.color.toInt()
        TextPaints.draw(canvas, text, rect, style, style.sizeUnits * scale, color)
    }

    private fun textFor(el: ElementSpec, s: WidgetSnapshot): String = when (el.type) {
        ElementType.BOOK_TITLE -> s.title
        ElementType.AUTHOR -> s.author
        ElementType.CHAPTER_TITLE -> s.chapterTitle
        ElementType.SERIES_NAME -> s.seriesName
        ElementType.SPEED_LABEL -> formatSpeed(s.speed)
        ElementType.TIME_REMAINING_BOOK -> formatDuration((s.bookDurationMs - s.positionMs).coerceAtLeast(0))
        ElementType.TIME_REMAINING_CHAPTER -> formatDuration((s.chapterDurationMs - s.chapterPositionMs).coerceAtLeast(0))
        ElementType.PROGRESS_PERCENT -> "${(bookProgressFraction(s) * 100).toInt()}%"
        ElementType.CUSTOM_TEXT -> el.customText ?: ""
        else -> ""
    }

    private fun bookProgressFraction(s: WidgetSnapshot): Float =
        if (s.bookDurationMs <= 0L) 0f else (s.positionMs.toFloat() / s.bookDurationMs).coerceIn(0f, 1f)

    private fun chapterProgressFraction(s: WidgetSnapshot): Float =
        if (s.chapterDurationMs <= 0L) 0f else (s.chapterPositionMs.toFloat() / s.chapterDurationMs).coerceIn(0f, 1f)

    private fun progressFraction(style: ShapeStyle, s: WidgetSnapshot): Float = when (style.progressSource) {
        ProgressSource.CHAPTER -> chapterProgressFraction(s)
        ProgressSource.BOOK -> bookProgressFraction(s)
    }

    private fun formatSpeed(speed: Float): String {
        val rounded = Math.round(speed * 100) / 100f
        val text = if (rounded == rounded.toInt().toFloat()) {
            rounded.toInt().toString()
        } else {
            rounded.toString().trimEnd('0').trimEnd('.')
        }
        return "${text}×"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val hh = totalSec / 3600
        val mm = (totalSec % 3600) / 60
        val ss = totalSec % 60
        return if (hh > 0) String.format("%d:%02d:%02d", hh, mm, ss) else String.format("%d:%02d", mm, ss)
    }

    // ── Images ──────────────────────────────────────────────────────────────

    private fun drawImage(
        context: Context,
        canvas: Canvas,
        el: ElementSpec,
        rect: RectF,
        snapshot: WidgetSnapshot,
        opts: PaintOptions,
        scale: Float,
    ) {
        val imgStyle = el.image ?: ImageStyle()
        val path = when (el.type) {
            ElementType.BOOK_COVER -> snapshot.bookCoverPath ?: opts.defaultCoverPath
            ElementType.SERIES_COVER -> snapshot.seriesCoverPath ?: snapshot.bookCoverPath ?: opts.defaultCoverPath
            ElementType.CUSTOM_IMAGE -> el.imagePath
            else -> null
        }
        val reqW = rect.width().toInt().coerceAtLeast(1)
        val reqH = rect.height().toInt().coerceAtLeast(1)
        val src = WidgetBitmapCache.decodeFile(path, reqW, reqH)
        val clipPath = ShapePaths.roundedRect(rect, imgStyle.cornerRadius * scale)

        canvas.save()
        canvas.clipPath(clipPath)
        if (src != null) {
            val fitted = fitBitmap(src, reqW, reqH, imgStyle.fit)
            val left = rect.left + (rect.width() - fitted.width) / 2f
            val top = rect.top + (rect.height() - fitted.height) / 2f
            canvas.drawBitmap(fitted, left, top, null)
        } else {
            drawCoverPlaceholder(context, canvas, rect)
        }
        canvas.restore()

        if (imgStyle.borderWidth > 0f) {
            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = imgStyle.borderWidth * scale
                color = imgStyle.borderColor.toInt()
            }
            canvas.drawPath(clipPath, borderPaint)
        }
    }

    private fun fitBitmap(src: Bitmap, w: Int, h: Int, fit: ImageFit): Bitmap = when (fit) {
        ImageFit.CONTAIN -> {
            val s = min(w.toFloat() / src.width, h.toFloat() / src.height)
            val sw = (src.width * s).toInt().coerceAtLeast(1)
            val sh = (src.height * s).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, sw, sh, true)
        }
        ImageFit.COVER -> {
            val s = max(w.toFloat() / src.width, h.toFloat() / src.height)
            val sw = (src.width * s).toInt().coerceAtLeast(w)
            val sh = (src.height * s).toInt().coerceAtLeast(h)
            val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
            val x = ((scaled.width - w) / 2).coerceAtLeast(0)
            val y = ((scaled.height - h) / 2).coerceAtLeast(0)
            Bitmap.createBitmap(scaled, x, y, w.coerceAtMost(scaled.width - x), h.coerceAtMost(scaled.height - y))
        }
    }

    // ── Shapes ──────────────────────────────────────────────────────────────

    private fun drawShape(canvas: Canvas, el: ElementSpec, rect: RectF, snapshot: WidgetSnapshot, scale: Float) {
        val style = el.shape ?: ShapeStyle()
        when (el.type) {
            ElementType.RECT -> {
                val path = ShapePaths.forShapeKind(style.kind, rect, style.cornerRadius * scale)
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.fillColor.toInt() })
            }
            ElementType.PROGRESS_BAR -> {
                val fraction = progressFraction(style, snapshot)
                if (style.progressShape == ProgressShape.LINE) {
                    val trackPath = ShapePaths.pill(rect)
                    canvas.drawPath(trackPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.trackColor.toInt() })
                    if (fraction > 0f) {
                        val fillRect = RectF(rect.left, rect.top, rect.left + rect.width() * fraction, rect.bottom)
                        if (fillRect.width() > 0f) {
                            canvas.save()
                            canvas.clipPath(trackPath)
                            canvas.drawRect(fillRect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.fillColorBar.toInt() })
                            canvas.restore()
                        }
                    }
                } else {
                    drawPerimeterProgressBar(canvas, style, rect, fraction, scale)
                }
            }
            else -> {}
        }
    }

    /** RING/SQUARE/ROUNDED_SQUARE progress: a stroked outline track, with the fill drawn as a
     *  partial stroke walking [fraction] of that same outline's perimeter (via PathMeasure) — one
     *  unified recipe for all three non-line shapes. */
    private fun drawPerimeterProgressBar(canvas: Canvas, shapeStyle: ShapeStyle, rect: RectF, fraction: Float, scale: Float) {
        val outline = when (shapeStyle.progressShape) {
            ProgressShape.RING -> ShapePaths.circle(rect)
            ProgressShape.SQUARE -> ShapePaths.roundedRect(rect, 0f)
            ProgressShape.ROUNDED_SQUARE -> ShapePaths.roundedRect(rect, shapeStyle.cornerRadius * scale)
            ProgressShape.LINE -> return
        }
        val strokeWidthPx = shapeStyle.strokeWidth * scale
        canvas.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = shapeStyle.trackColor.toInt()
        })
        if (fraction <= 0f) return
        val measure = android.graphics.PathMeasure(outline, true)
        val length = measure.length
        if (length <= 0f) return
        val fillSegment = android.graphics.Path()
        measure.getSegment(0f, length * fraction.coerceIn(0f, 1f), fillSegment, true)
        canvas.drawPath(fillSegment, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = Paint.Cap.ROUND
            color = shapeStyle.fillColorBar.toInt()
        })
    }

    // ── Background ─────────────────────────────────────────────────────────

    private fun resolveBackgroundCover(bg: BackgroundLayerStyle?, snapshot: WidgetSnapshot, opts: PaintOptions, w: Int, h: Int): Bitmap? {
        if (bg == null) return null
        val path = when (bg.source) {
            BgSource.BOOK_COVER -> snapshot.bookCoverPath
            BgSource.SERIES_COVER -> snapshot.seriesCoverPath ?: snapshot.bookCoverPath
            BgSource.CUSTOM_IMAGE -> bg.imagePath
            else -> return null
        }
        return WidgetBitmapCache.decodeFile(path, w, h) ?: WidgetBitmapCache.decodeFile(opts.defaultCoverPath, w, h)
    }

    /** Resolves a cover bitmap to derive the widget's accent color from: prefers whichever
     *  BACKGROUND_LAYER element (any position — a design can have zero, one, or several) actually
     *  shows a cover image, falling back to the snapshot's own book/series cover so accent-tinted
     *  elements (icons/text using `usesAccent`) still track "what's playing" in designs with no
     *  image background at all. */
    private fun resolveAccentCover(doc: WidgetDesignDoc, snapshot: WidgetSnapshot, opts: PaintOptions, w: Int, h: Int): Bitmap? {
        for (el in doc.elements) {
            val bg = el.backgroundLayer ?: continue
            val cover = resolveBackgroundCover(bg, snapshot, opts, w, h)
            if (cover != null) return cover
        }
        return WidgetBitmapCache.decodeFile(snapshot.bookCoverPath, w, h)
            ?: WidgetBitmapCache.decodeFile(snapshot.seriesCoverPath, w, h)
            ?: WidgetBitmapCache.decodeFile(opts.defaultCoverPath, w, h)
    }

    private fun drawCoverPlaceholder(context: Context, canvas: Canvas, rect: RectF) {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.apply {
            setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
            draw(canvas)
        }
    }

    /** A BACKGROUND_LAYER element — an ordinary freely-positioned decorative fill/shape, drawn
     *  within its own [rect] with its own independent cover/color/gradient/dim/blur/opacity; its
     *  [BackgroundLayerStyle.shapeKind] clips only its own fill, never the whole widget bitmap
     *  (the widget itself is always a plain rectangle). */
    private fun drawBackgroundLayerElement(
        context: Context,
        canvas: Canvas,
        el: ElementSpec,
        rect: RectF,
        snapshot: WidgetSnapshot,
        opts: PaintOptions,
        scale: Float,
    ) {
        val style = el.backgroundLayer ?: BackgroundLayerStyle()
        val reqW = rect.width().toInt().coerceAtLeast(1)
        val reqH = rect.height().toInt().coerceAtLeast(1)
        val cover = resolveBackgroundCover(style, snapshot, opts, reqW, reqH)
        val path = ShapePaths.forShapeKind(style.shapeKind, rect, style.cornerRadius * scale)
        val alpha = (style.opacity.coerceIn(0f, 1f) * 255).toInt()

        canvas.save()
        canvas.clipPath(path)
        if (alpha < 255) canvas.saveLayerAlpha(rect, alpha)

        when (style.source) {
            BgSource.BOOK_COVER, BgSource.SERIES_COVER, BgSource.CUSTOM_IMAGE -> {
                if (cover != null) {
                    val fitted = fitBitmap(cover, reqW, reqH, ImageFit.COVER)
                    val blurred = if (style.blurRadius > 0f) BlurUtil.blur(fitted, BlurUtil.clampRadius(style.blurRadius * scale, max(reqW, reqH))) else fitted
                    canvas.drawBitmap(blurred, rect.left, rect.top, null)
                } else {
                    drawCoverPlaceholder(context, canvas, rect)
                }
            }
            BgSource.SOLID -> canvas.drawColor(style.color.toInt())
            BgSource.GRADIENT -> {
                val end = style.colorEnd ?: style.color
                canvas.drawRect(rect, gradientPaint(style.color.toInt(), end.toInt(), style.gradientAngleDeg, rect))
            }
            BgSource.TRANSPARENT -> {}
        }

        if (style.dim > 0f) {
            canvas.drawColor(Color.argb((style.dim.coerceIn(0f, 0.8f) * 255).toInt(), 0, 0, 0))
        }

        if (alpha < 255) canvas.restore()
        canvas.restore()
    }

    private fun gradientPaint(start: Int, end: Int, angleDeg: Float, rect: RectF): Paint {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = (Math.cos(rad) * rect.width() / 2).toFloat()
        val dy = (Math.sin(rad) * rect.height() / 2).toFloat()
        val cx = rect.centerX()
        val cy = rect.centerY()
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, start, end, Shader.TileMode.CLAMP)
        }
    }

    private fun resolveAccent(cover: Bitmap?, fallback: Int): Int {
        if (cover == null) return fallback
        return try {
            val palette = Palette.from(cover).maximumColorCount(16).generate()
            val c = palette.getVibrantColor(0).takeIf { it != 0 }
                ?: palette.getLightVibrantColor(0).takeIf { it != 0 }
                ?: palette.getDominantColor(fallback)
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(c, hsl)
            hsl[1] = max(hsl[1], 0.45f)
            hsl[2] = hsl[2].coerceIn(0.55f, 0.72f)
            ColorUtils.HSLToColor(hsl)
        } catch (e: Exception) {
            AppLog.e("Widget", "palette extraction failed", e)
            fallback
        }
    }
}
