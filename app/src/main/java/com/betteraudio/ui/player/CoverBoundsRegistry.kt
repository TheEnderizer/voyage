package com.betteraudio.ui.player

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Publishes on-screen cover bounds + corner radius for library grid cards, keyed by book id, so
 * a cover-morph transition (e.g. grid → Book Info) can read where a specific tapped card's cover
 * sits even when there's no mini-player bar to morph from (nothing was playing yet). Screens that
 * render a cover call [publish] on every layout pass; a transition reads [boundsState]/[radiusFor]
 * for the book it's opening.
 */
@Stable
class CoverBoundsRegistry {
    private val rects = mutableStateMapOf<Long, MutableState<Rect>>()
    private val radii = mutableStateMapOf<Long, Dp>()

    fun publish(bookId: Long, rect: Rect, radius: Dp) {
        rectState(bookId).value = rect
        radii[bookId] = radius
    }

    fun boundsState(bookId: Long): State<Rect> = rectState(bookId)
    fun radiusFor(bookId: Long): Dp = radii[bookId] ?: 0.dp

    private fun rectState(bookId: Long) =
        rects.getOrPut(bookId) { mutableStateOf(Rect.Zero) }
}

val LocalCoverBoundsRegistry = compositionLocalOf { CoverBoundsRegistry() }
