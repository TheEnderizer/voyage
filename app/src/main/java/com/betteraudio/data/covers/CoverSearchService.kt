package com.betteraudio.data.covers

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
        val google = runCatching { searchGoogleBooks(query) }.getOrDefault(emptyList())
        if (google.isNotEmpty()) google
        else runCatching { searchOpenLibrary(query) }.getOrDefault(emptyList())
    }

    private fun searchGoogleBooks(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.googleapis.com/books/v1/volumes?q=$q&maxResults=20&country=US"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val items = JSONObject(body).optJSONArray("items") ?: return emptyList()
            val urls = mutableListOf<String>()
            for (i in 0 until items.length()) {
                val imageLinks = items.getJSONObject(i)
                    .optJSONObject("volumeInfo")
                    ?.optJSONObject("imageLinks") ?: continue
                // Prefer the largest available link
                val link = imageLinks.optString("thumbnail")
                    .ifBlank { imageLinks.optString("smallThumbnail") }
                if (link.isNotBlank()) {
                    // Force https and drop the page-curl overlay for a cleaner cover
                    urls.add(link.replaceFirst("http://", "https://").replace("&edge=curl", ""))
                }
            }
            return urls.distinct()
        }
    }

    private fun searchOpenLibrary(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://openlibrary.org/search.json?q=$q&limit=20"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val docs = JSONObject(body).optJSONArray("docs") ?: return emptyList()
            val urls = mutableListOf<String>()
            for (i in 0 until docs.length()) {
                val coverId = docs.getJSONObject(i).optInt("cover_i", -1)
                if (coverId > 0) {
                    urls.add("https://covers.openlibrary.org/b/id/$coverId-L.jpg")
                }
            }
            return urls.distinct()
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
