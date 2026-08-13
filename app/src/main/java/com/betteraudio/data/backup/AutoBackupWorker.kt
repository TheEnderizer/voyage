package com.betteraudio.data.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily periodic worker: writes a timestamped backup JSON into the user-picked SAF folder and
 * prunes to the last [KEEP_COUNT]. Fails soft — any error (revoked SAF permission, folder
 * deleted, IO error) is recorded in [SettingsStore.autoBackupLastStatus] rather than crashing or
 * silently retrying forever; the user sees the failure in Settings → Backup.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupManager,
    private val settings: SettingsStore
) : CoroutineWorker(appContext, params) {

    companion object {
        const val WORK_NAME = "VoyageAutoBackup"
        private const val ONE_TIME_WORK_NAME = "VoyageAutoBackupNow"
        private const val KEEP_COUNT = 5
        private val NAME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

        /** (Re)schedules the daily periodic backup. Safe to call repeatedly (e.g. every time the
         *  toggle/folder changes) — UPDATE policy replaces any existing schedule. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        /** Runs one backup immediately (used by the "Back up now" button and for verification —
         *  doesn't touch the daily schedule). */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        val folderUriStr = settings.autoBackupFolderUri.first()
        if (folderUriStr.isBlank()) {
            settings.setAutoBackupLastRun(System.currentTimeMillis(), "No backup folder chosen")
            return Result.failure()
        }
        val folderUri = Uri.parse(folderUriStr)
        val folder = DocumentFile.fromTreeUri(applicationContext, folderUri)
        if (folder == null || !folder.canWrite()) {
            settings.setAutoBackupLastRun(System.currentTimeMillis(), "Backup folder is no longer accessible — reselect it in Settings")
            return Result.failure()
        }

        return try {
            val fileName = "voyage-backup-${NAME_FORMAT.format(Date())}.json"
            val target = folder.createFile("application/json", fileName)
                ?: throw java.io.IOException("Could not create backup file")
            applicationContext.contentResolver.openOutputStream(target.uri)?.use { out ->
                backupManager.export(out, includeApiKey = false)
            } ?: throw java.io.IOException("Could not open backup file for writing")

            prune(folder)
            settings.setAutoBackupLastRun(System.currentTimeMillis(), "ok")
            AppLog.i(LogCat.BACKUP, "auto-backup wrote $fileName")
            Result.success()
        } catch (e: Exception) {
            AppLog.e(LogCat.BACKUP, "auto-backup failed", e)
            settings.setAutoBackupLastRun(System.currentTimeMillis(), e.message ?: "Unknown error")
            Result.failure()
        }
    }

    private fun prune(folder: DocumentFile) {
        val backups = folder.listFiles()
            .filter { it.name?.startsWith("voyage-backup-") == true && it.name?.endsWith(".json") == true }
            .sortedByDescending { it.name }
        backups.drop(KEEP_COUNT).forEach { it.delete() }
    }
}
