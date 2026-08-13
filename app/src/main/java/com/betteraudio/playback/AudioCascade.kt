package com.betteraudio.playback

import com.betteraudio.data.db.entities.AudioPreset
import com.betteraudio.data.db.entities.Book
import com.betteraudio.data.db.entities.PlaybackProgress
import com.betteraudio.data.db.entities.Series
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat

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

    fun boost(bookBoost: Int?, seriesBoost: Int?, globalDefault: Int = 0): Int =
        bookBoost?.takeIf { it != 0 } ?: seriesBoost ?: globalDefault

    fun eq(bookEq: String?, seriesEq: String?, globalDefault: String? = null): String? =
        bookEq ?: seriesEq ?: globalDefault

    fun skipSilence(bookSkip: Boolean, seriesSkip: Boolean?): Boolean = bookSkip || (seriesSkip == true)

    /** Effective author/narrator: the book's own value, or the series' when the book's is blank. */
    fun text(bookValue: String?, seriesValue: String?): String? =
        bookValue?.takeIf { it.isNotBlank() } ?: seriesValue?.takeIf { it.isNotBlank() }

    data class ResolvedAudio(
        val speed: Float,
        val boostDb: Int,
        val eqBandsJson: String?,
        val skipSilence: Boolean
    )

    /** All four cascaded values at once — the shape every play/resume entry point needs (player,
     *  home resume card, series continuation, cold widget-tap resume). Kept as one call so those
     *  call sites can't drift by applying the cascade to some fields and not others.
     *
     *  Logs which layer won for each field at DEBUG — this is the one place that can answer "why
     *  is this book playing at 1.2x" without reading DB rows by hand; the per-field helpers above
     *  stay pure/directly-unit-testable (see AudioCascadeTest) and don't log anything themselves. */
    fun resolve(book: Book, progress: PlaybackProgress?, series: Series?, globalPreset: AudioPreset?, fallbackSpeed: Float): ResolvedAudio {
        val resolved = ResolvedAudio(
            speed = speed(progress?.playbackSpeed, series?.playbackSpeed, globalPreset?.speedMult ?: fallbackSpeed),
            boostDb = boost(progress?.boostDb, series?.boostDb, globalPreset?.boostDb ?: 0),
            eqBandsJson = eq(progress?.eqBandsJson, series?.eqBandsJson, globalPreset?.eqBandsJson),
            skipSilence = skipSilence(book.skipSilenceEnabled, series?.skipSilenceEnabled)
        )
        AppLog.d(LogCat.PLAYBACK) {
            val speedFrom = when {
                progress?.playbackSpeed?.takeIf { it != 1.0f } != null -> "book"
                series?.playbackSpeed != null -> "series"
                globalPreset?.speedMult != null -> "preset"
                else -> "fallback"
            }
            val boostFrom = when {
                progress?.boostDb?.takeIf { it != 0 } != null -> "book"
                series?.boostDb != null -> "series"
                globalPreset?.boostDb != null -> "preset"
                else -> "default"
            }
            val eqFrom = when {
                progress?.eqBandsJson != null -> "book"
                series?.eqBandsJson != null -> "series"
                globalPreset?.eqBandsJson != null -> "preset"
                else -> "none"
            }
            val skipSilenceFrom = if (book.skipSilenceEnabled) "book" else if (series?.skipSilenceEnabled == true) "series" else "off"
            "cascade book=${book.id} series=${series?.id} speed=${resolved.speed}(${speedFrom}) boost=${resolved.boostDb}db(${boostFrom}) " +
                "eq=${if (resolved.eqBandsJson != null) "set" else "null"}(${eqFrom}) skipSilence=${resolved.skipSilence}(${skipSilenceFrom})"
        }
        return resolved
    }

    /** Pure core of the auto-rewind decision, as primitives (unlike the rest of [SettingsStore],
     *  directly unit-testable — no Android Context/DataStore needed): 0 if disabled/no prior
     *  pause; the full configured amount if the app was fully stopped since that pause; otherwise
     *  only past a configured threshold. [nowMs] defaults to wall-clock but is overridable for
     *  tests. */
    fun autoRewindMs(
        rewindSeconds: Int,
        thresholdMinutes: Int,
        appStoppedAt: Long,
        lastPausedAt: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        if (lastPausedAt <= 0L) return 0L
        val rewindMs = rewindSeconds * 1_000L
        if (rewindMs <= 0L) return 0L
        if (appStoppedAt > lastPausedAt) return rewindMs
        val thresholdMs = thresholdMinutes * 60_000L
        if (thresholdMs <= 0L) return 0L
        val elapsed = nowMs - lastPausedAt
        return if (elapsed >= thresholdMs) rewindMs else 0L
    }

    /** How far to auto-rewind on resume. Shared so a book rewinds the same way whether resumed
     *  from the full player, the home-screen resume card, or a series continuation. */
    fun autoRewindMs(settings: SettingsStore, lastPausedAt: Long): Long =
        autoRewindMs(
            rewindSeconds = settings.currentAutoRewindSeconds,
            thresholdMinutes = settings.currentAutoRewindThresholdMinutes,
            appStoppedAt = settings.currentAppStoppedAt,
            lastPausedAt = lastPausedAt,
        )
}
