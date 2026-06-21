package com.betteraudio.data.update

import android.content.Context
import com.betteraudio.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ReleaseInfo(
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String
)

@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        const val GITHUB_REPO = "TheEnderizer/voyage"
        private const val RELEASES_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    suspend fun checkForUpdate(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val json = fetchLatestRelease() ?: return@withContext null
            val tagName = json.optString("tag_name", "")
            val remoteVersion = tagName.trimStart('v')
            if (!isNewerVersion(remoteVersion, BuildConfig.VERSION_NAME)) return@withContext null
            val notes = json.optString("body", "")
            val apkUrl = findApkAssetUrl(json) ?: return@withContext null
            ReleaseInfo(remoteVersion, notes, apkUrl)
        } catch (_: Exception) { null }
    }

    suspend fun fetchLatestReleaseNotes(): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val json = fetchLatestRelease() ?: return@withContext null
            val tag = json.optString("tag_name", "").trimStart('v')
            val notes = json.optString("body", "No release notes available.")
            Pair(tag, notes)
        } catch (_: Exception) { null }
    }

    suspend fun downloadApk(url: String, onProgress: suspend (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                val total = body.contentLength()
                val file = File(context.externalCacheDir, "voyage-update.apk")
                var downloaded = 0L
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(8192)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) onProgress((downloaded * 100 / total).toInt())
                        }
                    }
                }
                file
            } catch (_: Exception) { null }
        }

    private fun fetchLatestRelease(): JSONObject? {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null
        return JSONObject(response.body?.string() ?: return null)
    }

    private fun findApkAssetUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            val mime = asset.optString("content_type", "")
            if (name.endsWith(".apk") || mime == "application/vnd.android.package-archive") {
                return asset.optString("browser_download_url").takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }
}
