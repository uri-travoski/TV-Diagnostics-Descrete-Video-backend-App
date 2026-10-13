package com.tvdiagnostics.app.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update from the project's GitHub Releases: each release is tagged "v<versionName>"
 * and carries the signed APK as an asset.
 */
object AppUpdater {

    const val RELEASES_API =
        "https://api.github.com/repos/uri-travoski/TV-Diagnostics-Descrete-Video-backend-App/releases/latest"

    data class Release(val version: String, val apkUrl: String, val apkSize: Long)

    private data class GhAsset(
        val name: String = "",
        val size: Long = 0,
        @SerializedName("browser_download_url") val downloadUrl: String = ""
    )

    private data class GhRelease(
        @SerializedName("tag_name") val tagName: String = "",
        val draft: Boolean = false,
        val assets: List<GhAsset> = emptyList()
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** The latest published release with an APK, or null if GitHub can't be reached. */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TV-Diagnostics")
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@withContext null
                val gh = Gson().fromJson(res.body?.string() ?: return@withContext null, GhRelease::class.java)
                val apk = gh.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                if (gh.draft || apk == null || gh.tagName.isBlank()) return@withContext null
                Release(gh.tagName.trim().removePrefix("v"), apk.downloadUrl, apk.size)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** True if [candidate] is a later version than [current] ("1.1.10" > "1.1.9"). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    private fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-')
        .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** Downloads the release APK into the app cache, reporting progress 0..100. */
    suspend fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "tv-diagnostics-${release.version}.apk")
            try {
                val req = Request.Builder().url(release.apkUrl).header("User-Agent", "TV-Diagnostics").build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@withContext null
                    val body = res.body ?: return@withContext null
                    val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
                    var done = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        withContext(Dispatchers.Main) { onProgress(pct) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (release.apkSize > 0 && file.length() != release.apkSize) {
                    file.delete()
                    return@withContext null
                }
                file
            } catch (e: Exception) {
                file.delete()
                null
            }
        }

    /** True if Android will let this app open the package installer (8.0+ needs a per-app permission). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where "install unknown apps" is allowed for this app. */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Hands the downloaded APK to the system installer. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
