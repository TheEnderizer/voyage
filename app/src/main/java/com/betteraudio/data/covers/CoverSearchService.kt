package com.betteraudio.data.covers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CoverSearchService @Inject constructor() {
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

    /** Downloads the image at [imageUrl] into memory — the caller decides where the bytes land
     *  (BookDataStore/LibraryDataStore), since a book's canonical cover location depends on its
     *  folderKey shape. Returns null on any failure or a suspiciously tiny (likely error-page) body. */
    suspend fun downloadBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size < 100) return@withContext null
                bytes
            }
        } catch (_: Exception) { null }
    }
}
