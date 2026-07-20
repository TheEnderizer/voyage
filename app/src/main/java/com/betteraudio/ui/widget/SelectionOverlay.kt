package com.betteraudio.ui.widget

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.render.WidgetPainter
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Selection + transform overlay.
 *
 * This is a **stationary, full-canvas gesture surface** — not a box sized/positioned to the
 * element. That distinction is the fix for two bugs: (1) the earlier version stacked four handle
 * children each with its own drag gesture, which on a small element blanketed the whole touch area
 * so a body drag resized instead of moved; and (2) making the *moving* element its own gesture
 * surface created a self-offset feedback loop — as the element (and thus the gesture node) shifted
 * under the finger, each frame's measured delta was partly cancelled, so it "only moved a little".
 *
 * Here one pointerInput lives on a node that never moves (the canvas). Where a drag STARTS decides
 * the mode (rotate / resize-corner / move) by nearest-anchor hit-testing against the element's
 * current bounds, read fresh from the ViewModel. Drag deltas are in stable canvas pixels, so
 * movement tracks the finger exactly. No snapping (the editor's core requirement).
 */
private enum class DragMode { MOVE, ROTATE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

@Composable
fun SelectionOverlay(
    viewModel: WidgetEditorViewModel,
    element: ElementSpec,
    box: RectF,
    scale: Float,
    /** True only for the design's base background layer (index 0, BACKGROUND_LAYER) — it's
     *  forced full-bleed by the painter regardless of its stored bounds, so move/resize/rotate
     *  would be misleading. Shows a plain outline and nothing else; edit its look via the style
     *  panel instead. */
    isBaseLayer: Boolean = false,
) {
    val density = LocalDensity.current
    val handleOut = with(density) { 13.dp.toPx() }   // resize anchor sits this far past a corner
    val rotOut = with(density) { 34.dp.toPx() }       // rotate anchor sits this far above the top
    val hitR = with(density) { 26.dp.toPx() }         // anchor hit radius

    // Axis-aligned bounds of the element in canvas pixels (== the exact rect when un-rotated).
    val aabb = remember(element.x, element.y, element.w, element.h, element.rotationDeg, box, scale) {
        WidgetPainter.elementBoundingBox(element, box, scale)
    }
    val rotatable = element.type.canRotate

    var mode by remember { mutableStateOf<DragMode?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            // Key only on things that are stable during a drag — NOT x/y/w/h — so a move never
            // restarts the in-flight gesture. Fresh element bounds are read from the VM at drag
            // start instead.
            .let { m ->
                if (isBaseLayer) m else m.pointerInput(element.id, box, scale) {
                    detectDragGestures(
                        onDragStart = { p ->
                            mode = classifyStart(p, viewModel, box, scale, handleOut, rotOut, hitR)
                        },
                        onDrag = { change, amount ->
                            val drag = mode ?: return@detectDragGestures
                            change.consume()
                            when (drag) {
                                DragMode.MOVE -> viewModel.moveSelected(amount.x / scale, amount.y / scale)
                                DragMode.ROTATE -> viewModel.rotateBy(amount.x * 0.4f)
                                DragMode.RESIZE_TL -> viewModel.resizeSelected(ResizeCorner.TOP_LEFT, amount.x / scale, amount.y / scale)
                                DragMode.RESIZE_TR -> viewModel.resizeSelected(ResizeCorner.TOP_RIGHT, amount.x / scale, amount.y / scale)
                                DragMode.RESIZE_BL -> viewModel.resizeSelected(ResizeCorner.BOTTOM_LEFT, amount.x / scale, amount.y / scale)
                                DragMode.RESIZE_BR -> viewModel.resizeSelected(ResizeCorner.BOTTOM_RIGHT, amount.x / scale, amount.y / scale)
                            }
                        },
                        onDragEnd = { if (mode != null) viewModel.endContinuousEdit(); mode = null },
                        onDragCancel = { if (mode != null) viewModel.endContinuousEdit(); mode = null },
                    )
                }
            }
    ) {
        // Selection border, positioned at the element's current bounds.
        Box(
            Modifier
                .offset { IntOffset(aabb.left.roundToInt(), aabb.top.roundToInt()) }
                .size(with(density) { aabb.width().toDp() }, with(density) { aabb.height().toDp() })
                .border(2.dp, MaterialTheme.colorScheme.primary)
        )

        if (!isBaseLayer) {
            // Visual-only handle dots (all gestures handled by the single canvas pointerInput above).
            HandleDot(Offset(aabb.left - handleOut, aabb.top - handleOut), density, MaterialTheme.colorScheme.primary)
            HandleDot(Offset(aabb.right + handleOut, aabb.top - handleOut), density, MaterialTheme.colorScheme.primary)
            HandleDot(Offset(aabb.left - handleOut, aabb.bottom + handleOut), density, MaterialTheme.colorScheme.primary)
            HandleDot(Offset(aabb.right + handleOut, aabb.bottom + handleOut), density, MaterialTheme.colorScheme.primary)
            if (rotatable) {
                HandleDot(Offset(aabb.centerX(), aabb.top - rotOut), density, MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun HandleDot(center: Offset, density: Density, color: Color) {
    val d = with(density) { 15.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((center.x - d / 2f).roundToInt(), (center.y - d / 2f).roundToInt()) }
            .size(with(density) { d.toDp() })
            .clip(CircleShape)
            .background(color)
            .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
    )
}

/** Classifies a drag-start canvas point into a transform mode, reading the element's CURRENT
 *  bounds from the ViewModel (so it's correct even after prior moves in the same session). Returns
 *  null when the drag starts on empty canvas, so it becomes a no-op instead of moving the
 *  selection from nowhere. */
private fun classifyStart(
    p: Offset,
    viewModel: WidgetEditorViewModel,
    box: RectF,
    scale: Float,
    handleOut: Float,
    rotOut: Float,
    hitR: Float,
): DragMode? {
    val el = viewModel.state.value.selectedElement ?: return null
    val aabb = WidgetPainter.elementBoundingBox(el, box, scale)

    if (el.type.canRotate) {
        val rot = Offset(aabb.centerX(), aabb.top - rotOut)
        if (dist(p, rot) <= hitR) return DragMode.ROTATE
    }
    val anchors = listOf(
        DragMode.RESIZE_TL to Offset(aabb.left - handleOut, aabb.top - handleOut),
        DragMode.RESIZE_TR to Offset(aabb.right + handleOut, aabb.top - handleOut),
        DragMode.RESIZE_BL to Offset(aabb.left - handleOut, aabb.bottom + handleOut),
        DragMode.RESIZE_BR to Offset(aabb.right + handleOut, aabb.bottom + handleOut),
    )
    var best: DragMode? = null
    var bestD = hitR
    for ((m, a) in anchors) {
        val d = dist(p, a)
        if (d <= bestD) { bestD = d; best = m }
    }
    if (best != null) return best

    // A generous move zone: the element's bounds expanded a little, so grabbing the body (or just
    // outside it) drags. Anywhere else is empty canvas → ignore the drag.
    val pad = handleOut
    if (p.x in (aabb.left - pad)..(aabb.right + pad) && p.y in (aabb.top - pad)..(aabb.bottom + pad)) {
        return DragMode.MOVE
    }
    return null
}

private fun dist(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)
