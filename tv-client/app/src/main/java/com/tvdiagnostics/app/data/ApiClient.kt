package com.tvdiagnostics.app.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvdiagnostics.app.TVDiagnosticsApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun getBaseUrl(): String {
        return TVDiagnosticsApp.instance.preferences.serverUrl
    }

    fun getStreamUrl(videoId: Int): String {
        return "${getBaseUrl()}/api/v1/videos/$videoId/stream"
    }

    fun getThumbnailUrl(videoId: Int): String {
        return "${getBaseUrl()}/api/v1/videos/$videoId/thumbnail"
    }

    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(PinVerifyRequest(pin))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/v1/auth/verify")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) {
                // Fallback to local pin match if server unreachable
                return@withContext pin == TVDiagnosticsApp.instance.preferences.pinCode
            }
            val bodyStr = response.body?.string() ?: return@withContext false
            val parsed = gson.fromJson(bodyStr, PinVerifyResponse::class.java)
            return@withContext parsed.valid
        } catch (e: Exception) {
            // Local PIN fallback in case offline
            return@withContext pin == TVDiagnosticsApp.instance.preferences.pinCode
        }
    }

    suspend fun verifyNotesPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(PinVerifyRequest(pin))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/v1/auth/verify-notes-pin")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) return@withContext false
            val bodyStr = response.body?.string() ?: return@withContext false
            val parsed = gson.fromJson(bodyStr, PinVerifyResponse::class.java)
            return@withContext parsed.valid
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun fetchTags(prefix: String? = null): List<TagItem> = withContext(Dispatchers.IO) {
        try {
            val url = if (!prefix.isNullOrBlank()) {
                "${getBaseUrl()}/api/v1/tags?prefix=" + URLEncoder.encode(prefix.trim(), "UTF-8")
            } else {
                "${getBaseUrl()}/api/v1/tags"
            }
            val req = Request.Builder().url(url).get().build()
            val response = client.newCall(req).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            val listType = object : TypeToken<List<TagItem>>() {}.type
            return@withContext gson.fromJson(bodyStr, listType)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    suspend fun fetchVideos(rating: Int? = null, tag: String? = null): List<VideoItem> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            if (rating != null) params.add("rating=$rating")
            if (!tag.isNullOrBlank()) params.add("tag=" + URLEncoder.encode(tag, "UTF-8"))
            val queryStr = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
            val url = "${getBaseUrl()}/api/v1/videos$queryStr"
            val req = Request.Builder().url(url).get().build()
            val response = client.newCall(req).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            val listType = object : TypeToken<List<VideoItem>>() {}.type
            return@withContext gson.fromJson(bodyStr, listType)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    suspend fun updateProgress(videoId: Int, watchedSeconds: Double, completed: Boolean? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val payload = gson.toJson(ProgressUpdateRequest(watchedSeconds, completed))
                val req = Request.Builder()
                    .url("${getBaseUrl()}/api/v1/videos/$videoId/progress")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val res = client.newCall(req).execute()
                return@withContext res.isSuccessful
            } catch (e: Exception) {
                return@withContext false
            }
        }

    suspend fun resetProgress(videoId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/v1/videos/$videoId/reset-progress")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val res = client.newCall(req).execute()
            return@withContext res.isSuccessful
        } catch (e: Exception) {
            return@withContext false
        }
    }

    suspend fun checkServerStatus(): ServerStatus? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/v1/status")
                .get()
                .build()
            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return@withContext null
            val body = res.body?.string() ?: return@withContext null
            return@withContext gson.fromJson(body, ServerStatus::class.java)
        } catch (e: Exception) {
            return@withContext null
        }
    }
}
