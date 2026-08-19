package com.betteraudio.ui.immersive

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The state behind Immersive Home's "clear window" into the app backdrop.
 *
 * Home does not draw its own copy of the cover any more. The app-wide backdrop
 * ([com.betteraudio.ui.components.AppBlurredBackdrop]) is one continuous image, and the top of the
 * library is simply a region where that *same* image is rendered sharp instead of blurred — so it
 * reads as the background coming into focus, not as a second picture pasted on top. Scrolling
 * ramps that region's blur up to the backdrop's own, at which point the window has become the
 * background and disappears without ever moving.
 *
 * Alignment is the whole trick, and it is why this is driven from the backdrop rather than from a
 * composable inside the grid: the sharp copy is the *same composable with the same modifier chain*
 * as the blurred one, drawn in the same place, so there is no position or scale maths that could
 * drift and betray it as a separate image.
 *
 * Every field here is written on scroll frames and read only inside deferred draw lambdas — never
 * in composition — with the single exception of the quantised blur radius (see
 * `AppBlurredBackdrop`), which cannot be deferred because `Modifier.blur` takes a plain Dp.
 */
class HeroWindowState {
    /**
     * Whether Home has a book to feature. Set by the header.
     *
     * Deliberately separate from [routeAllows]: this one follows composition, and Home stays
     * composed until its exit animation finishes. Hanging the whole window off it meant leaving
     * Settings' route meant the sharp region and the darkening both vanished a beat LATE, which
     * read as a stutter partway through the transition.
     */
    var hasHero by mutableStateOf(false)

    /**
     * Whether the current destination should show a window at all. Written from MainActivity the
     * instant the route changes, so the window goes exactly when the navigation animation starts
     * rather than when Home is finally torn down.
     */
    var routeAllows by mutableStateOf(false)

    /** True only while Immersive Home is on screen; every other route gets the plain backdrop. */
    val active: Boolean get() = hasHero && routeAllows

    /** 0 = window fully sharp (unscrolled), 1 = window fully blurred, i.e. gone. */
    val scrolledFraction = mutableFloatStateOf(0f)

    /** Bottom of the sharp region in root pixels, captured at rest so the window blurs in place
     *  instead of also shrinking as the header scrolls. 0 until the header has been laid out. */
    val windowBottomPx = mutableFloatStateOf(0f)

    /** Bottom of the darkening in root pixels. Published by the status-tab row on every scroll
     *  frame, so the dark band travels with the content it belongs to and always ends just under
     *  the tabs rather than at a fixed screen position. */
    val scrimBottomPx = mutableFloatStateOf(0f)

    fun reset() {
        hasHero = false
        scrolledFraction.floatValue = 0f
        windowBottomPx.floatValue = 0f
        scrimBottomPx.floatValue = 0f
    }
}

/** Provided once from MainActivity; written by Immersive Home, read by the app backdrop. */
val LocalHeroWindow = compositionLocalOf { HeroWindowState() }

/**
 * Whether [coverPath]'s artwork is light enough to need darkening behind Home's hero copy.
 *
 * The scrim exists so the title and status tabs stay readable. A dark cover already provides that
 * on its own, and darkening it further just muddies artwork that was fine — so the band is only
 * drawn over light covers. Decoded tiny and averaged off the main thread; the answer changes about
 * once per book.
 */
@Composable
fun rememberCoverIsLight(coverPath: String?): Boolean {
    var isLight by remember(coverPath) { mutableStateOf(false) }
    LaunchedEffect(coverPath) {
        isLight = withContext(Dispatchers.IO) { coverIsLight(coverPath) }
    }
    return isLight
}

/** Mean perceptual luminance of the cover, thresholded. Cheap: decodes at 1/16 scale. */
private fun coverIsLight(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    return runCatching {
        val file = File(path)
        if (!file.exists()) return false
        val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bmp = BitmapFactory.decodeFile(path, opts) ?: return false
        try {
            var sum = 0.0
            var count = 0
            // Every pixel of an already-tiny bitmap — a few hundred at this sample size.
            for (y in 0 until bmp.height) {
                for (x in 0 until bmp.width) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16 and 0xFF) / 255.0
                    val g = (c shr 8 and 0xFF) / 255.0
                    val b = (c and 0xFF) / 255.0
                    // Rec. 709 luma — matches how bright the eye actually finds the pixel.
                    sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                    count++
                }
            }
            if (count == 0) false else (sum / count) > LIGHT_COVER_THRESHOLD
        } finally {
            bmp.recycle()
        }
    }.getOrDefault(false)
}

/** Above this mean luminance a cover is treated as light. Deliberately below 0.5: a cover only
 *  slightly brighter than mid-grey is already enough to fight near-white text. */
private const val LIGHT_COVER_THRESHOLD = 0.42

/**
 * Masks the sharp backdrop copy down to the window: fully opaque to [state]'s window bottom, then
 * a soft ramp to nothing over [fadePx] so the sharp region never ends on a visible edge.
 *
 * `CompositingStrategy.Offscreen` is required — `BlendMode.DstIn` needs a real layer to punch
 * alpha out of, and without it the mask draws as a black gradient instead of erasing.
 */
fun Modifier.heroWindowMask(state: HeroWindowState, fadePx: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val bottom = state.windowBottomPx.floatValue
        if (bottom <= 0f || size.height <= 0f) return@drawWithContent
        val solid = ((bottom - fadePx) / size.height).coerceIn(0f, 1f)
        val gone = (bottom / size.height).coerceIn(solid, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                solid to Color.Black,
                gone to Color.Transparent,
                1f to Color.Transparent
            ),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * The darkening under Home's hero copy.
 *
 * It used to live inside the 250dp header and therefore stopped dead at its bottom edge, leaving a
 * hard horizontal line across the artwork. It now runs from the top of the screen down past the
 * status-tab row and *fades out* over [fadePx] just below it, so the dark band ends by running out
 * rather than by stopping. Because its extent comes from the tab row's own live position, it
 * scrolls with the content it darkens.
 */
fun Modifier.heroScrim(state: HeroWindowState, color: Color, maxAlpha: Float): Modifier =
    this.drawWithContent {
        drawContent()
        val bottom = state.scrimBottomPx.floatValue
        if (bottom <= 0f || size.height <= 0f) return@drawWithContent
        // Fade the whole band out as the window itself goes, so a scrolled-away hero doesn't
        // leave a dark cap hanging at the top of the screen.
        val strength = maxAlpha * (1f - state.scrolledFraction.floatValue).coerceIn(0f, 1f)
        if (strength <= 0.001f) return@drawWithContent
        val fadeStart = ((bottom - fadePxFor(bottom)) / size.height).coerceIn(0f, 1f)
        val end = (bottom / size.height).coerceIn(fadeStart, 1f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to color.copy(alpha = strength),
                fadeStart to color.copy(alpha = strength * 0.72f),
                end to Color.Transparent,
                1f to Color.Transparent
            )
        )
    }

/** The ramp is a share of the band's own length, so a short band doesn't get a fade longer than
 *  itself (which would leave it visibly darker at the very top than intended). */
private fun fadePxFor(bottomPx: Float): Float = (bottomPx * 0.42f).coerceAtMost(340f)
