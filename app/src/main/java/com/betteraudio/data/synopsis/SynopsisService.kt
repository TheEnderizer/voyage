package com.betteraudio.data.synopsis

import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class SynopsisResult {
    data class Success(val text: String) : SynopsisResult()
    data class Error(val message: String) : SynopsisResult()
}

@Singleton
class SynopsisService @Inject constructor(
    private val settings: SettingsStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateSynopsis(title: String, author: String): SynopsisResult {
        val prompt = if (author.isNotBlank())
            "Write a 2-3 sentence synopsis of \"$title\" by $author in the same voice and tone as the book itself — maintain its atmosphere and style while staying factual and engaging."
        else
            "Write a 2-3 sentence synopsis of \"$title\" in the same voice and tone as the book itself — maintain its atmosphere and style while staying factual and engaging."
        return runPrompt(prompt)
    }

    /** Synopsis for a whole series — same voice-matching style, but covering the series arc. */
    suspend fun generateSeriesSynopsis(name: String, author: String?): SynopsisResult {
        val byAuthor = author?.takeIf { it.isNotBlank() }?.let { " by $it" } ?: ""
        val prompt =
            "Write a 2-3 sentence synopsis of the book series \"$name\"$byAuthor in the same voice and tone as the series itself — capture its overall arc and atmosphere while staying factual and engaging."
        return runPrompt(prompt)
    }

    private suspend fun runPrompt(prompt: String): SynopsisResult =
        withContext(Dispatchers.IO) {
            val apiKey = settings.currentGeminiApiKey
            if (apiKey.isBlank()) return@withContext SynopsisResult.Error("No API key — add one in Settings → AI Synopsis")

            try {
                val body = JSONObject()
                    .put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(
                            JSONObject().put("text", prompt)
                        ))
                    ))
                    .toString()

                val host = "generativelanguage.googleapis.com"
                val request = Request.Builder()
                    .url("https://$host/v1beta/models/gemini-2.5-flash:generateContent")
                    .header("Content-Type", "application/json")
                    // Google's recommended way to pass the key — keeps it out of the URL, so it
                    // can never end up in an exception message, a proxy log, or browser history.
                    // Never logged below either, for the same reason.
                    .header("x-goog-api-key", apiKey)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val startMs = System.currentTimeMillis()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                val durationMs = System.currentTimeMillis() - startMs
                if (responseBody == null) {
                    AppLog.w(LogCat.NET, "synopsis request to $host: empty body, status=${response.code} ${durationMs}ms")
                    return@withContext SynopsisResult.Error("Empty response from Gemini")
                }

                if (!response.isSuccessful) {
                    AppLog.w(LogCat.NET, "synopsis request to $host: status=${response.code} bytes=${responseBody.length} ${durationMs}ms")
                    return@withContext SynopsisResult.Error(categorizeHttpError(response.code))
                }

                val text = JSONObject(responseBody)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                AppLog.i(LogCat.NET, "synopsis request to $host: status=${response.code} bytes=${responseBody.length} ${durationMs}ms")
                SynopsisResult.Success(text)
            } catch (e: IOException) {
                AppLog.w(LogCat.NET, "synopsis request failed: no network connection (${e.message})")
                SynopsisResult.Error("No network connection")
            } catch (e: Exception) {
                AppLog.e(LogCat.NET, "synopsis request failed unexpectedly", e)
                SynopsisResult.Error("Unexpected error generating synopsis")
            }
        }

    // Deliberately generic — never echoes the raw HTTP body back to the UI, since a Gemini
    // error response can (and sometimes does) include the request's own query/headers.
    private fun categorizeHttpError(code: Int): String = when (code) {
        401, 403 -> "Invalid API key — check Settings → AI Synopsis"
        429 -> "Rate limited — try again in a moment"
        in 500..599 -> "Gemini server error — try again later"
        else -> "Synopsis request failed (HTTP $code)"
    }
}
