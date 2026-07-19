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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementSpec
import com.betteraudio.widget.render.WidgetPainter
import kotlin.math.roundToInt

/** Selection outline + drag-to-move + corner resize handles + (for rotatable types) a rotation
 *  handle, all working directly in the same design-unit → pixel space as [WidgetPainter] so the
 *  overlay always tracks the real render exactly. No snapping anywhere — continuous free
 *  positioning per the editor's core requirement. */
@Composable
fun SelectionOverlay(
    viewModel: WidgetEditorViewModel,
    element: ElementSpec,
    box: RectF,
    scale: Float,
) {
    val rectPx = remember(element.x, element.y, element.w, element.h, box, scale) {
        WidgetPainter.elementRect(element, box, scale)
    }
    val widthPx = rectPx.width()
    val heightPx = rectPx.height()

    Box(
        Modifier
            .offset { IntOffset(rectPx.left.roundToInt(), rectPx.top.roundToInt()) }
            .size(with(androidx.compose.ui.platform.LocalDensity.current) { widthPx.toDp() }, with(androidx.compose.ui.platform.LocalDensity.current) { heightPx.toDp() })
            .rotate(element.rotationDeg)
            .border(2.dp, MaterialTheme.colorScheme.primary)
            .pointerInput(element.id) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        viewModel.moveSelected(dragAmount.x / scale, dragAmount.y / scale)
                    },
                    onDragEnd = { viewModel.endContinuousEdit() },
                    onDragCancel = { viewModel.endContinuousEdit() },
                )
            }
    ) {
        ResizeHandle(Alignment.TopStart, ResizeCorner.TOP_LEFT, viewModel, scale)
        ResizeHandle(Alignment.TopEnd, ResizeCorner.TOP_RIGHT, viewModel, scale)
        ResizeHandle(Alignment.BottomStart, ResizeCorner.BOTTOM_LEFT, viewModel, scale)
        ResizeHandle(Alignment.BottomEnd, ResizeCorner.BOTTOM_RIGHT, viewModel, scale)

        if (element.type.canRotate) {
            RotationHandle(element, viewModel)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.ResizeHandle(alignment: Alignment, corner: ResizeCorner, viewModel: WidgetEditorViewModel, scale: Float) {
    Box(
        Modifier
            .align(alignment)
            .offset(x = if (alignment == Alignment.TopStart || alignment == Alignment.BottomStart) (-10).dp else 10.dp,
                y = if (alignment == Alignment.TopStart || alignment == Alignment.TopEnd) (-10).dp else 10.dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
            .pointerInput(corner) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        viewModel.resizeSelected(corner, dragAmount.x / scale, dragAmount.y / scale)
                    },
                    onDragEnd = { viewModel.endContinuousEdit() },
                    onDragCancel = { viewModel.endContinuousEdit() },
                )
            }
    )
}

/** Simplified rotate interaction: drag horizontally to spin the element, rather than tracking a
 *  true orbital angle around the element's center — much simpler to implement correctly and
 *  still an intuitive, common pattern for a compact rotation handle. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.RotationHandle(element: ElementSpec, viewModel: WidgetEditorViewModel) {
    val rotation = remember { mutableStateOf(element.rotationDeg) }
    rotation.value = element.rotationDeg
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(y = (-44).dp)
            .size(22.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary)
            .border(2.dp, MaterialTheme.colorScheme.onTertiary, CircleShape)
            .pointerInput(element.id) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val next = rotation.value + dragAmount.x * 0.5f
                        rotation.value = next
                        viewModel.rotateSelected(next)
                    },
                    onDragEnd = { viewModel.endContinuousEdit() },
                    onDragCancel = { viewModel.endContinuousEdit() },
                )
            }
    )
}
