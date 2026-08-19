package com.betteraudio.ui.immersive.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A live capture of everything drawn *behind* the floating glass surfaces, so they can show a
 * blurred copy of what is genuinely underneath them instead of an approximation.
 *
 * Compose has no `backdrop-filter`: a layer cannot sample already-rendered content behind it. What
 * it does have (since 1.7) is [rememberGraphicsLayer] — the app's content can be recorded into a
 * `GraphicsLayer` as it draws, and that same layer can then be drawn *again* somewhere else, with
 * an effect applied. That is what this does:
 *
 *  1. [recordBackdrop] wraps the backdrop + NavHost + overlays. During their draw it records them
 *     into [layer], then draws the layer normally so the screen looks unchanged.
 *  2. A pill calls [backdropGlass], which re-draws that same layer shifted by the pill's own root
 *     position — so the pixels under the pill land under the pill — inside a `graphicsLayer`
 *     carrying an asymmetric [BlurEffect].
 *
 * Because the layer is re-recorded every frame the content draws, a scroll updates it for free:
 * the pill's appearance tracks whatever is passing beneath it with no extra bookkeeping. That is
 * the property the old approach could not have — it smudged a *cover file* chosen by hit-testing
 * card bounds, never the actual rendered pixels.
 *
 * **The pills must not be inside the recorded subtree**, or they would sample themselves and
 * feed back. In `MainActivity` they are siblings drawn after the recorded Box, which is exactly
 * the arrangement this relies on.
 *
 * Gated on API 31 — `BlurEffect` is a `RenderEffect`, unavailable below Android 12 — and on the
 * user's **Settings → Theme → Dynamic pills** toggle, which is what turns the whole thing off.
 * Either way the pills fall back to [GlassPillSurface]'s cover smudge, which needs no RenderEffect
 * at all.
 */
class BackdropCapture(val layer: GraphicsLayer) {
    /** False until the content has drawn at least once; drawing an empty layer paints nothing and
     *  would leave the pill see-through for a frame. */
    var recorded by mutableStateOf(false)
        internal set
}

val LocalBackdropCapture = compositionLocalOf<BackdropCapture?> { null }

/** True where a real backdrop sample is possible at all. */
val backdropGlassSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun rememberBackdropCapture(enabled: Boolean): BackdropCapture? {
    // Unconditional: a composable can't be called behind an `if` that changes across
    // recompositions, and an unused layer is cheap.
    val layer = rememberGraphicsLayer()
    val capture = remember(layer) { BackdropCapture(layer) }
    return if (enabled && backdropGlassSupported) capture else null
}

/** Records the wrapped content into [capture] and draws it unchanged. No-op when capture is off. */
fun Modifier.recordBackdrop(capture: BackdropCapture?): Modifier =
    if (capture == null) this else this.drawWithContent {
        capture.layer.record { this@drawWithContent.drawContent() }
        drawLayer(capture.layer)
        if (!capture.recorded) capture.recorded = true
    }

/**
 * Fills this element with a blurred, vertically-smudged sample of whatever [capture] recorded
 * beneath it.
 *
 * [rootOffset] is the element's own top-left in root coordinates, supplied by the caller and read
 * only here inside the draw lambda — it changes whenever the pill moves (the mini bar lifts and
 * slides with the sheet), and reading it in composition would recompose the pill on every frame of
 * that animation.
 *
 * The blur is deliberately asymmetric — a much larger radius on Y than X — because that *is* the
 * vertical smudge, rather than a symmetric frost. Note the file-level warning in
 * [ImmersiveGlass]: an asymmetric effect combined into a single `graphicsLayer` block alongside a
 * scale rendered completely sharp on the dev device. This applies it in its own layer with nothing
 * else in the block, which is the arrangement that has worked; it still wants checking on a real
 * device before being trusted.
 */
fun Modifier.backdropGlass(
    capture: BackdropCapture,
    rootOffset: () -> Offset,
    blurX: Dp = 14.dp,
    blurY: Dp = 40.dp
): Modifier = this
    .graphicsLayer {
        renderEffect = BlurEffect(blurX.toPx(), blurY.toPx(), TileMode.Clamp)
    }
    .drawBehind {
        if (!capture.recorded) return@drawBehind
        val o = rootOffset()
        translate(left = -o.x, top = -o.y) {
            drawLayer(capture.layer)
        }
    }
