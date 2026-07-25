package com.betteraudio

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.betteraudio.data.repository.AudiobookRepository
import com.betteraudio.data.repository.SeriesRepository
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.di.ApplicationScope
import com.betteraudio.util.AppLog
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
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        AppLog.init(this)
        super.onCreate()
        appScope.launch { cleanupPhantomSeries() }
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
                        AppLog.i("DB", "removing phantom series '${series.name}' (${members.size} books)")
                        seriesRepository.deleteSeries(series.id)
                    }
                }
        }
        settings.setPhantomSeriesCleanupDone(true)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
