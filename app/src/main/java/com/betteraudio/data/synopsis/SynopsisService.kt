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

@Singleton
class SynopsisService @Inject constructor(
    private val settings: SettingsStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generateSynopsis(title: String, author: String): String? = withContext(Dispatchers.IO) {
        val apiKey = settings.currentAnthropicApiKey
        if (apiKey.isBlank()) return@withContext null

        try {
            val prompt = if (author.isNotBlank())
                "In 2-3 sentences, write an engaging synopsis for the audiobook \"$title\" by $author. Be factual and concise."
            else
                "In 2-3 sentences, write an engaging synopsis for the audiobook \"$title\". Be factual and concise."

            val body = JSONObject()
                .put("model", "claude-haiku-4-5-20251001")
                .put("max_tokens", 200)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)
                ))
                .toString()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val json = JSONObject(response.body?.string() ?: return@withContext null)
            json.getJSONArray("content").getJSONObject(0).getString("text").trim()
        } catch (_: Exception) { null }
    }
}
