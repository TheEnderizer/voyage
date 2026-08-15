package com.betteraudio.ui.material.player

/**
 * Which landscape layout the Material You player draws. Portrait is unaffected by this — there is
 * one portrait player and it stays that way.
 *
 * This is a LAYOUT choice inside one theme, not a third `AppTheme`: both styles read the same
 * colours, the same `PlayerViewModel`, and the same sheets/overlays, and both are reached from the
 * single `PlayerContent` call site in `PlayerScreen.kt`. Adding a style therefore means a new
 * entry here plus a new body composable — never a new per-theme `ui/` tree.
 *
 * Persisted by NAME in `SettingsStore.playerLandscapeStyle`, so entry order is free to change but
 * the names are not.
 */
enum class LandscapePlayerStyle(val label: String, val blurb: String) {
    /** Everything on its side: vertical chapter + book seek rails, a vertical transport column,
     *  a vertical secondary rail, and the collapse/overflow buttons stacked down the left edge. */
    RAILS(
        label = "Rails",
        blurb = "Vertical seek rails with the controls stacked down the right edge. Compact — " +
            "nothing takes a full row, so the cover stays large."
    ),

    /** A conventional two-pane split: cover and secondary actions on the left, identity, a wide
     *  scrubber and a big transport row on the right. */
    STAGE(
        label = "Stage",
        blurb = "Cover on the left, a wide scrubber and full-size transport controls on the " +
            "right. Larger touch targets and familiar left-to-right progress."
    );

    companion object {
        /** Parses a persisted name, falling back to [RAILS] for a blank or unknown value. */
        fun fromName(name: String?): LandscapePlayerStyle =
            entries.firstOrNull { it.name == name } ?: RAILS
    }
}
