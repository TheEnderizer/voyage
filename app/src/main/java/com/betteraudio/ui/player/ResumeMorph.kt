package com.betteraudio.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp

/**
 * The Book Info → player leg of the transition — the modifiers [ResumeChoreography] is authored
 * for. See that object for the shape of the whole thing and where it is storyboarded.
 *
 * Everything here follows the same discipline as [morphFrom] and [elementMotion]: bounds are read
 * from [onGloballyPositioned] into local state, and every per-frame value is read inside a draw- or
 * layer-phase lambda, so a running transition never recomposes or relayouts anything.
 */

/**
 * Paints the play button's accent ground, interpolating it from the Resume button's whole rect.
 *
 * **This replaces `clip(Pill) + background(accent)` on the play button — it is not an addition.**
 * A 268dp-wide pill has to become a 72dp circle, and a transform cannot do that honestly: scaling
 * the button by the ratio of the two widths would carry the play triangle up to four times its size
 * and back down again, so the one glyph that is supposed to stay put would visibly breathe. So the
 * *ground* is redrawn each frame at the interpolated rect and the glyph is only ever translated
 * (see [resumeIconMorph]).
 *
 * The drawing deliberately escapes the button's own 72dp bounds early in the transition — a
 * `drawBehind` is not clipped to them — which is exactly what lets the pill still be pill-shaped.
 * Any `clip` must therefore come AFTER this in the chain, where it wraps only the content.
 *
 * With [source] at [Rect.Zero] — the mini-bar leg, or before the page has published — this draws
 * the plain circle and nothing else, so it is a safe unconditional replacement for the background.
 */
@Composable
fun Modifier.resumePillMorph(
    source: State<Rect>,
    progress: State<Float>,
    fill: Color,
    from: Float = ResumeChoreography.BUTTON_FROM,
    to: Float = ResumeChoreography.BUTTON_TO,
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { own = Rect(it.positionInRoot(), it.size.toSize()) }
        .drawBehind {
            val k = curve(progress.value, from, to)
            val src = source.value
            if (k >= 1f || src == Rect.Zero || own.width <= 0f) {
                drawRoundRect(
                    color = fill,
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                )
                return@drawBehind
            }
            // source is in ROOT coords; this canvas is the button's own local space.
            val l = lerp(src.left - own.left, 0f, k)
            val t = lerp(src.top - own.top, 0f, k)
            val w = lerp(src.width, own.width, k)
            val h = lerp(src.height, own.height, k)
            drawRoundRect(
                color = fill,
                topLeft = Offset(l, t),
                size = Size(w, h),
                cornerRadius = CornerRadius(h / 2f, h / 2f)
            )
        }
}

/**
 * Carries the play glyph from where it sits inside the Resume button to the centre of the circle.
 *
 * Translation only, at a constant size — the counterpart to [resumePillMorph]'s redrawn ground.
 */
@Composable
fun Modifier.resumeIconMorph(
    source: State<Rect>,
    progress: State<Float>,
    from: Float = ResumeChoreography.BUTTON_FROM,
    to: Float = ResumeChoreography.BUTTON_TO,
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { own = Rect(it.positionInRoot(), it.size.toSize()) }
        .graphicsLayer {
            val k = curve(progress.value, from, to)
            val src = source.value
            if (k >= 1f || src == Rect.Zero || own.width <= 0f) {
                translationX = 0f; translationY = 0f
                scaleX = 1f; scaleY = 1f
                return@graphicsLayer
            }
            // Scales as well as travels: the page's glyph is 22dp and the player's is 40dp, and
            // "the play icon morphs into the play icon in the player" means it grows on the way,
            // not that it pops from one size to the other. Uniform, about its own centre, so the
            // triangle never distorts -- and it is the GLYPH that scales, not the button, which is
            // the whole reason the pill's ground is redrawn instead of transformed.
            val s = lerp(src.width / own.width, 1f, k)
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center
            scaleX = s; scaleY = s
            translationX = (src.center.x - own.center.x) * (1f - k)
            translationY = (src.center.y - own.center.y) * (1f - k)
        }
}

/**
 * The travelling copy of the word "Resume".
 *
 * The page's own label is covered by the morphing pill from the first frame, so without this the
 * word would not fade — it would be occluded instantly. The player therefore carries its own copy
 * and fades that out, exactly as it carries a copy of the cover in the other legs. Placed at the
 * page label's position and gone by [ResumeChoreography.LABEL_FADE_TO], well before the pill is
 * narrow enough to clip it.
 */
@Composable
fun Modifier.resumeLabelMorph(
    source: State<Rect>,
    progress: State<Float>,
): Modifier {
    var own by remember { mutableStateOf(Rect.Zero) }
    return this
        .onGloballyPositioned { own = Rect(it.positionInRoot(), it.size.toSize()) }
        .graphicsLayer {
            val raw = progress.value
            alpha = 1f - (raw / ResumeChoreography.LABEL_FADE_TO).coerceIn(0f, 1f)
            val src = source.value
            if (src == Rect.Zero || own.width <= 0f) return@graphicsLayer
            val k = curve(raw, ResumeChoreography.BUTTON_FROM, ResumeChoreography.BUTTON_TO)
            translationX = (src.center.x - own.center.x) * (1f - k)
            translationY = (src.center.y - own.center.y) * (1f - k)
        }
}

/**
 * Picks the leg: travel out of [source] when the player was opened from an info page, or the
 * ordinary [expandReveal] the mini-bar leg has always used.
 *
 * The two legs genuinely differ for these elements — on the mini-bar leg the transport buttons and
 * the utility rail have nothing to come from and are revealed in place; on this one they come out
 * from under the button that was pressed — so this is a real branch, not a parameterisation.
 */
@Composable
fun Modifier.resumeOrReveal(
    resume: Boolean,
    motion: ElementMotion,
    progress: State<Float>,
    source: State<Rect>? = null,
    playRect: State<Rect>? = null,
): Modifier =
    if (resume) elementMotion(motion, progress, source = source, playRect = playRect)
    else expandReveal(progress)

/**
 * Applies [motion] on the resume leg only, leaving the element exactly as it was on the other one.
 *
 * The counterpart of [resumeOrReveal], for elements whose mini-bar behaviour is already handled by
 * something else — a group modifier on their parent, say — rather than by [expandReveal].
 */
@Composable
fun Modifier.resumeOnly(
    resume: Boolean,
    motion: ElementMotion,
    progress: State<Float>,
    source: State<Rect>? = null,
    playRect: State<Rect>? = null,
): Modifier =
    if (resume) elementMotion(motion, progress, source = source, playRect = playRect) else this

/**
 * Hides what the player has taken over.
 *
 * The info page stays composed underneath the opening player — it is the morph's source, so it has
 * to — which means every element the player is carrying is drawn twice: once by the page, sitting
 * still, and once by the player, travelling. They start in the same place, so the double is
 * invisible at first and then separates into a ghost. This drops the page's copy the moment the
 * transition starts, the same trick (and the same threshold) [CoverBoundsRegistry.isMorphHidden]
 * uses for a grid card whose cover is being carried away.
 */
fun Modifier.handedOff(progress: State<Float>): Modifier = graphicsLayer {
    alpha = if (progress.value > 0.02f) 0f else 1f
}

/**
 * Fades out what the player has no use for — the synopsis, the status chip, the percentage row.
 *
 * Owned by the page rather than by the player because the player has no counterpart to fade: these
 * elements simply stop existing. Accelerating and early, so the page is visibly clearing before
 * anything starts travelling through where they were.
 */
fun Modifier.leavesOnResume(progress: State<Float>): Modifier = graphicsLayer {
    val k = (progress.value / ResumeChoreography.LEAVE_TO).coerceIn(0f, 1f)
    alpha = 1f - MorphEasing.Accelerate.transform(k)
}

/**
 * The info page's half of the contract: where its furniture is, and how far the player has opened.
 *
 * Passed down to [com.betteraudio.ui.components.BookInfoPanel] so the panel can publish its own
 * elements as morph sources and get out of the way once the player has taken them over. The default
 * [NoResumeHandoff] is a page that is not a morph source at all — it publishes nothing and never
 * hides anything, which is every other caller of the panel.
 */
@androidx.compose.runtime.Stable
class ResumeHandoff(
    /** The sheet's expansion, 0 while the page is simply being read. */
    val progress: State<Float>,
    val publish: (InfoSlot, Rect) -> Unit,
)

private val zeroProgress = mutableStateOf(0f)
val NoResumeHandoff = ResumeHandoff(zeroProgress) { _, _ -> }

/** Reports this element's bounds as [slot]'s morph source, every layout pass. */
fun Modifier.publishSlot(handoff: ResumeHandoff, slot: InfoSlot): Modifier =
    onGloballyPositioned { handoff.publish(slot, Rect(it.positionInRoot(), it.size.toSize())) }

/** Publishes [slot] AND stands down once the player's own copy is carrying it — the pairing every
 *  element that travels needs. See [handedOff]. */
fun Modifier.travellingSlot(handoff: ResumeHandoff, slot: InfoSlot): Modifier =
    publishSlot(handoff, slot).handedOff(handoff.progress)

/** Shared remap-and-ease for the button's three parts, so they cannot drift apart. */
private fun curve(raw: Float, from: Float, to: Float): Float {
    val local = ((raw - from) / (to - from).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
    return MorphEasing.Standard.transform(local)
}
