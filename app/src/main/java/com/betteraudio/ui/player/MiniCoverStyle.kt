package com.betteraudio.ui.player

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How the Immersive mini player draws the playing book's cover, and therefore where its progress
 * goes. Material You's mini bar is untouched by this — it keeps its inset square thumbnail and its
 * straight bar underneath.
 */
enum class MiniCoverStyle(val label: String, val blurb: String) {
    /** The cover is the pill's own left cap, full height and flush to the edge, clipped into a
     *  "D" by the pill; progress traces the pill's outline. */
    CAP(
        "Cover cap",
        "The cover fills the left end of the pill, and progress traces the pill's outline."
    ),

    /** The cover is a circle inset in the pill with the progress drawn as a ring around it. */
    RING(
        "Circle",
        "The cover is a circle sitting inside the pill, with progress drawn as a ring around it."
    );

    companion object {
        fun from(raw: String): MiniCoverStyle = entries.firstOrNull { it.name == raw } ?: CAP
    }
}

/** Provided by MainActivity, read by the shared [PlayerSheet] (which stays unsplit) and by the
 *  Immersive player, which has to morph out of whichever shape the mini bar actually drew. */
val LocalMiniCoverStyle = staticCompositionLocalOf { MiniCoverStyle.CAP }
