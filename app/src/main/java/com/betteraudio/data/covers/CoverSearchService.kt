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

/**
 * Searches the web for book cover art. Tries the Google Books API first (free, no key),
 * falling back to OpenLibrary. Returns a list of image URLs the user can pick from.
 */
@Singleton
class CoverSearchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { searchGoogleImages("$query book cover") }.getOrDefault(emptyList())
    }

    private fun searchGoogleImages(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.google.com/search?q=$q&tbm=isch&num=20&hl=en"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            // Google embeds full-size image URLs in the page JSON as "ou":"https://..."
            val ouRegex = Regex(""""ou":"(https://[^"]+)"""")
            val ouUrls = ouRegex.findAll(body).map { it.groupValues[1] }.distinct().take(24).toList()
            if (ouUrls.isNotEmpty()) return ouUrls
            // Fallback: scrape any direct image URLs from the page
            val imgRegex = Regex("""https://[^\s"'\\]+\.(?:jpg|jpeg|png)(?:\?[^\s"'\\]*)?""")
            return imgRegex.findAll(body)
                .map { it.value }
                .filter { "gstatic.com/images" !in it }
                .distinct()
                .take(20)
                .toList()
        }
    }

    /**
     * Downloads [imageUrl] to internal storage and returns the saved file path, or null on failure.
     * Uses a timestamped filename so Coil always reloads a freshly-picked cover (it caches by path).
     */
    suspend fun download(imageUrl: String, bookId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size < 100) return@withContext null // reject empty / error responses
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val file = File(dir, "${bookId}_${System.currentTimeMillis()}.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            }
        } catch (_: Exception) { null }
    }
}
