package com.betteraudio.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.betteraudio.data.db.entities.BookStatus
import com.betteraudio.ui.immersive.components.ImmersiveInfoPageFrame
import com.betteraudio.ui.material.components.MaterialInfoPageFrame
import com.betteraudio.ui.material.motion.LocalVoyageMotion
import com.betteraudio.ui.player.LocalCoverBoundsRegistry
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The shared info page — ONE implementation behind both the Book Info page
 * ([com.betteraudio.ui.bookinfo.BookInfoScreen]) and the Series page
 * ([com.betteraudio.ui.series.SeriesDetailScreen]).
 *
 * The two pages were previously four separate screen files (book/series × immersive/material) that
 * were deliberate copies of each other, so every visual change had to be made four times and
 * silently drifted when it wasn't (the Series page had missed the container-reveal treatment and
 * still used the two-writer predictive-back recipe that Book Info documents as broken). Everything
 * that is genuinely the same — the cover morph out of the tapped grid card, the reveal, the
 * backdrop, the top bar, the [BookInfoPanel] — now lives here and in the two theme frames; the
 * callers supply only what actually differs: their data ([InfoPageData]), their overflow items, and
 * anything they draw below the panel or over the page.
 *
 * The theme frames follow CLAUDE.md's split convention: [ImmersiveInfoPageFrame] (blurred cover
 * backdrop, scrim text) and [MaterialInfoPageFrame] (opaque tonal background, rounded cover card,
 * portrait + landscape). This file owns everything that is NOT visual — the morph animation, the
 * [com.betteraudio.ui.player.CoverBoundsRegistry] registration and predictive back — so those can
 * never diverge between the two looks either.
 */

/** Which half of [com.betteraudio.ui.player.CoverBoundsRegistry] the page morphs out of: the book
 *  maps or the series maps. The registry keeps them separate because a book id and a series id can
 *  collide. */
enum class InfoPageKind { BOOK, SERIES }

/** Everything the shared frame draws. Deliberately plain data — the actions are separate params of
 *  [InfoPageScaffold], so this stays equatable and doesn't defeat skipping. */
@Immutable
data class InfoPageData(
    /** Small caps label in the top bar: "BOOK" / "SERIES". */
    val kindLabel: String,
    val coverPath: String?,
    /** Pre-baked cover effect image, Immersive's backdrop only (`coverFxPath`). */
    val bakedCoverPath: String? = null,
    /** Coil memory-cache key, so the page and the grid card share one decoded bitmap during the
     *  morph. Null just means "no shared key". */
    val coverCacheKey: String? = null,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val seriesLabel: String? = null,
    val status: BookStatus? = null,
    val progressFraction: Float = 0f,
    val totalMs: Long = 0L,
    val synopsis: String? = null,
)

/**
 * Handle on the open/close morph. Created by the caller ([rememberInfoPageState]) rather than
 * internally so a screen can drive the close itself — Book Info's Resume button has to shrink the
 * page back onto the grid card *before* the player opens, or the two animations fight.
 */
@Stable
class InfoPageState internal constructor(private val scope: CoroutineScope) {
    /** 0 = sitting exactly on the tapped grid card, 1 = fully open. */
    internal val openAnim = Animatable(0f)
    val openProgress: State<Float> = derivedStateOf { openAnim.value }

    /** Set by [InfoPageScaffold] from the active theme's motion tokens. */
    internal var closeSpec: AnimationSpec<Float> = spring()

    /** Reverse the open morph back onto the grid card, then run [then]. */
    fun closeWithMorph(then: () -> Unit) {
        scope.launch {
            openAnim.animateTo(0f, closeSpec)
            then()
        }
    }
}

@Composable
fun rememberInfoPageState(): InfoPageState {
    val scope = rememberCoroutineScope()
    return remember(scope) { InfoPageState(scope) }
}

/**
 * @param morphId book id or series id, per [kind] — the grid card this page morphs out of.
 * @param backEnabled false while the caller has something else that must consume back first (the
 *        Series page's books panel). The page's own predictive-back close is disabled then.
 * @param contentModifier applied to the info column/row — the Series page hangs its
 *        swipe-up-for-books drag gesture here.
 * @param overflowItems rows for the ⋮ menu; the frame owns the menu itself (its fill differs per
 *        theme) and hands back a `dismiss` to close it.
 * @param belowPanel drawn under [BookInfoPanel] — the Series page's "Swipe up for books" pill.
 */
@Composable
fun InfoPageScaffold(
    state: InfoPageState,
    morphId: Long,
    kind: InfoPageKind,
    data: InfoPageData,
    onBack: () -> Unit,
    onResume: () -> Unit,
    backEnabled: Boolean = true,
    contentModifier: Modifier = Modifier,
    overflowItems: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit = {},
    belowPanel: @Composable ColumnScope.() -> Unit = {},
) {
    val theme = LocalAppTheme.current
    val motion = LocalVoyageMotion.current
    // Motion is the one thing the frames don't own: Material You springs come from its own tokens,
    // Immersive keeps the hand-tuned pair the pages have always used.
    val openSpec = when (theme) {
        AppTheme.IMMERSIVE -> spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
        AppTheme.MATERIAL_YOU -> motion.spatialDefault
    }
    val closeSpec = when (theme) {
        AppTheme.IMMERSIVE -> spring<Float>(dampingRatio = 0.9f, stiffness = 400f)
        AppTheme.MATERIAL_YOU -> motion.spatialDefault
    }
    SideEffect { state.closeSpec = closeSpec }

    val scope = rememberCoroutineScope()
    val openAnim = state.openAnim
    val openProgress = state.openProgress

    val registry = LocalCoverBoundsRegistry.current
    LaunchedEffect(Unit) { openAnim.animateTo(1f, openSpec) }
    LaunchedEffect(morphId, kind) {
        when (kind) {
            InfoPageKind.BOOK -> registry.setActiveMorph(morphId, openProgress)
            InfoPageKind.SERIES -> registry.setActiveSeriesMorph(morphId, openProgress)
        }
    }
    DisposableEffect(kind) {
        onDispose {
            when (kind) {
                InfoPageKind.BOOK -> registry.setActiveMorph(-1L, null)
                InfoPageKind.SERIES -> registry.setActiveSeriesMorph(-1L, null)
            }
        }
    }
    val coverSource = remember(morphId, kind) {
        when (kind) {
            InfoPageKind.BOOK -> registry.boundsState(morphId)
            InfoPageKind.SERIES -> registry.seriesBoundsState(morphId)
        }
    }
    val coverSourceRadius = when (kind) {
        InfoPageKind.BOOK -> registry.radiusFor(morphId)
        InfoPageKind.SERIES -> registry.seriesRadiusFor(morphId)
    }

    // Predictive back drives the open morph backwards, all from ONE coroutine.
    //
    // It used to be a `rememberPredictiveBackProgress` whose value a `LaunchedEffect` mirrored into
    // `openAnim.snapTo(...)`. That's two independent writers of one Animatable, and Animatable
    // serializes mutations through a MutatorMutex — the last gesture frame's snapTo routinely
    // landed *after* the commit handler had started its closing `animateTo` and cancelled it, which
    // killed the `onBack()` sequenced behind it. The page collapsed onto the card but the overlay
    // stayed mounted, eating touches: back appeared not to close the screen at all. (Book Info was
    // fixed this way; the Series page still had the broken version until both moved here.)
    //
    // Here the collect, the closing spring and `onBack()` are one sequence, so nothing can preempt
    // them, and the spring starts from wherever the finger left off instead of restarting at 1.
    PredictiveBackHandler(enabled = backEnabled) { events ->
        var committed = false
        try {
            events.collect { event -> openAnim.snapTo(1f - event.progress) }
            committed = true
        } catch (_: CancellationException) {
            // Gesture abandoned — fall through to the re-open below.
        }
        // Deliberately OUTSIDE the try/catch and non-suspending, so it still runs if this handler's
        // own coroutine is being cancelled as the back dispatch tears down. Both branches hand off
        // to the screen's scope, which outlives that dispatch.
        if (committed) {
            scope.launch {
                // `finally`, not a plain sequence: if anything ever preempts this spring the
                // overlay must STILL close. Leaving it mounted is the worst failure mode here — it
                // is invisible at progress 0, so the app looks closed while the nav pill stays
                // hidden and the grid card keeps its cover suppressed.
                try {
                    openAnim.animateTo(0f, closeSpec)
                } finally {
                    onBack()
                }
            }
        } else {
            scope.launch { openAnim.animateTo(1f, closeSpec) }
        }
    }

    val closeWithMorph = { state.closeWithMorph(onBack) }

    when (theme) {
        AppTheme.IMMERSIVE -> ImmersiveInfoPageFrame(
            data, openProgress, coverSource, coverSourceRadius,
            closeWithMorph, onResume, contentModifier, overflowItems, belowPanel
        )
        AppTheme.MATERIAL_YOU -> MaterialInfoPageFrame(
            data, openProgress, coverSource, coverSourceRadius,
            closeWithMorph, onResume, contentModifier, overflowItems, belowPanel
        )
    }
}
