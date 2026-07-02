package com.betteraudio.playback

/**
 * Resolves a member book's effective audio settings against its series and the global defaults:
 * a per-book value that has been explicitly changed (differs from neutral) wins; otherwise the
 * series default applies; otherwise the global default. This lets a series set defaults for all
 * its books while any individually-tuned book keeps its own.
 *
 * "Neutral" per-book values (speed 1.0×, boost 0 dB, null EQ, skip-silence off) are treated as
 * "not customised" so they inherit the series — the common case. A book explicitly set to a
 * neutral value to override a non-neutral series default is the rare exception this trades away
 * to avoid a schema/override-flag migration.
 */
object AudioCascade {
    fun speed(bookSpeed: Float?, seriesSpeed: Float?, globalDefault: Float): Float =
        bookSpeed?.takeIf { it != 1.0f } ?: seriesSpeed ?: globalDefault

    fun boost(bookBoost: Int?, seriesBoost: Int?): Int =
        bookBoost?.takeIf { it != 0 } ?: seriesBoost ?: 0

    fun eq(bookEq: String?, seriesEq: String?): String? = bookEq ?: seriesEq

    fun skipSilence(bookSkip: Boolean, seriesSkip: Boolean?): Boolean = bookSkip || (seriesSkip == true)

    /** Effective author/narrator: the book's own value, or the series' when the book's is blank. */
    fun text(bookValue: String?, seriesValue: String?): String? =
        bookValue?.takeIf { it.isNotBlank() } ?: seriesValue?.takeIf { it.isNotBlank() }
}
