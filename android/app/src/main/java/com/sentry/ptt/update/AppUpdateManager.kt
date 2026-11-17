package com.sentry.ptt.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateManager(private val context: Context) {

    private val releasesApiUrl = "https://api.github.com/repos/rudhrahas-dev/sentry-walkie-talkie/releases/latest"

    val currentVersionName: String
        get() {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                pInfo.versionName ?: "1.0.0"
            } catch (_: Exception) {
                "1.0.0"
            }
        }

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val tagName: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val error: String? = null
    )

    var cachedUpdateInfo: UpdateInfo? = null
        private set

    fun checkForUpdate(callback: (UpdateInfo) -> Unit) {
        Thread {
            try {
                val url = URL(releasesApiUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "SentryPTT-Android")
                }

                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                    val body = json.optString("body", "Bug fixes and performance improvements")

                    var apkDownloadUrl = ""
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkDownloadUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    if (apkDownloadUrl.isEmpty()) {
                        apkDownloadUrl = "https://github.com/rudhrahas-dev/sentry-walkie-talkie/releases/latest/download/sentry-ptt.apk"
                    }

                    val isNewer = isVersionNewer(tagName, currentVersionName)
                    val info = UpdateInfo(isNewer, "v$tagName", body, apkDownloadUrl)
                    cachedUpdateInfo = info
                    callback(info)
                } else {
                    callback(UpdateInfo(false, "", "", "", "Server returned HTTP ${conn.responseCode}"))
                }
            } catch (t: Throwable) {
                Log.w("AppUpdateManager", "Check update failed: ${t.message}")
                callback(UpdateInfo(false, "", "", "", t.message ?: "Connection failed"))
            }
        }.start()
    }

    private fun isVersionNewer(remote: String, local: String): Boolean {
        try {
            val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val localParts = local.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
        } catch (_: Exception) {}
        return false
    }

    fun downloadAndInstall(
        activity: Activity,
        apkUrl: String? = null,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) {
        val targetUrl = if (!apkUrl.isNullOrEmpty()) apkUrl else cachedUpdateInfo?.downloadUrl ?: "https://github.com/rudhrahas-dev/sentry-walkie-talkie/releases/latest/download/sentry-ptt.apk"
        Thread {
            try {
                val url = URL(targetUrl)
                var currentConn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "SentryPTT-Android")
                }

                // Handle HTTP redirects (GitHub Releases redirect to AWS S3)
                var responseCode = currentConn.responseCode
                var redirectCount = 0
                while ((responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                        responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                        responseCode == HttpURLConnection.HTTP_SEE_OTHER) && redirectCount < 5) {
                    val newUrl = currentConn.getHeaderField("Location") ?: break
                    currentConn.disconnect()
                    currentConn = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("User-Agent", "SentryPTT-Android")
                    }
                    responseCode = currentConn.responseCode
                    redirectCount++
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    activity.runOnUiThread {
                        onError("Download server returned code $responseCode")
                    }
                    return@Thread
                }

                val totalBytes = currentConn.contentLength
                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val apkFile = File(cacheDir, "sentry-ptt-update.apk")
                if (apkFile.exists()) apkFile.delete()

                currentConn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var downloaded = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((downloaded * 100) / totalBytes).toInt()
                                activity.runOnUiThread {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                activity.runOnUiThread {
                    onProgress(100)
                    installApk(activity, apkFile)
                }

            } catch (t: Throwable) {
                Log.e("AppUpdateManager", "Download update error: ${t.message}", t)
                activity.runOnUiThread {
                    onError(t.message ?: "Download failed")
                }
            }
        }.start()
    }

    private fun installApk(activity: Activity, apkFile: File) {
        try {
            val authority = "${activity.packageName}.provider"
            val apkUri: Uri = FileProvider.getUriForFile(activity, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            activity.startActivity(intent)
        } catch (t: Throwable) {
            Log.e("AppUpdateManager", "Install APK error: ${t.message}", t)
        }
    }
}
