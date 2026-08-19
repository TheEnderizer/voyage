package com.betteraudio.ui.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.lerp as lerpDp
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Data-driven replacement for [morphFrom] / [expandReveal] in the Material You portrait player.
 *
 * Both of those hard-code one recipe — linear curve, straight-line path, uniform scale, one shared
 * fade — so every element moved identically and the only thing a designer could change was the
 * spring. This lets each element carry its own [ElementMotion]: its slice of the sheet progress,
 * its curve, the shape of its travel path, whether it scales, and how it becomes visible.
 *
 * The whole thing stays a pure function of the sheet's progress float, so the transition remains
 * scrubbable and interruptible exactly as before, and every per-frame value is still read inside
 * [graphicsLayer] (draw phase) — nothing here recomposes or relayouts while it animates. See
 * docs/motion-spec.md for the authored values and docs/motion-lab.html to change them.
 *
 * Immersive and the landscape players are untouched and still use [morphFrom]/[expandReveal].
 */

/** Curves. [Overshoot] is deliberately absent from the shipped choreography — the bounce comes
 *  from the driver spring, not from per-element easing (see [PlayerChoreography]). */
enum class MorphEasing { Linear, Standard, Decelerate, Accelerate, Overshoot }

private class CubicBezier(x1: Float, y1: Float, x2: Float, y2: Float) {
    private val cx = 3f * x1; private val bx = 3f * (x2 - x1) - cx; private val ax = 1f - cx - bx
    private val cy = 3f * y1; private val by = 3f * (y2 - y1) - cy; private val ay = 1f - cy - by
    private fun fx(t: Float) = ((ax * t + bx) * t + cx) * t
    private fun fy(t: Float) = ((ay * t + by) * t + cy) * t
    private fun dfx(t: Float) = (3f * ax * t + 2f * bx) * t + cx
    fun transform(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var t = x
        repeat(6) {
            val e = fx(t) - x
            if (abs(e) < 1e-5f) return@repeat
            val d = dfx(t)
            if (abs(d) < 1e-6f) return@repeat
            t -= e / d
        }
        return fy(t.coerceIn(0f, 1f))
    }
}

private val Standard = CubicBezier(0.2f, 0f, 0f, 1f)
private val Decelerate = CubicBezier(0.05f, 0.7f, 0.1f, 1f)
private val Accelerate = CubicBezier(0.3f, 0f, 1f, 1f)

internal fun MorphEasing.transform(p: Float): Float = when (this) {
    MorphEasing.Linear -> p
    MorphEasing.Standard -> Standard.transform(p)
    MorphEasing.Decelerate -> Decelerate.transform(p)
    MorphEasing.Accelerate -> Accelerate.transform(p)
    MorphEasing.Overshoot -> {
        val c = 2.70158f
        1f + c * (p - 1f) * (p - 1f) * (p - 1f) + 1.70158f * (p - 1f) * (p - 1f)
    }
}

/** How an element travels from its source to its resting place. */
sealed interface MorphPath {
    /** Straight line — what [morphFrom] has always done. */
    object Linear : MorphPath
    /** Vertical resolves first, horizontal trails. The play button needs this: on a straight
     *  line it arrives at the transport row from BELOW and never passes over the buttons it is
     *  supposed to deposit. [bias] is how long the horizontal is held back (0.1 barely, 0.95 hard). */
    data class ArcVerticalFirst(val bias: Float = 0.55f) : MorphPath
}

enum class ScaleMode { UniformByHeight, UniformByWidth, Independent, None }
enum class MorphAnchor { Center, TopLeft }

/** How an element becomes visible, when a plain fade is not what is wanted. */
sealed interface Spawn {
    object None : Spawn
    /** Invisible until the play button covers [coverage] of this element's resting rect, then
     *  visible for good — so the play button appears to deposit it as it sweeps past. */
    data class UncoveredBy(val coverage: Float) : Spawn
    /** Starts stacked under the play button's resting centre and slides out to its own spot,
     *  hidden until then by the play button's z-order. For the buttons the play button never
     *  passes over. */
    object SlideOutFromPlay : Spawn
    /** Starts behind the cover (which draws above it) and drops out as the cover settles. */
    object DropFromBehindCover : Spawn
}

/**
 * One element's motion. [from]/[to] are the slice of sheet progress it is active over; everything
 * else describes how it crosses that slice.
 *
 * [rise]/[shrink] synthesise a source rect from the element's own resting bounds for elements with
 * no mini-player counterpart — a negative [rise] starts it ABOVE its resting place (behind the
 * cover), a positive one below. This is what makes [expandReveal] just another [ElementMotion]
 * rather than a second mechanism: its old behaviour is rise 14dp, shrink 0.8, alpha 0.35f..1f.
 */
data class ElementMotion(
    val from: Float = 0f,
    val to: Float = 1f,
    val easing: MorphEasing = MorphEasing.Linear,
    val path: MorphPath = MorphPath.Linear,
    val scale: ScaleMode = ScaleMode.UniformByHeight,
    val scaleEasing: MorphEasing? = null,
    val anchor: MorphAnchor = MorphAnchor.Center,
    /** Alpha window in RAW sheet progress, not in this element's own slice. Null = always opaque. */
    val alpha: ClosedFloatingPointRange<Float>? = null,
    val radius: Pair<Dp, Dp>? = null,
    val rise: Dp = 0.dp,
    val shrink: Float = 1f,
    val inBounceGroup: Boolean = false,
    val spawn: Spawn = Spawn.None,
)

/**
 * How far the mini-player group dips DOWN per unit of driver overshoot.
 *
 * The bounce is a single, uniform downward translation applied to every group member — cover,
 * title, author, play button — so they move as one rigid object. Deliberately NOT a scale:
 * scaling text and an image every frame resamples them at fractional sizes, which is what read as
 * stutter, and a scale about a shared centre also pushes the members apart, which reads as the
 * group inflating rather than landing.
 *
 * The spring peaks around 5.4% overshoot, so the visible dip is roughly 250dp x 0.054 = 13dp.
 */
val BOUNCE_DROP = 250.dp

/** Result of resolving one element at one progress value. Pure — used by the modifier below and
 *  by the coverage sampling that decides the spawn points. */
internal data class MorphSolution(
    val tx: Float, val ty: Float, val sx: Float, val sy: Float, val p: Float,
)

/** Maps raw progress onto this element's slice, PRESERVING overshoot above 1 for elements still in
 *  flight at the end. Clamped at 0 below: the closing spring undershoots, and negative progress
 *  would extrapolate elements straight past the mini bar. */
private fun remapOver(raw: Float, from: Float, to: Float): Float {
    val span = max(1e-4f, to - from)
    val base = ((min(raw, 1f) - from) / span).coerceIn(0f, 1f)
    return if (raw > 1f && to >= 0.999f) base + (raw - 1f) / span else base
}

internal fun solveMorph(motion: ElementMotion, src: Rect, own: Rect, raw: Float): MorphSolution {
    // Group members are capped at their own target; their overshoot is applied once, for the whole
    // group, by the caller. Non-members simply never see raw > 1 in a meaningful way.
    val p0 = min(remapOver(raw, motion.from, motion.to), 1f)
    val p = motion.easing.transform(p0)
    val ps = (motion.scaleEasing ?: motion.easing).transform(p0)

    var sx = 1f; var sy = 1f
    if (own.width > 0f && own.height > 0f) {
        when (motion.scale) {
            ScaleMode.Independent -> {
                sx = lerp(src.width / own.width, 1f, ps); sy = lerp(src.height / own.height, 1f, ps)
            }
            ScaleMode.UniformByWidth -> { sx = lerp(src.width / own.width, 1f, ps); sy = sx }
            ScaleMode.UniformByHeight -> { sx = lerp(src.height / own.height, 1f, ps); sy = sx }
            ScaleMode.None -> {}
        }
    }

    val topLeft = motion.anchor == MorphAnchor.TopLeft
    val dx = if (topLeft) src.left - own.left else src.center.x - own.center.x
    val dy = if (topLeft) src.top - own.top else src.center.y - own.center.y

    val tx: Float; val ty: Float
    when (val path = motion.path) {
        is MorphPath.ArcVerticalFirst -> {
            tx = dx * (1f - CubicBezier(path.bias, 0f, 1f, 1f).transform(p))
            ty = dy * (1f - Decelerate.transform(p))
        }
        MorphPath.Linear -> { tx = dx * (1f - p); ty = dy * (1f - p) }
    }
    return MorphSolution(tx, ty, sx, sy, p)
}

/** On-screen bounds of an element at [raw] — used to work out when the play button covers a
 *  transport button. */
internal fun morphBoundsAt(motion: ElementMotion, src: Rect, own: Rect, raw: Float): Rect {
    val s = solveMorph(motion, src, own, raw)
    val w = own.width * s.sx; val h = own.height * s.sy
    val cx = if (motion.anchor == MorphAnchor.TopLeft) own.left + s.tx + w / 2f else own.center.x + s.tx
    val cy = if (motion.anchor == MorphAnchor.TopLeft) own.top + s.ty + h / 2f else own.center.y + s.ty
    return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/**
 * The progress at which the play button first covers [coverage] of [target] — the moment a
 * [Spawn.UncoveredBy] element becomes visible. Sampled once per layout change (not per frame) and
 * kept as a pure function of the geometry, so scrubbing backwards reproduces exactly what a real
 * open showed. Null when the play button never covers enough, which is a real possibility: next
 * chapter tops out around 47% because the button is still scaling up when it sweeps that far right.
 */
@Composable
internal fun rememberUncoverPoint(
    target: State<Rect>,
    playSource: State<Rect>,
    playOwn: State<Rect>,
    coverage: Float,
): State<Float?> = remember(coverage) {
    derivedStateOf {
        val t = target.value; val src = playSource.value; val own = playOwn.value
        if (t.width <= 0f || own.width <= 0f || src == Rect.Zero) return@derivedStateOf null
        val playMotion = PlayerChoreography.play
        val area = t.width * t.height
        for (i in 0..200) {
            val r = i / 200f
            val b = morphBoundsAt(playMotion, src, own, r)
            val ox = max(0f, min(b.right, t.right) - max(b.left, t.left))
            val oy = max(0f, min(b.bottom, t.bottom) - max(b.top, t.top))
            if (ox * oy / area >= coverage) return@derivedStateOf r
        }
        null
    }
}

/**
 * Applies [motion] to this element.
 *
 * [source] is where it travels FROM in root coords (the mini bar's cover/title/author/play slot).
 * Pass null for elements with no mini-player counterpart — the source is then synthesised from the
 * element's own resting bounds using [ElementMotion.rise]/[ElementMotion.shrink].
 *
 * Members of the bounce group ([ElementMotion.inBounceGroup]) share one downward dip on the
 * driver spring's overshoot — see [BOUNCE_DROP].
 *
 * [playRect] is the play button's resting bounds, needed only by [Spawn.SlideOutFromPlay].
 * [visibleFrom] is the resolved reveal point for [Spawn.UncoveredBy] (see [rememberUncoverPoint]).
 */
@Composable
fun Modifier.elementMotion(
    motion: ElementMotion,
    progress: State<Float>,
    source: State<Rect>? = null,
    playRect: State<Rect>? = null,
    visibleFrom: State<Float?>? = null,
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    val riseDp = motion.rise
    return this
        .onGloballyPositioned {
            val r = Rect(it.positionInRoot(), it.size.toSize())
            if (own != r) own = r
        }
        .graphicsLayer {
            val raw = progress.value.coerceAtLeast(0f)
            if (own.width <= 0f || own.height <= 0f) return@graphicsLayer

            // Where this element travels from.
            val src: Rect = when {
                motion.spawn is Spawn.SlideOutFromPlay && playRect != null && playRect.value != Rect.Zero -> {
                    // Position only: same size as itself, parked on the play button's centre.
                    val c = playRect.value.center
                    Rect(c.x - own.width / 2f, c.y - own.height / 2f,
                         c.x + own.width / 2f, c.y + own.height / 2f)
                }
                source != null && source.value != Rect.Zero -> source.value
                else -> {
                    val s = motion.shrink
                    val w = own.width * s; val h = own.height * s
                    val l = own.left + (own.width - w) / 2f
                    val t = own.top + (own.height - h) / 2f + riseDp.toPx()
                    Rect(l, t, l + w, t + h)
                }
            }
            val eff = if (motion.spawn is Spawn.SlideOutFromPlay)
                motion.copy(scale = ScaleMode.None) else motion
            val s = solveMorph(eff, src, own, raw)
            var tx = s.tx; var ty = s.ty; var sxv = s.sx; var syv = s.sy

            // One rigid dip for the whole group. Every member takes the SAME downward offset, so
            // nothing spreads apart and nothing resamples — the group lands, settles down past its
            // resting place, and springs back as one object.
            if (motion.inBounceGroup && raw > 1f) {
                ty += (raw - 1f) * BOUNCE_DROP.toPx()
            }

            transformOrigin = if (motion.anchor == MorphAnchor.TopLeft)
                TransformOrigin(0f, 0f) else TransformOrigin.Center
            translationX = tx; translationY = ty
            scaleX = sxv; scaleY = syv

            alpha = when {
                motion.spawn is Spawn.UncoveredBy -> {
                    val at = visibleFrom?.value
                    if (at != null && raw >= at) 1f else 0f
                }
                // Nothing occludes these until the play button is home, so gate them on their slice.
                motion.spawn is Spawn.SlideOutFromPlay -> if (raw >= motion.from) 1f else 0f
                motion.alpha != null -> {
                    val a = motion.alpha
                    ((raw - a.start) / max(1e-4f, a.endInclusive - a.start)).coerceIn(0f, 1f)
                }
                else -> 1f
            }

            motion.radius?.let { (fromR, toR) ->
                val onScreen = lerpDp(fromR, toR, s.p.coerceIn(0f, 1f))
                val avg = (sxv + syv) / 2f
                shape = RoundedCornerShape(if (avg > 0.001f) onScreen / avg else onScreen)
                clip = true
            }
        }
}

/**
 * The authored choreography for the Material You portrait player, tuned in docs/motion-lab.html.
 * Recorded in docs/motion-spec.md section 8. Change values there and here together.
 *
 * The bounce is NOT in these entries — it comes from the sheet's driver spring (damping 0.60 for
 * Material You, see PlayerSheet) overshooting past 1.0. The four elements marked [inBounceGroup]
 * absorb that overshoot as one rigid unit.
 */
object PlayerChoreography {
    /** Travels out of the mini cover. The anchor of the whole transition. */
    val cover = ElementMotion(
        anchor = MorphAnchor.TopLeft, scale = ScaleMode.UniformByWidth,
        alpha = 0f..0.5f, radius = 12.dp to 28.dp, inBounceGroup = true,
    )
    val title = ElementMotion(anchor = MorphAnchor.TopLeft, inBounceGroup = true)
    /** Now travels out of the mini bar's new author line instead of fading in place. */
    val author = ElementMotion(anchor = MorphAnchor.TopLeft, inBounceGroup = true)

    /** Arcs up first, then sweeps left along the transport row, depositing the buttons it passes
     *  over. On a straight line it arrives from below and covers nothing — see the lab. */
    val play = ElementMotion(path = MorphPath.ArcVerticalFirst(0.55f), inBounceGroup = true)

    /** Reachable: the play button fully covers skip-forward around progress 0.55. */
    val skipForward = ElementMotion(spawn = Spawn.UncoveredBy(0.70f), scale = ScaleMode.None)
    /** Next chapter tops out near 47% coverage — the button is still scaling up when it sweeps
     *  that far right — so its threshold is deliberately lower. */
    val nextChapter = ElementMotion(spawn = Spawn.UncoveredBy(0.40f), scale = ScaleMode.None)

    /** The play button never reaches the left side, so these slide out from under its resting spot. */
    val skipBack = ElementMotion(
        from = 0.80f, to = 1f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, spawn = Spawn.SlideOutFromPlay,
    )
    val prevChapter = ElementMotion(
        from = 0.84f, to = 1f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, spawn = Spawn.SlideOutFromPlay,
    )

    /** Sits behind the cover (which draws above it) and drops out as the cover settles — the
     *  cover's own rebound is what appears to flick it loose. */
    val chapterPill = ElementMotion(
        from = 0.88f, to = 1f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, rise = (-40).dp, alpha = 0.88f..0.89f,
        spawn = Spawn.DropFromBehindCover,
    )

    /** Everything with no mini-player counterpart, staggered instead of one shared crossfade. */
    val topBar = ElementMotion(from = 0.55f, to = 0.75f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, rise = 14.dp, alpha = 0.55f..0.62f)
    val seekBar = ElementMotion(from = 0.62f, to = 0.82f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, rise = 14.dp, alpha = 0.62f..0.69f)
    val times = ElementMotion(from = 0.66f, to = 0.86f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, rise = 14.dp, alpha = 0.66f..0.73f)
    val secondaryRow = ElementMotion(from = 0.70f, to = 0.90f, easing = MorphEasing.Decelerate,
        scale = ScaleMode.None, rise = 14.dp, alpha = 0.70f..0.77f)
}
