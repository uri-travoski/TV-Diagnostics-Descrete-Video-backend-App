package com.tvdiagnostics.app.data

import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tvdiagnostics.app.TVDiagnosticsApp
import com.tvdiagnostics.app.security.LockReason
import com.tvdiagnostics.app.security.Session
import com.tvdiagnostics.app.security.VaultLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val SESSION_HEADER = "X-Session-Token"
    private const val ACTIVITY_HEADER = "X-Activity-Session"
    private const val DEVICE_HEADER = "X-Device-Name"

    /** Shown in the admin activity log, e.g. "Amlogic 905X3A". */
    private val deviceName: String by lazy {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(80)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private fun getBaseUrl(): String {
        return TVDiagnosticsApp.instance.preferences.serverUrl
    }

    private fun tokenParam(): String {
        val token = VaultLock.session?.token ?: return ""
        return "?t=" + URLEncoder.encode(token, "UTF-8")
    }

    fun getStreamUrl(videoId: Int): String {
        return "${getBaseUrl()}/api/v1/videos/$videoId/stream${tokenParam()}"
    }

    fun getThumbnailUrl(videoId: Int): String {
        return "${getBaseUrl()}/api/v1/videos/$videoId/thumbnail${tokenParam()}"
    }

    /** Storyboard frame [index]: the picture at (index + 1) * 7 seconds. */
    fun getPreviewFrameUrl(videoId: Int, index: Int): String {
        return "${getBaseUrl()}/api/v1/videos/$videoId/preview/$index${tokenParam()}"
    }

    private val fastClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** Request builder carrying the signed-in user's session. */
    private fun authed(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header(DEVICE_HEADER, deviceName)
        VaultLock.session?.let {
            builder.header(SESSION_HEADER, it.token)
            if (it.activityId.isNotEmpty()) builder.header(ACTIVITY_HEADER, it.activityId)
        }
        return builder
    }

    /** A 401 means the code was changed or reset on the backend: lock and ask for the code again. */
    private fun checkSession(response: Response) {
        if (response.code == 401 && VaultLock.isUnlocked) {
            Handler(Looper.getMainLooper()).post {
                VaultLock.lock(LockReason.EXPIRED)
                VaultLock.promptOnReturn = true
            }
        }
    }

    sealed class PinResult {
        data class Success(val session: Session) : PinResult()
        object Invalid : PinResult()
        object Offline : PinResult()
    }

    /** Checks a 6-digit vault code; the backend says which user it belongs to. */
    suspend fun verifyPin(pin: String): PinResult = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(PinVerifyRequest(pin, deviceName))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/v1/auth/verify")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            fastClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext PinResult.Offline
                val bodyStr = response.body?.string() ?: return@withContext PinResult.Offline
                val parsed = gson.fromJson(bodyStr, PinVerifyResponse::class.java)
                if (parsed.valid && parsed.token != null && parsed.userId != null) {
                    PinResult.Success(Session(parsed.userId, parsed.userName ?: "", parsed.token, parsed.sessionId ?: ""))
                } else {
                    PinResult.Invalid
                }
            }
        } catch (e: Exception) {
            PinResult.Offline
        }
    }

    /** Changes the signed-in user's code. Returns null on success, else a message to show. */
    suspend fun changePin(newPin: String): String? = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(ChangePinRequest(newPin))
            val req = authed("${getBaseUrl()}/api/v1/auth/change-pin")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(req).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val parsed = gson.fromJson(bodyStr, ChangePinResponse::class.java)
                    parsed.token?.let { VaultLock.updateToken(it) }
                    return@withContext null
                }
                checkSession(response)
                val detail = try {
                    gson.fromJson(bodyStr, ErrorResponse::class.java)?.detail
                } catch (e: Exception) { null }
                detail ?: "Could not change code (error ${response.code})"
            }
        } catch (e: Exception) {
            "Server unreachable"
        }
    }

    private val activityExecutor = Executors.newSingleThreadExecutor()

    /**
     * Fire-and-forget activity log entry (play, pause, exit, lock...). Takes the session explicitly
     * because events like "lock" and "exit" are sent while the session is being cleared.
     */
    fun logActivity(
        session: Session,
        event: String,
        videoId: Int? = null,
        positionSec: Double? = null,
        watchedSec: Double? = null,
        detail: String = ""
    ) {
        if (session.activityId.isEmpty()) return
        val payload = gson.toJson(ActivityEvent(session.activityId, event, videoId, positionSec, watchedSec, detail))
        val baseUrl = getBaseUrl()
        // One at a time, so the log keeps the order things happened in (exit before lock...)
        activityExecutor.execute {
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/v1/activity")
                    .header(SESSION_HEADER, session.token)
                    .header(DEVICE_HEADER, deviceName)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                client.newCall(req).execute().close()
            } catch (e: Exception) {
                // The log is best effort: playback never waits on it
            }
        }
    }

    suspend fun verifyNotesPin(pin: String): Boolean = withContext(Dispatchers.IO) {
        val isKnownNotes = (pin == "1234")

        try {
            val json = gson.toJson(PinVerifyRequest(pin))
            val req = Request.Builder()
                .url("${getBaseUrl()}/api/v1/auth/verify-notes-pin")
                .post(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = fastClient.newCall(req).execute()
            if (!response.isSuccessful) return@withContext isKnownNotes
            val bodyStr = response.body?.string() ?: return@withContext isKnownNotes
            val parsed = gson.fromJson(bodyStr, PinVerifyResponse::class.java)
            if (parsed.valid) return@withContext true
            return@withContext false
        } catch (e: Exception) {
            // Offline fallback
            return@withContext isKnownNotes
        }
    }

    suspend fun fetchTags(prefix: String? = null): List<TagItem> = withContext(Dispatchers.IO) {
        try {
            val url = if (!prefix.isNullOrBlank()) {
                "${getBaseUrl()}/api/v1/tags?prefix=" + URLEncoder.encode(prefix.trim(), "UTF-8")
            } else {
                "${getBaseUrl()}/api/v1/tags"
            }
            val req = authed(url).get().build()
            val response = client.newCall(req).execute()
            checkSession(response)
            if (!response.isSuccessful) return@withContext emptyList()

            val bodyStr = response.body?.string() ?: return@withContext emptyList()
            val listType = object : TypeToken<List<TagItem>>() {}.type
            return@withContext gson.fromJson(bodyStr, listType)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /** The signed-in user's videos, or null if the server couldn't be reached. */
    suspend fun fetchVideos(rating: Int? = null, tag: String? = null): List<VideoItem>? = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf<String>()
            if (rating != null) params.add("rating=$rating")
            if (!tag.isNullOrBlank()) params.add("tag=" + URLEncoder.encode(tag, "UTF-8"))
            val queryStr = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
            val url = "${getBaseUrl()}/api/v1/videos$queryStr"
            val req = authed(url).get().build()
            val response = client.newCall(req).execute()
            checkSession(response)
            if (!response.isSuccessful) return@withContext null

            val bodyStr = response.body?.string() ?: return@withContext null
            val listType = object : TypeToken<List<VideoItem>>() {}.type
            return@withContext gson.fromJson(bodyStr, listType)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun updateProgress(videoId: Int, watchedSeconds: Double, completed: Boolean? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val payload = gson.toJson(ProgressUpdateRequest(watchedSeconds, completed))
                val req = authed("${getBaseUrl()}/api/v1/videos/$videoId/progress")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                val res = client.newCall(req).execute()
                res.close()
                return@withContext res.isSuccessful
            } catch (e: Exception) {
                return@withContext false
            }
        }

    /**
     * Progress sync for when the player is closing: the vault may already be locked by then,
     * so the token is captured by the caller while the session was still valid.
     */
    suspend fun updateProgressWithToken(token: String?, videoId: Int, watchedSeconds: Double, completed: Boolean?): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val payload = gson.toJson(ProgressUpdateRequest(watchedSeconds, completed))
                val builder = Request.Builder().url("${getBaseUrl()}/api/v1/videos/$videoId/progress")
                if (token != null) builder.header(SESSION_HEADER, token)
                val res = client.newCall(builder.post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()).execute()
                res.close()
                return@withContext res.isSuccessful
            } catch (e: Exception) {
                return@withContext false
            }
        }

    suspend fun resetProgress(videoId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = authed("${getBaseUrl()}/api/v1/videos/$videoId/reset-progress")
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val res = client.newCall(req).execute()
            checkSession(res)
            res.close()
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
