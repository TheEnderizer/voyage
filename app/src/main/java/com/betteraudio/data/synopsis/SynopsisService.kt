package com.betteraudio.data.synopsis

import com.betteraudio.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

    suspend fun generateSynopsis(title: String, author: String): SynopsisResult =
        withContext(Dispatchers.IO) {
            val apiKey = settings.currentGeminiApiKey
            if (apiKey.isBlank()) return@withContext SynopsisResult.Error("No API key — add one in Settings → AI Synopsis")

            try {
                val prompt = if (author.isNotBlank())
                    "In 2-3 sentences, write an engaging synopsis for the audiobook \"$title\" by $author. Be factual and concise."
                else
                    "In 2-3 sentences, write an engaging synopsis for the audiobook \"$title\". Be factual and concise."

                val body = JSONObject()
                    .put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(
                            JSONObject().put("text", prompt)
                        ))
                    ))
                    .toString()

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key=$apiKey")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext SynopsisResult.Error("Empty response from Gemini")

                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(responseBody).getJSONObject("error").getString("message")
                    } catch (_: Exception) { "HTTP ${response.code}" }
                    return@withContext SynopsisResult.Error(errMsg)
                }

                val text = JSONObject(responseBody)
                    .getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .trim()

                SynopsisResult.Success(text)
            } catch (e: Exception) {
                SynopsisResult.Error(e.message ?: "Unknown error")
            }
        }
}
