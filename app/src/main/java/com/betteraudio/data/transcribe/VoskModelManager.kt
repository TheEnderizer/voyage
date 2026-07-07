package com.betteraudio.data.transcribe

import android.content.Context
import com.betteraudio.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A downloadable Vosk language model. v1 ships English-small; the list is kept extensible. */
data class VoskModel(
    val lang: String,
    val displayName: String,
    val url: String,
    val dirName: String
) {
    companion object {
        val EN_SMALL = VoskModel(
            lang = "en",
            displayName = "English (small)",
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            dirName = "vosk-model-small-en-us-0.15"
        )
    }
}

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val pct: Int) : ModelState
    data object Unzipping : ModelState
    data class Ready(val dir: File, val sizeBytes: Long) : ModelState
    data class Error(val message: String) : ModelState
}

/**
 * Downloads, unzips, and tracks the on-device Vosk speech model used by [SyncAligner]. Kept out of
 * the APK (≈45 MB) and fetched once on demand into `filesDir/vosk/`. Mirrors
 * `UpdateChecker.downloadApk`'s OkHttp streaming + progress + integrity pattern.
 */
@Singleton
class VoskModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val voskRoot: File get() = File(context.filesDir, "vosk")
    private fun modelDir(model: VoskModel) = File(voskRoot, model.dirName)

    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    init { refreshState() }

    /** A model is "ready" once its acoustic model file exists (proves the unzip completed). */
    fun modelDirOrNull(model: VoskModel = VoskModel.EN_SMALL): File? =
        modelDir(model).takeIf { File(it, "am/final.mdl").exists() || File(it, "conf/model.conf").exists() }

    fun sizeOnDiskBytes(model: VoskModel = VoskModel.EN_SMALL): Long =
        modelDir(model).takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun refreshState() {
        val dir = modelDirOrNull()
        _state.value = if (dir != null) ModelState.Ready(dir, sizeOnDiskBytes()) else ModelState.NotDownloaded
    }

    suspend fun download(model: VoskModel = VoskModel.EN_SMALL) = withContext(Dispatchers.IO) {
        if (modelDirOrNull(model) != null) { refreshState(); return@withContext }
        try {
            voskRoot.mkdirs()
            val zip = File(voskRoot, "${model.dirName}.zip")
            zip.delete()

            _state.value = ModelState.Downloading(0)
            val response = client.newCall(Request.Builder().url(model.url).build()).execute()
            if (!response.isSuccessful) { _state.value = ModelState.Error("Download failed (HTTP ${response.code})"); return@withContext }
            val body = response.body ?: run { _state.value = ModelState.Error("Empty download"); return@withContext }
            val total = body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                zip.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) _state.value = ModelState.Downloading((downloaded * 100 / total).toInt())
                    }
                    output.flush()
                }
            }
            if (total > 0 && downloaded != total) { zip.delete(); _state.value = ModelState.Error("Download interrupted"); return@withContext }
            // A zip starts with "PK".
            zip.inputStream().use { if (it.read() != 'P'.code || it.read() != 'K'.code) { zip.delete(); _state.value = ModelState.Error("Downloaded file is not a valid model"); return@withContext } }

            _state.value = ModelState.Unzipping
            unzip(zip, voskRoot)
            zip.delete()

            val dir = modelDirOrNull(model)
            if (dir != null) _state.value = ModelState.Ready(dir, sizeOnDiskBytes(model))
            else { deleteDir(modelDir(model)); _state.value = ModelState.Error("Model archive was incomplete") }
        } catch (e: Exception) {
            AppLog.e("Vosk", "model download failed", e)
            _state.value = ModelState.Error(e.message ?: "Download failed")
        }
    }

    fun delete(model: VoskModel = VoskModel.EN_SMALL) {
        deleteDir(modelDir(model))
        refreshState()
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun unzip(zip: File, targetRoot: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetRoot, entry.name)
                // Zip-slip guard: refuse any entry that escapes the target root.
                if (!outFile.canonicalPath.startsWith(targetRoot.canonicalPath + File.separator)) {
                    throw SecurityException("Zip entry outside target: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun deleteDir(dir: File) { runCatching { if (dir.exists()) dir.deleteRecursively() } }
}
