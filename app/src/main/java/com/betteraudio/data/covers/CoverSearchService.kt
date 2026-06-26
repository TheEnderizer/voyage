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
        val itunes = runCatching { searchItunes(query) }.getOrDefault(emptyList())
        if (itunes.isNotEmpty()) return@withContext itunes
        runCatching { searchOpenLibrary(query) }.getOrDefault(emptyList())
    }

    private fun searchItunes(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://itunes.apple.com/search?term=$q&media=audiobook&limit=20"
        val request = Request.Builder().url(url)
            .header("User-Agent", "BetterAudio/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val regex = Regex(""""artworkUrl100"\s*:\s*"([^"]+)"""")
            return regex.findAll(body).map { match ->
                match.groupValues[1].replace("100x100bb", "600x600bb")
            }.distinct().toList()
        }
    }

    private fun searchOpenLibrary(query: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://openlibrary.org/search.json?q=$q&limit=20"
        val request = Request.Builder().url(url)
            .header("User-Agent", "BetterAudio/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val regex = Regex(""""cover_i"\s*:\s*(\d+)""")
            return regex.findAll(body).map { match ->
                "https://covers.openlibrary.org/b/id/${match.groupValues[1]}-L.jpg"
            }.distinct().toList()
        }
    }

    suspend fun download(imageUrl: String, bookId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(imageUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                if (bytes.size < 100) return@withContext null
                val dir = File(context.filesDir, "covers").apply { mkdirs() }
                val file = File(dir, "${bookId}_${System.currentTimeMillis()}.jpg")
                file.writeBytes(bytes)
                file.absolutePath
            }
        } catch (_: Exception) { null }
    }
}
