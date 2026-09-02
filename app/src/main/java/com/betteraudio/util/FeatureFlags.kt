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
     * Book options → Ebook (connect/open/disconnect), and the player's "Read from here" overflow
     * item.
     *
     * On: the reading experience is being brought back (see docs/reader-revival.md).
     */
    const val EBOOKS_UI = true

    /**
     * The listen ↔ read sync surface: Settings → AI's on-device speech-model card, and the
     * reader's own "Improve sync" / "Align chapters" / "Import mapping" affordances.
     *
     * Deliberately a *separate* switch from [EBOOKS_UI] — the reader ships without sync. The
     * alignment code (`data/transcribe/`, `sync/PositionBridge`, `SyncAligner`) stays live and
     * untouched behind it.
     */
    const val EBOOK_SYNC_UI = false
}
