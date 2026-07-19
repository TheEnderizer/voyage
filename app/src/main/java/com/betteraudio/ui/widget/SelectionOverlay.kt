package com.betteraudio.ui.widget

import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.render.WidgetPainter
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Selection + transform overlay. Redesigned to fix the "dragging the body resizes instead of
 * moving" bug: the old version stacked four 20 dp resize-handle children, each with its own drag
 * gesture, directly on the element's corners — on a small element those handles blanketed almost
 * the entire touch area, so the center drag (move) rarely won the gesture.
 *
 * Now there is a SINGLE drag gesture on one overlay node. Where the drag STARTS decides the mode
 * (rotate / resize-corner / move) via nearest-anchor hit-testing, and the resize/rotate handles
 * sit fully OUTSIDE the element's bounds — so the element body is, unambiguously and at any size, a
 * move target. No snapping anywhere (the editor's core requirement).
 */
private enum class DragMode { MOVE, ROTATE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

@Composable
fun SelectionOverlay(
    viewModel: WidgetEditorViewModel,
    element: ElementSpec,
    box: RectF,
    scale: Float,
) {
    val density = LocalDensity.current
    val rectPx = remember(element.x, element.y, element.w, element.h, box, scale) {
        WidgetPainter.elementRect(element, box, scale)
    }
    val w = rectPx.width()
    val h = rectPx.height()

    // Uniform padding around the element so handles live in the margin, never over the body.
    // Uniform (not extra-top) so the overlay's center coincides with the element center, letting a
    // plain center-pivot rotation track the rendered element exactly.
    val pad = with(density) { 40.dp.toPx() }
    val handleOut = with(density) { 15.dp.toPx() }   // how far a resize anchor sits past a corner
    val rotOut = with(density) { 30.dp.toPx() }      // how far the rotate anchor sits above the top
    val hitR = with(density) { 22.dp.toPx() }        // anchor hit radius

    // Element rect expressed in the overlay's own (un-rotated) local pixel space.
    val elL = pad; val elT = pad; val elR = pad + w; val elB = pad + h
    val cx = pad + w / 2f

    val resizeAnchors = remember(w, h) {
        mapOf(
            DragMode.RESIZE_TL to Offset(elL - handleOut, elT - handleOut),
            DragMode.RESIZE_TR to Offset(elR + handleOut, elT - handleOut),
            DragMode.RESIZE_BL to Offset(elL - handleOut, elB + handleOut),
            DragMode.RESIZE_BR to Offset(elR + handleOut, elB + handleOut),
        )
    }
    val rotAnchor = Offset(cx, elT - rotOut)
    val rotatable = element.type.canRotate

    var mode by remember { mutableStateOf(DragMode.MOVE) }

    Box(
        Modifier
            .offset { IntOffset((rectPx.left - pad).roundToInt(), (rectPx.top - pad).roundToInt()) }
            .size(
                with(density) { (w + 2 * pad).toDp() },
                with(density) { (h + 2 * pad).toDp() },
            )
            // Rotate around the element center (== overlay center, thanks to uniform pad) so the
            // overlay stays aligned with the rendered, rotated element.
            .graphicsLayer { rotationZ = element.rotationDeg }
            .pointerInput(element.id, w, h, scale, rotatable) {
                detectDragGestures(
                    onDragStart = { p ->
                        mode = classify(p, resizeAnchors, rotAnchor, rotatable, hitR)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        when (mode) {
                            DragMode.MOVE -> viewModel.moveSelected(amount.x / scale, amount.y / scale)
                            DragMode.ROTATE -> viewModel.rotateBy(amount.x * 0.4f)
                            DragMode.RESIZE_TL -> viewModel.resizeSelected(ResizeCorner.TOP_LEFT, amount.x / scale, amount.y / scale)
                            DragMode.RESIZE_TR -> viewModel.resizeSelected(ResizeCorner.TOP_RIGHT, amount.x / scale, amount.y / scale)
                            DragMode.RESIZE_BL -> viewModel.resizeSelected(ResizeCorner.BOTTOM_LEFT, amount.x / scale, amount.y / scale)
                            DragMode.RESIZE_BR -> viewModel.resizeSelected(ResizeCorner.BOTTOM_RIGHT, amount.x / scale, amount.y / scale)
                        }
                    },
                    onDragEnd = { viewModel.endContinuousEdit() },
                    onDragCancel = { viewModel.endContinuousEdit() },
                )
            }
    ) {
        // Selection border, drawn on the element region only (inside the padded overlay).
        Box(
            Modifier
                .offset { IntOffset(pad.roundToInt(), pad.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .border(2.dp, MaterialTheme.colorScheme.primary)
        )

        // Visual-only handle dots (all gestures handled by the single parent pointerInput above).
        resizeAnchors.values.forEach { a -> HandleDot(a, density, MaterialTheme.colorScheme.primary) }
        if (rotatable) HandleDot(rotAnchor, density, MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun HandleDot(center: Offset, density: androidx.compose.ui.unit.Density, color: androidx.compose.ui.graphics.Color) {
    val d = with(density) { 16.dp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((center.x - d / 2f).roundToInt(), (center.y - d / 2f).roundToInt()) }
            .size(with(density) { d.toDp() })
            .clip(CircleShape)
            .background(color)
            .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
    )
}

private fun classify(
    p: Offset,
    resizeAnchors: Map<DragMode, Offset>,
    rotAnchor: Offset,
    rotatable: Boolean,
    hitR: Float,
): DragMode {
    if (rotatable && dist(p, rotAnchor) <= hitR) return DragMode.ROTATE
    var best: DragMode? = null
    var bestD = hitR
    for ((m, a) in resizeAnchors) {
        val d = dist(p, a)
        if (d <= bestD) { bestD = d; best = m }
    }
    if (best != null) return best
    // Anything else — including the element body and the empty margin — moves.
    return DragMode.MOVE
}

private fun dist(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)
