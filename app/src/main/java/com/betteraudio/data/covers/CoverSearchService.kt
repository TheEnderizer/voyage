package com.betteraudio.data.covers

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoverSearchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { searchDuckDuckGo(query) }.getOrDefault(emptyList())
    }

    private fun searchDuckDuckGo(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        // Step 1: get vqd token from the main search page
        val tokenReq = Request.Builder()
            .url("https://duckduckgo.com/?q=$q&iax=images&ia=images")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        val vqd = client.newCall(tokenReq).execute().use { resp ->
            val body = resp.body?.string() ?: return emptyList()
            // DuckDuckGo embeds vqd as: vqd="4-..." or vqd=4-...
            Regex("""vqd=["']?([\d\-]+)["']?""").find(body)?.groupValues?.get(1)
        } ?: return emptyList()

        // Step 2: fetch image results JSON
        val imgReq = Request.Builder()
            .url("https://duckduckgo.com/i.js?l=us-en&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36")
            .header("Referer", "https://duckduckgo.com/")
            .build()
        client.newCall(imgReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            // Extract "image":"url" entries
            return Regex(""""image"\s*:\s*"([^"]+)"""")
                .findAll(body)
                .map { it.groupValues[1] }
                .filter { it.startsWith("http") }
                .distinct()
                .take(30)
                .toList()
        }
    }

    suspend fun download(imageUrl: String, bookId: Long): String? = download(imageUrl, "book$bookId")

    /** Download a cover directly to [target] on disk (e.g. inside the book's own folder). */
    suspend fun downloadTo(imageUrl: String, target: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val bytes = response.body?.bytes() ?: return@withContext false
                if (bytes.size < 100) return@withContext false
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                true
            }
        } catch (_: Exception) { false }
    }

    /** Download a cover to internal storage under an arbitrary [key] (e.g. "series12", "authorFooBar"). */
    suspend fun download(imageUrl: String, key: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size < 100) return@withContext null
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val safeKey = key.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "cover" }
                val file = File(dir, "${safeKey}_${System.currentTimeMillis()}.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            }
        } catch (_: Exception) { null }
    }
}
