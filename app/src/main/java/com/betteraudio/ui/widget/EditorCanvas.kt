package com.betteraudio.ui.widget

import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.betteraudio.widget.model.WidgetDesignDoc
import com.betteraudio.widget.model.WidgetSnapshot
import com.betteraudio.widget.render.WidgetPainter

/** The editor's live canvas: renders through the exact same [WidgetPainter] used for the real
 *  widget (so the preview IS the widget render), with a checkerboard behind it for transparency,
 *  a tap-to-select/deselect layer, and (for the selected element) the drag/resize/rotate overlay
 *  and free-floating alignment guides. */
@Composable
fun EditorCanvas(
    viewModel: WidgetEditorViewModel,
    doc: WidgetDesignDoc,
    /** The design's native aspect — determines element layout (design-unit content box). */
    layoutAspect: Float,
    /** The canvas frame aspect being previewed. Equals [layoutAspect] for the native view; when
     *  different, the design is rendered fit-inside with full-bleed background, exactly as the
     *  placed widget reflows when resized to that shape. */
    frameAspect: Float,
    canvasHeightUnits: Float,
    snapshot: WidgetSnapshot,
    selectedElementId: String?,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    var sizePx by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .aspectRatio(frameAspect)
            .onSizeChanged { sizePx = it }
            .checkerboard()
    ) {
        if (sizePx.width > 0 && sizePx.height > 0) {
            // Content box uses the DESIGN aspect against the actual frame pixels, so elements sit
            // in the same fit-inside/letterboxed region the real widget uses — and gestures map to
            // it exactly.
            val box = remember(sizePx, layoutAspect) { WidgetPainter.contentBox(sizePx.width, sizePx.height, layoutAspect) }
            val scale = remember(box) { WidgetPainter.unitScale(box) }

            val bitmap = remember(doc, snapshot, sizePx, accent, layoutAspect) {
                WidgetPainter.paint(
                    context, doc, layoutAspect, snapshot, sizePx.width, sizePx.height,
                    WidgetPainter.PaintOptions(accentFallback = accent)
                )
            }
            Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())

            // Tap-to-select layer. Keyed on stable (box, scale) only — NOT doc.elements — so it
            // doesn't relaunch every frame while an element is being dragged; the current element
            // list is read fresh from the ViewModel at tap time instead.
            Box(
                Modifier.fillMaxSize().pointerInput(box, scale) {
                    detectTapGestures { offset ->
                        val hit = viewModel.state.value.doc.elements.asReversed().firstOrNull { el ->
                            WidgetPainter.elementBoundingBox(el, box, scale).contains(offset.x, offset.y)
                        }
                        viewModel.selectElement(hit?.id)
                    }
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

/** Dark/darker tile pattern behind the canvas so a transparent background is visibly distinct
 *  from an opaque black one while designing. */
private fun Modifier.checkerboard(tilePx: Float = 24f): Modifier = this.drawBehind {
    val dark = androidx.compose.ui.graphics.Color(0xFF1A1A1E)
    val light = androidx.compose.ui.graphics.Color(0xFF242428)
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
