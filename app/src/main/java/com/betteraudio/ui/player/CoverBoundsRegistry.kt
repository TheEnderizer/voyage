package com.betteraudio.ui.player

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
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
    private val coverPaths = mutableStateMapOf<Long, String?>()

    // Which book's grid card cover is currently the source of an active morph, and the sheet's
    // own live expand progress — so the grid card can hide its own (otherwise-still-visible) cover
    // the instant the player's morphing cover starts traveling on top of it, avoiding a visible
    // "two copies of the same cover" duplicate. Set once by PlayerSheet; read (deferred, inside a
    // graphicsLayer) by the grid card itself via [isMorphHidden].
    private val morphBookId = mutableStateOf(-1L)
    private val morphProgress = mutableStateOf<State<Float>?>(null)

    fun publish(bookId: Long, rect: Rect, radius: Dp, coverPath: String? = null) {
        rectState(bookId).value = rect
        radii[bookId] = radius
        coverPaths[bookId] = coverPath
    }

    fun boundsState(bookId: Long): State<Rect> = rectState(bookId)
    fun radiusFor(bookId: Long): Dp = radii[bookId] ?: 0.dp

    /** Called when a grid card leaves composition (scrolls off-screen in a LazyVerticalGrid) so a
     *  stale off-screen rect can't be matched by [coverPathUnder] or morphed from forever. Resets
     *  the existing MutableState's VALUE to [Rect.Zero] rather than removing the map entry —
     *  PlayerSheet and both Book Info screens capture this book's MutableState via `remember` for
     *  as long as its transition is relevant, so replacing the instance here would silently freeze
     *  those readers on a dead object the moment the card scrolls back into view and re-publishes
     *  under a fresh one. `radii`/`coverPaths` hold plain values nothing captures a reference to,
     *  so those two are safe to actually remove. */
    fun forget(bookId: Long) {
        rects[bookId]?.value = Rect.Zero
        radii.remove(bookId)
        coverPaths.remove(bookId)
    }

    /** Cover path of whichever published (currently on-screen) book's rect contains [point], or
     *  null if none does — the Immersive "Dynamic pills" feature samples whatever's actually
     *  scrolled underneath a pill right now instead of a fixed backdrop. Deferred read: iterates
     *  live rect State objects, so only call this from inside a graphicsLayer/draw lambda (it
     *  correctly re-triggers that draw as books scroll, without recomposing the caller). */
    fun coverPathUnder(point: Offset): String? {
        for ((id, rectState) in rects) {
            if (rectState.value.contains(point)) return coverPaths[id]
        }
        return null
    }

    /** Called by PlayerSheet: [bookId] is the grid-sourced book currently morphing (-1L when the
     *  active source isn't a grid card, or nothing is open). */
    fun setActiveMorph(bookId: Long, progress: State<Float>?) {
        morphBookId.value = bookId
        morphProgress.value = progress
    }

    /** True while [bookId]'s grid card should hide its own cover IMAGE because the morphing cover
     *  is currently traveling on top of it. Binary on purpose: at this point the traveling cover
     *  sits exactly on the card (same rect, same crop, same bitmap), so swapping which of the two
     *  is drawn is invisible — whereas fading would briefly show both. Deferred read (only meant
     *  to be called from inside a graphicsLayer/draw lambda) so checking this doesn't recompose
     *  the card. */
    fun isMorphHidden(bookId: Long): Boolean {
        if (morphBookId.value != bookId) return false
        val p = morphProgress.value?.value ?: return false
        return p > 0.02f
    }

    /** Opacity for [bookId]'s grid card CHROME — its title/author scrim, progress bar, border and
     *  now-playing badge — while a morph is running. Unlike the cover image these have no
     *  counterpart traveling on top of them, so snapping them off (and back on) was a visible
     *  blink at both ends of the transition; they ramp out over the first quarter of the opening
     *  instead, and ramp back in as the page shrinks onto the card. Deferred read, same as
     *  [isMorphHidden]. */
    fun morphChromeAlpha(bookId: Long): Float {
        if (morphBookId.value != bookId) return 1f
        val p = morphProgress.value?.value ?: return 1f
        return 1f - (p / 0.25f).coerceIn(0f, 1f)
    }

    private fun rectState(bookId: Long) =
        rects.getOrPut(bookId) { mutableStateOf(Rect.Zero) }

    // ── Series (separate maps: series ids and book ids are both Long, independent id spaces —
    // sharing the book maps above would risk a series and a book colliding on the same key) ──────

    private val seriesRects = mutableStateMapOf<Long, MutableState<Rect>>()
    private val seriesRadii = mutableStateMapOf<Long, Dp>()
    private val activeSeriesMorphId = mutableStateOf(-1L)
    private val activeSeriesMorphProgress = mutableStateOf<State<Float>?>(null)

    /** Called by a series grid card on every layout pass — same purpose as [publish], for the
     *  Series info screen's cover-morph open/close. */
    fun publishSeries(seriesId: Long, rect: Rect, radius: Dp) {
        seriesRectState(seriesId).value = rect
        seriesRadii[seriesId] = radius
    }

    fun seriesBoundsState(seriesId: Long): State<Rect> = seriesRectState(seriesId)
    fun seriesRadiusFor(seriesId: Long): Dp = seriesRadii[seriesId] ?: 0.dp

    /** Series counterpart of [forget] — same reset-in-place, same reason (SeriesDetailScreen in
     *  both themes captures the MutableState via `remember`). */
    fun forgetSeries(seriesId: Long) {
        seriesRects[seriesId]?.value = Rect.Zero
        seriesRadii.remove(seriesId)
    }

    /** Called by the Series info screen: [seriesId] is the grid-sourced series currently morphing
     *  (-1L when closed / not a grid-card open). */
    fun setActiveSeriesMorph(seriesId: Long, progress: State<Float>?) {
        activeSeriesMorphId.value = seriesId
        activeSeriesMorphProgress.value = progress
    }

    /** True while [seriesId]'s grid card should hide its own cover because the series screen's
     *  morphing cover is currently traveling on top of it. Deferred read (call only from inside a
     *  graphicsLayer/draw lambda). */
    fun isSeriesMorphHidden(seriesId: Long): Boolean {
        if (activeSeriesMorphId.value != seriesId) return false
        val p = activeSeriesMorphProgress.value?.value ?: return false
        return p > 0.02f
    }

    private fun seriesRectState(seriesId: Long) =
        seriesRects.getOrPut(seriesId) { mutableStateOf(Rect.Zero) }

    // ── Info pages (Book Info / the Series page) ─────────────────────────────────────────────
    //
    // A third source, because there are three places a full player can grow out of and they are
    // live at the same time: the mini bar, a library grid card, and the cover of an info page that
    // is itself currently morphing out of that grid card. Keyed by a STRING rather than by id,
    // because these keys have to span both kinds ("book:12" and "series:12" are different covers)
    // and a Long map cannot say which it holds.

    private val infoRects = mutableStateMapOf<String, MutableState<Rect>>()
    private val infoRadii = mutableStateMapOf<String, Dp>()
    private val infoSlots = mutableStateMapOf<String, MutableState<Rect>>()

    /** Called by the active info page frame on every layout pass, with the radius its own cover is
     *  drawn at — 28dp in Material You, 0dp in Immersive, which is the frame's business and not
     *  the player's. */
    fun publishInfoCover(key: String, rect: Rect, radius: Dp) {
        infoRectState(key).value = rect
        infoRadii[key] = radius
    }

    fun infoCoverBounds(key: String): State<Rect> = infoRectState(key)
    fun infoCoverRadius(key: String): Dp = infoRadii[key] ?: 0.dp

    /** Reset in place, for the same reason [forget] is: PlayerSheet captures this MutableState by
     *  `remember` for the length of a transition that outlives the page it came from. */
    fun forgetInfoCover(key: String) {
        infoRects[key]?.value = Rect.Zero
        infoRadii.remove(key)
        for (slot in InfoSlot.entries) infoSlots["$key/$slot"]?.value = Rect.Zero
    }

    private fun infoRectState(key: String) =
        infoRects.getOrPut(key) { mutableStateOf(Rect.Zero) }

    /**
     * The rest of the info page's furniture, for the Resume → player choreography.
     *
     * The cover above is a special case only because it also carries a corner radius; these are
     * plain rectangles. Each is where an element of the *page* is sitting right now, which is where
     * the player's own counterpart starts its travel — the page's title is the player's title's
     * source, its Resume button is the play button's, and so on. See
     * [com.betteraudio.ui.player.ResumeChoreography].
     *
     * Published from [com.betteraudio.ui.components.BookInfoPanel] on every layout pass, and read
     * by [PlayerSheet] when the player was opened from that page.
     */
    fun publishInfoSlot(key: String, slot: InfoSlot, rect: Rect) {
        infoSlotState(key, slot).value = rect
    }

    fun infoSlotBounds(key: String, slot: InfoSlot): State<Rect> = infoSlotState(key, slot)

    private fun infoSlotState(key: String, slot: InfoSlot) =
        infoSlots.getOrPut("$key/$slot") { mutableStateOf(Rect.Zero) }

    companion object {
        fun bookInfoKey(bookId: Long) = "book:$bookId"
        fun seriesInfoKey(seriesId: Long) = "series:$seriesId"
    }
}

/** The pieces of an info page the player grows out of, beyond its cover. */
enum class InfoSlot { TITLE, AUTHOR, TRACK, BUTTON, BUTTON_ICON, BUTTON_LABEL }

val LocalCoverBoundsRegistry = compositionLocalOf { CoverBoundsRegistry() }
