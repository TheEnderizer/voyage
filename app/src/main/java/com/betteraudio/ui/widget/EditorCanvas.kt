package com.betteraudio.ui.widget

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.ShapeKind
import com.betteraudio.widget.model.WidgetDesignDoc
import com.betteraudio.widget.model.WidgetSnapshot
import com.betteraudio.widget.render.ShapePaths
import com.betteraudio.widget.render.WidgetPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Matches WidgetPainter's private DEFAULT_OUTER_RADIUS — duplicated here purely so the editor-only
 *  shape outline (never touches WidgetPainter/the real render path) can draw the same fallback. */
private const val EDITOR_DEFAULT_OUTER_RADIUS = 60f
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f

/** The editor's live canvas: renders through the exact same [WidgetPainter] used for the real
 *  widget (so the preview IS the widget render), with a checkerboard behind it for transparency,
 *  a tap-to-select/deselect layer, a dashed outline tracing the widget's real outer silhouette
 *  (editor-only — never part of the actual render), pinch-to-zoom/pan, and (for the selected
 *  element) the drag/resize/rotate overlay and free-floating alignment guides. */
@Composable
fun EditorCanvas(
    viewModel: WidgetEditorViewModel,
    doc: WidgetDesignDoc,
    /** The design's native aspect — determines element layout (design-unit content box) AND the
     *  canvas frame shape (the editor no longer previews a different reflow size). */
    layoutAspect: Float,
    canvasHeightUnits: Float,
    snapshot: WidgetSnapshot,
    selectedElementId: String?,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    fun clampPan(raw: Offset, scale: Float): Offset {
        if (scale <= MIN_ZOOM) return Offset.Zero
        val maxX = sizePx.width * (scale - 1f) / 2f
        val maxY = sizePx.height * (scale - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    fun setZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        zoom = clamped
        pan = clampPan(pan, clamped)
    }

    Box(
        modifier
            .aspectRatio(layoutAspect)
            .onSizeChanged { sizePx = it }
            .clipToBounds()
            // Static, unscaled backdrop — deliberately NOT inside the zoomed/panned layer below, so
            // it never needs to redraw as part of that layer's frequent invalidation (every drag
            // delta, every pinch frame) — a meaningful chunk of the stutter this used to cause.
            .checkerboard()
            // Two-finger pinch/pan only — consumes nothing for a single pointer, so element
            // tap/drag (handled by children below) is completely unaffected.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val prevCentroid = pressed.map { it.previousPosition }.reduce { a, b -> a + b } / pressed.size.toFloat()
                            val currCentroid = pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat()
                            val prevSpread = pressed.map { (it.previousPosition - prevCentroid).getDistance() }.average().toFloat()
                            val currSpread = pressed.map { (it.position - currCentroid).getDistance() }.average().toFloat()
                            val zoomDelta = if (prevSpread > 1f) currSpread / prevSpread else 1f
                            val newZoom = (zoom * zoomDelta).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            zoom = newZoom
                            pan = clampPan(pan + (currCentroid - prevCentroid), newZoom)
                            pressed.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                }
        ) {
            if (sizePx.width > 0 && sizePx.height > 0) {
                // Content box uses the DESIGN aspect against the actual frame pixels — matches the
                // frame exactly now that there's no separate preview-size reflow, so elements sit
                // in the same region the real widget uses and gestures map to it exactly.
                val box = remember(sizePx, layoutAspect) { WidgetPainter.contentBox(sizePx.width, sizePx.height, layoutAspect) }
                val scale = remember(box) { WidgetPainter.unitScale(box) }

                // WidgetPainter.paint() is genuinely expensive (cover decode/scale, Palette color
                // extraction) and doc changes on every single drag delta — running it synchronously
                // in `remember` blocked the main thread each frame and was the main source of the
                // editor's stutter, especially now the canvas is much bigger than before. Rendering
                // it on a background dispatcher keeps the last frame on screen while the next one is
                // computed, so drag/pinch stay smooth even if a render or two falls behind.
                var bitmap by remember { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(doc, snapshot, sizePx, accent, layoutAspect) {
                    val result = withContext(Dispatchers.Default) {
                        WidgetPainter.paint(
                            context, doc, layoutAspect, snapshot, sizePx.width, sizePx.height,
                            WidgetPainter.PaintOptions(accentFallback = accent)
                        )
                    }
                    bitmap = result
                }
                bitmap?.let { bmp ->
                    Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                }

                val baseLayer = doc.elements.firstOrNull()?.takeIf { it.type == ElementType.BACKGROUND_LAYER }
                WidgetShapeOutline(
                    shapeKind = baseLayer?.backgroundLayer?.shapeKind ?: ShapeKind.RECT,
                    cornerRadiusUnits = baseLayer?.backgroundLayer?.cornerRadius ?: EDITOR_DEFAULT_OUTER_RADIUS,
                    scale = scale,
                    strokeWidthPx = with(density) { 2.dp.toPx() },
                    modifier = Modifier.fillMaxSize(),
                )

                // Tap-to-select/deselect layer, plus double-tap to reset zoom. Keyed on stable
                // (box, scale) only — NOT doc.elements — so it doesn't relaunch every frame while an
                // element is being dragged; the current element list is read fresh from the
                // ViewModel at tap time instead.
                Box(
                    Modifier.fillMaxSize().pointerInput(box, scale) {
                        detectTapGestures(
                            onDoubleTap = { setZoom(if (zoom > MIN_ZOOM) MIN_ZOOM else 2f) },
                            onTap = { offset ->
                                val elements = viewModel.state.value.doc.elements
                                val base = elements.firstOrNull()?.takeIf { it.type == ElementType.BACKGROUND_LAYER }
                                // The base background layer is always full-bleed, so its bounding
                                // box covers every pixel — excluded here or no tap could ever miss.
                                val hit = elements.asReversed().firstOrNull { el ->
                                    el !== base && WidgetPainter.elementBoundingBox(el, box, scale).contains(offset.x, offset.y)
                                }
                                val newSelection = when {
                                    hit != null -> hit.id
                                    viewModel.state.value.selectedElementId != null -> null
                                    else -> base?.id
                                }
                                viewModel.selectElement(newSelection)
                            }
                        )
                    }
                )

                val selected = doc.elements.find { it.id == selectedElementId }
                if (selected != null) {
                    // The design's base background layer (index 0) is always full-bleed and defines
                    // the widget's own outer shape (see WidgetPainter.paint) — it has no move/resize/
                    // rotate handles, only its style panel is editable.
                    val isBaseLayer = selected.type.isBackgroundLayer && doc.elements.firstOrNull()?.id == selected.id
                    if (isDragging) {
                        AlignmentGuides(
                            box = box, scale = scale, canvasHeightUnits = canvasHeightUnits,
                            selected = selected, others = doc.elements.filter { it.id != selected.id },
                        )
                    }
                    SelectionOverlay(viewModel = viewModel, element = selected, box = box, scale = scale, isBaseLayer = isBaseLayer)
                }
            }
        }
    }
}

/** Dashed outline tracing the widget's real outer silhouette (rect/pill/circle/squircle, per the
 *  base background layer) — purely an editor aid so the user can see the final placed shape at a
 *  glance; this line is never rendered by [WidgetPainter] and doesn't exist in the real widget. */
@Composable
private fun WidgetShapeOutline(
    shapeKind: ShapeKind,
    cornerRadiusUnits: Float,
    scale: Float,
    strokeWidthPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val rect = RectF(0f, 0f, size.width, size.height)
        val path = ShapePaths.forShapeKind(shapeKind, rect, cornerRadiusUnits * scale)
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = android.graphics.Color.WHITE
            alpha = 170
            isAntiAlias = true
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f, 12f), 0f)
        }
        drawContext.canvas.nativeCanvas.drawPath(path, paint)
    }
}

/** Light tile pattern behind the canvas so a transparent background is visibly distinct from an
 *  opaque one while designing — light enough that dark-colored elements stay clearly visible. */
private fun Modifier.checkerboard(tilePx: Float = 24f): Modifier = this.drawBehind {
    val dark = androidx.compose.ui.graphics.Color(0xFFAEAEB4)
    val light = androidx.compose.ui.graphics.Color(0xFFC9C9CF)
    drawRect(dark)
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) 0f else tilePx
        while (x < size.width) {
            drawRect(light, topLeft = Offset(x, y), size = Size(tilePx, tilePx))
            x += tilePx * 2
        }
        y += tilePx
        row++
    }
}

private fun RectF.contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
