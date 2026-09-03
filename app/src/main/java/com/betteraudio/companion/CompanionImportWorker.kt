package com.betteraudio.companion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File

/**
 * WorkManager job for a FULL companion-pack import (docs/companion-packs.md §10.3 step 3, P7) —
 * mirrors [com.betteraudio.data.backup.AutoBackupWorker]'s shape, the established precedent for
 * exactly this kind of background job in this codebase (Hilt-managed `WorkManager` init already
 * wired in the manifest). Not a foreground service — see [CompanionFullImportService]'s kdoc for
 * why. Deletes the local pending-import file (see [CompanionImportService.importFromUri]) when done,
 * success or failure, so a repeatedly-failing import doesn't leak files into `filesDir` forever.
 */
@HiltWorker
class CompanionImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val placementService: CompanionFullImportService
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val file = File(path)
        return try {
            when (val result = placementService.importFull(file)) {
                is CompanionFullImportService.PlacementResult.Success -> {
                    AppLog.i(LogCat.SCAN, "companion FULL import: placed book=${result.bookId} pack=${result.packTitle}")
                    Result.success()
                }
                else -> {
                    AppLog.w(LogCat.SCAN, "companion FULL import failed: $result")
                    Result.failure()
                }
            }
        } catch (e: Exception) {
            AppLog.e(LogCat.SCAN, "companion FULL import crashed", e)
            Result.failure()
        } finally {
            runCatching { file.delete() }
        }
    }

    companion object {
        private const val WORK_NAME_PREFIX = "VoyageCompanionFullImport"
        private const val KEY_FILE_PATH = "filePath"

        fun enqueue(context: Context, localFilePath: String) {
            val data: Data = workDataOf(KEY_FILE_PATH to localFilePath)
            val request = OneTimeWorkRequestBuilder<CompanionImportWorker>().setInputData(data).build()
            // Unique per file (not one shared name) — two FULL packs queued back to back must both
            // run, not have the second REPLACE-cancel the first mid-extract.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("$WORK_NAME_PREFIX:${File(localFilePath).name}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
