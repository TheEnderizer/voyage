package com.betteraudio.data.covers

import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
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
        runCatching { searchDuckDuckGo(query) }
            .onFailure { AppLog.w(LogCat.NET, "cover search for '$query' threw: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun searchDuckDuckGo(query: String): List<String> {
        val startMs = System.currentTimeMillis()
        val q = URLEncoder.encode(query, "UTF-8")
        // Step 1: get vqd token from the main search page
        val tokenReq = Request.Builder()
            .url("https://duckduckgo.com/?q=$q&iax=images&ia=images")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .build()
        val vqd = client.newCall(tokenReq).execute().use { resp ->
            val body = resp.body?.string()
            if (body == null) {
                AppLog.w(LogCat.NET, "cover search '$query': token request status=${resp.code} had no body")
                return emptyList()
            }
            // DuckDuckGo embeds vqd as: vqd="4-..." or vqd=4-...
            val token = Regex("""vqd=["']?([\d\-]+)["']?""").find(body)?.groupValues?.get(1)
            if (token == null) {
                // The single most likely failure mode of the whole feature: DuckDuckGo changed
                // their page format and the regex no longer matches anything on it.
                AppLog.w(LogCat.NET, "cover search '$query': no vqd token found in token page (status=${resp.code} bytes=${body.length}) — DuckDuckGo may have changed its page format")
            }
            token
        } ?: return emptyList()

        // Step 2: fetch image results JSON
        val imgReq = Request.Builder()
            .url("https://duckduckgo.com/i.js?l=us-en&o=json&q=$q&vqd=$vqd&f=,,,,,&p=1")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36")
            .header("Referer", "https://duckduckgo.com/")
            .build()
        client.newCall(imgReq).execute().use { resp ->
            val durationMs = System.currentTimeMillis() - startMs
            if (!resp.isSuccessful) {
                AppLog.w(LogCat.NET, "cover search '$query': image request status=${resp.code} ${durationMs}ms")
                return emptyList()
            }
            val body = resp.body?.string()
            if (body == null) {
                AppLog.w(LogCat.NET, "cover search '$query': image request status=${resp.code} had no body")
                return emptyList()
            }
            val results = Regex(""""image"\s*:\s*"([^"]+)"""")
                .findAll(body)
                .map { it.groupValues[1] }
                .filter { it.startsWith("http") }
                .distinct()
                .take(30)
                .toList()
            AppLog.i(LogCat.NET, "cover search '$query': ${results.size} result(s), status=${resp.code} bytes=${body.length} ${durationMs}ms")
            return results
        }
    }

    /** Downloads the image at [imageUrl] into memory — the caller decides where the bytes land
     *  (BookDataStore/LibraryDataStore), since a book's canonical cover location depends on its
     *  folderKey shape. Returns null on any failure or a suspiciously tiny (likely error-page) body. */
    suspend fun downloadBytes(imageUrl: String): ByteArray? = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URI(imageUrl).host }.getOrNull() ?: imageUrl
        val startMs = System.currentTimeMillis()
        try {
            val request = Request.Builder().url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .build()
            client.newCall(request).execute().use { response ->
                val durationMs = System.currentTimeMillis() - startMs
                if (!response.isSuccessful) {
                    AppLog.w(LogCat.NET, "cover download from $host: status=${response.code} ${durationMs}ms")
                    return@withContext null
                }
                val bytes = response.body?.bytes()
                if (bytes == null) {
                    AppLog.w(LogCat.NET, "cover download from $host: status=${response.code} had no body")
                    return@withContext null
                }
                if (bytes.size < 100) {
                    AppLog.w(LogCat.NET, "cover download from $host: only ${bytes.size} byte(s) — likely an error page, not an image")
                    return@withContext null
                }
                AppLog.i(LogCat.NET, "cover download from $host: ${bytes.size} bytes ${durationMs}ms")
                bytes
            }
        } catch (e: Exception) {
            AppLog.w(LogCat.NET, "cover download from $host failed: ${e.message}")
            null
        }
    }
}
