package com.betteraudio.ui.widget

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.betteraudio.widget.model.WidgetDesignDoc
import com.betteraudio.widget.model.WidgetSnapshot
import com.betteraudio.widget.render.WidgetPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MIN_ZOOM = 0.3f
private const val MAX_ZOOM = 5f

/** Fraction of the workspace the widget frame occupies at zoom = 1 ("fit") — leaves a visible
 *  pasteboard margin around it, photo-editor canvas style. */
private const val FIT_FRACTION = 0.88f

/** The editor's live canvas: a pannable/zoomable workspace (a "pasteboard" filling nearly the whole
 *  screen, not itself shaped like the widget) containing the widget frame — a plain rectangle the
 *  user can freely zoom/pan around, similar to a photo editor's canvas view. The frame renders
 *  through the exact same [WidgetPainter] used for the real widget (so the preview IS the widget
 *  render), with a checkerboard confined to its own bounds for transparency, a dashed outline
 *  tracing its real size (editor-only — never part of the actual render), a tap-to-select/deselect
 *  layer, and (for the selected element) the drag/resize/rotate overlay and alignment guides. */
@Composable
fun EditorCanvas(
    viewModel: WidgetEditorViewModel,
    doc: WidgetDesignDoc,
    /** The design's native aspect — determines element layout (design-unit content box) AND the
     *  widget frame's own shape (always a plain rectangle). */
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
    var workspaceSizePx by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    fun clampPan(raw: Offset): Offset {
        // Generous margin rather than clamping the frame to the viewport's own edges — the whole
        // point of the pannable workspace is letting the frame move away from center, so it's
        // bounded only loosely, enough that a fling can't lose it off-screen entirely.
        val maxX = workspaceSizePx.width.toFloat()
        val maxY = workspaceSizePx.height.toFloat()
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { workspaceSizePx = it }
            .background(MaterialTheme.colorScheme.surfaceVariant)
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
                            pan = clampPan(pan + (currCentroid - prevCentroid))
                            pressed.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The widget frame — sized to fit FIT_FRACTION of the workspace at zoom = 1, then
        // scaled/translated as a single unit by pinch-zoom/pan. Always a plain rectangle; the
        // widget's outer shape is no longer background-defined (backgrounds are ordinary movable
        // elements now — see WidgetPainter/WidgetEditorViewModel).
        Box(
            Modifier
                .fillMaxSize(FIT_FRACTION)
                .aspectRatio(layoutAspect)
                .onSizeChanged { sizePx = it }
                .graphicsLayer {
                    scaleX = zoom; scaleY = zoom
                    translationX = pan.x; translationY = pan.y
                }
                .clipToBounds()
                .checkerboard()
        ) {
            if (sizePx.width > 0 && sizePx.height > 0) {
                // Content box uses the DESIGN aspect against the actual frame pixels — matches the
                // frame exactly, so elements sit in the same region the real widget uses and
                // gestures map to it exactly.
                val box = remember(sizePx, layoutAspect) { WidgetPainter.contentBox(sizePx.width, sizePx.height, layoutAspect) }
                val scale = remember(box) { WidgetPainter.unitScale(box) }

                // WidgetPainter.paint() is genuinely expensive (cover decode/scale, Palette color
                // extraction) and doc changes on every single drag delta — running it synchronously
                // in `remember` blocked the main thread each frame and was the main source of the
                // editor's stutter. Rendering it on a background dispatcher keeps the last frame on
                // screen while the next one is computed, so drag/pinch stay smooth even if a render
                // or two falls behind.
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

                WidgetFrameOutline(
                    strokeWidthPx = with(density) { 2.dp.toPx() },
                    modifier = Modifier.fillMaxSize(),
                )

                // Tap-to-select/deselect layer. Keyed on stable (box, scale) only — NOT
                // doc.elements — so it doesn't relaunch every frame while an element is being
                // dragged; the current element list is read fresh from the ViewModel at tap time.
                Box(
                    Modifier.fillMaxSize().pointerInput(box, scale) {
                        detectTapGestures(
                            onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                            onTap = { offset ->
                                val elements = viewModel.state.value.doc.elements
                                val hit = elements.asReversed().firstOrNull { el ->
                                    WidgetPainter.elementBoundingBox(el, box, scale).contains(offset.x, offset.y)
                                }
                                viewModel.selectElement(hit?.id)
                            }
                        )
                    }
                )

                val selected = doc.elements.find { it.id == selectedElementId }
                if (selected != null) {
                    if (isDragging) {
                        AlignmentGuides(
                            box = box, scale = scale, canvasHeightUnits = canvasHeightUnits,
                            selected = selected, others = doc.elements.filter { it.id != selected.id },
                        )
                    }
                    SelectionOverlay(viewModel = viewModel, element = selected, box = box, scale = scale)
                }
            }
        }
    }
}

/** Dashed outline tracing the widget's real (always-rectangular) bounds — purely an editor aid so
 *  the user can see the final placed size at a glance; this line is never rendered by
 *  [WidgetPainter] and doesn't exist in the real widget. */
@Composable
private fun WidgetFrameOutline(strokeWidthPx: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val rect = RectF(0f, 0f, size.width, size.height)
        val paint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            color = android.graphics.Color.WHITE
            alpha = 170
            isAntiAlias = true
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(16f, 12f), 0f)
        }
        drawContext.canvas.nativeCanvas.drawRect(rect, paint)
    }
}

/** Light tile pattern behind the frame so a transparent background is visibly distinct from an
 *  opaque one while designing — light enough that dark-colored elements stay clearly visible. */
private fun Modifier.checkerboard(tilePx: Float = 24f): Modifier = this.drawBehind {
    val dark = Color(0xFFAEAEB4)
    val light = Color(0xFFC9C9CF)
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
