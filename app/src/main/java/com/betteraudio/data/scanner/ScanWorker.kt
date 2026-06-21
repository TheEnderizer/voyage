package com.betteraudio.data.scanner

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scanner: AudioFileScanner
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val path = inputData.getString(KEY_PATH)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No folder path provided."))
        return try {
            val count = scanner.scanDirectory(path)
            Result.success(workDataOf(KEY_BOOK_COUNT to count))
        } catch (e: SecurityException) {
            Result.failure(workDataOf(KEY_ERROR to "Permission denied reading this folder."))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error during scan.")))
        }
    }

    companion object {
        const val KEY_PATH = "scan_path"
        const val KEY_BOOK_COUNT = "book_count"
        const val KEY_ERROR = "error_message"
        const val WORK_NAME = "AudiobookScan"
    }
}
