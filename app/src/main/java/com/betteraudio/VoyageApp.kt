package com.betteraudio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import com.betteraudio.BuildConfig
import androidx.work.Configuration
import com.betteraudio.data.ebook.ParagraphCache
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import com.betteraudio.widget.render.WidgetBitmapCache
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class VoyageApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var seriesRepository: SeriesRepository
    @Inject lateinit var repository: AudiobookRepository
    @Inject lateinit var paragraphCache: ParagraphCache
    @Inject lateinit var diskExportMigration: com.betteraudio.data.diskstore.DiskExportMigration
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        AppLog.init(this)
        // StrictMode after AppLog.init() (its crash-handler mkdirs is synchronous, so the log
        // directory already exists — a StrictMode disk-read violation on that mkdirs would
        // otherwise be the very first thing StrictMode ever reports). Debug builds only,
        // penaltyLog only — never penaltyDeath, and the violation logging itself goes through
        // AppLog.e below rather than crashing or dialoging, so a StrictMode hit can never make an
        // existing bug worse. See AppLog.kt's redaction — a violation's stack trace can legitimately
        // contain a file path, which stays on-device same as everything else.
        if (BuildConfig.DEBUG) installStrictMode()
        // Depends on blocker 3 — AppLog.init() has already synchronously set the real level by
        // this point, so this is captured correctly from the very first line even at ON/VERBOSE,
        // not lost to the async DataStore race the level itself used to have.
        com.betteraudio.util.log.PostMortem.logExitReasons(this)
        com.betteraudio.util.log.PostMortem.Watchdog.start()
        super.onCreate()
        appScope.launch {
            // Before anything else in this block: a build that moves the manifest default
            // icon can leave an upgraded install with two launcher entries, and the sooner
            // that is collapsed the less likely the user ever sees it (see
            // AppIconManager.reconcile).
            com.betteraudio.util.AppIconManager.reconcile(this@VoyageApp)
            cleanupPhantomSeries()
            // Strictly after cleanupPhantomSeries: that one identifies a phantom by it holding
            // *every* non-ignored book, which re-attaching orphans could push a real series into.
            repairOrphanedSeriesMembership()
            // After the series work above, not concurrent with it — both read/write the series
            // table and there's no reason to race them.
            diskExportMigration.runIfNeeded()
        }
        appScope.launch {
            settings.logLevel.collect { level ->
                // Backfill on every emission, not just from SettingsStore.setLogLevel — an install
                // that already had a level set before this cache existed has nothing written yet,
                // so without this the cache stays stuck reading the OFF default forever. Writing
                // here makes it self-healing from the very next launch.
                com.betteraudio.util.log.LogPrefsCache.write(this@VoyageApp, level)
                // Collects the real tri-state value (not the legacy boolean) so a VERBOSE session
                // set via Diagnostics is never silently downgraded to ON by this collector.
                AppLog.setLevel(com.betteraudio.util.log.LogLevel.from(level))
            }
        }
        appScope.launch {
            settings.logBudgetMb.collect { mb ->
                // Same self-healing reasoning as the level collector above.
                com.betteraudio.util.log.LogPrefsCache.writeBudgetMb(this@VoyageApp, mb)
                AppLog.setBudgetMb(mb)
            }
        }
    }

    /** `penaltyLog` only — logs violations without crashing or dialoging. Debug builds only (see
     *  [onCreate]'s call site). The codebase has real, deliberate main-thread I/O today (e.g. the
     *  Diagnostics log viewer before Phase 2's async reader landed there), so this will report
     *  findings immediately; that's the point, not a bug in the check. */
    private fun installStrictMode() {
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        android.os.StrictMode.setVmPolicy(
            android.os.StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }

    /** Both are in-memory-only decode/parse caches (see their own docs) — safe to drop entirely
     *  under memory pressure, since the next access just re-decodes/re-parses on demand. */
    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW still fires on API < 34; no replacement level covers it
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            AppLog.i(LogCat.SYSTEM, "onTrimMemory(level=$level) — clearing widget bitmap and paragraph caches")
            WidgetBitmapCache.clear()
            paragraphCache.clear()
        }
    }

    /**
     * One-shot: retire a "phantom" series left by a scanner bug where AUTO import mode could
     * treat the library-root folder itself as a series container (fixed in
     * AudioFileScanner.scanFolder's depth guard — this cleans up rows the old code already wrote
     * to disk). Conservative by design: deletes a series only when its name equals the
     * library-root folder's own name, it holds every non-ignored book in the whole library, and
     * none of its cascade defaults/cover/description was ever customized. Needs the configured
     * library folder from DataStore, which a Room migration cannot read — that's why this runs
     * here instead of as a migration.
     */
    private suspend fun cleanupPhantomSeries() {
        if (settings.phantomSeriesCleanupDone.first()) return
        val libraryFolder = settings.libraryFolder.first()
        if (libraryFolder.isBlank()) return   // not configured yet — retry on a later launch

        val rootName = File(libraryFolder).name
        val nonIgnoredCount = repository.getAllBooksIncludingIgnoredOnce().count { !it.isIgnored }
        if (nonIgnoredCount > 0) {
            seriesRepository.getAllSeriesOnce()
                .filter { it.name.equals(rootName, ignoreCase = true) }
                .filter {
                    it.playbackSpeed == null && it.boostDb == null && it.eqBandsJson == null &&
                        it.skipSilenceEnabled == null && it.coverArtPath == null && it.description == null
                }
                .forEach { series ->
                    val members = seriesRepository.getBooksInSeriesOnce(series.id)
                    if (members.size == nonIgnoredCount && members.none { it.isIgnored }) {
                        AppLog.i(LogCat.DB, "removing phantom series '${series.name}' (${members.size} books)")
                        seriesRepository.deleteSeries(series.id)
                    }
                }
        }
        settings.setPhantomSeriesCleanupDone(true)
    }

    /**
     * One-shot: re-attach books that carry a series name but no [com.betteraudio.data.db.entities.Book.seriesId].
     * Book Options' series field used to write only the denormalized seriesName/seriesOrder cache,
     * so a book set that way displayed its series everywhere the cache is read while Series view
     * never grouped it, no Series page existed for it, and the series cascade defaults could not
     * reach it (fixed in [com.betteraudio.data.repository.SeriesRepository.setBookSeriesByName]).
     * Runs here rather than as a Room migration so the re-attach goes through the repo — which
     * resolves/creates the Series rows and flushes the disk mirror — and so it also reaches
     * installs already sitting on the current schema version.
     */
    private suspend fun repairOrphanedSeriesMembership() {
        if (settings.seriesMembershipRepairDone.first()) return
        val repaired = seriesRepository.repairOrphanedMembership()
        if (repaired > 0) AppLog.i(LogCat.DB, "repaired series membership for $repaired book(s)")
        settings.setSeriesMembershipRepairDone(true)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
