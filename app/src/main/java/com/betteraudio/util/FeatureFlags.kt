package com.betteraudio.util

/**
 * Compile-time switches for features that are built but deliberately not surfaced yet.
 *
 * These gate **UI entry points only** — the underlying data/scanner/reader code stays live and
 * untouched, so flipping a flag back to `true` restores the feature without a migration or a
 * re-scan. Anything already stored (a connected `Book.ebookPath`, a reading position, the ebook
 * folder setting) simply sits unused while the flag is off.
 */
object FeatureFlags {
    /**
     * Ebook (EPUB) UI: the Ebooks section in the nav pill, Settings → Library → Ebook folder,
     * Book options → Ebook (connect/open/disconnect), the player's "Read from here" overflow item,
     * and the Settings → AI listen↔read sync model card.
     *
     * Off for now — the reader and the listen↔read sync aren't ready to ship. The reader screen and
     * its nav route still exist; with this off nothing in the app navigates to them.
     */
    const val EBOOKS_UI = false
}
