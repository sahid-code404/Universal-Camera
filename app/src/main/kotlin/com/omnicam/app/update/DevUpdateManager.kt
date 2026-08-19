package com.omnicam.app.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.omnicam.app.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface DevUpdateState {
    data object Idle : DevUpdateState
    data object Checking : DevUpdateState
    data object UpToDate : DevUpdateState
    data class Available(val update: DevUpdateInfo) : DevUpdateState
    data class Downloading(val update: DevUpdateInfo) : DevUpdateState
    data class ReadyToInstall(val update: DevUpdateInfo, val apk: File) : DevUpdateState
    data class Error(val message: String) : DevUpdateState
}

data class DevUpdateInfo(val versionCode: Int, val assetName: String, val downloadUrl: String)

class DevUpdateManager(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<DevUpdateState>(DevUpdateState.Idle)
    val state: StateFlow<DevUpdateState> = _state.asStateFlow()

    fun check() {
        if (_state.value is DevUpdateState.Checking || _state.value is DevUpdateState.Downloading) return
        scope.launch {
            _state.value = DevUpdateState.Checking
            _state.value = runCatching { queryUpdate() }.fold(
                onSuccess = { update ->
                    if (update == null || update.versionCode <= BuildConfig.VERSION_CODE) DevUpdateState.UpToDate
                    else DevUpdateState.Available(update)
                },
                onFailure = { DevUpdateState.Error(it.message ?: it::class.java.simpleName) },
            )
        }
    }

    fun download(update: DevUpdateInfo) {
        if (_state.value is DevUpdateState.Downloading) return
        scope.launch {
            _state.value = DevUpdateState.Downloading(update)
            _state.value = runCatching { downloadApk(update) }.fold(
                onSuccess = { DevUpdateState.ReadyToInstall(update, it) },
                onFailure = { DevUpdateState.Error(it.message ?: it::class.java.simpleName) },
            )
        }
    }

    fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            _state.value = DevUpdateState.Error("Allow Install unknown apps for OmniCam, then tap Install update again.")
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private suspend fun queryUpdate(): DevUpdateInfo? = withContext(Dispatchers.IO) {
        val connection = (URL("https://api.github.com/repos/${BuildConfig.DEV_UPDATE_REPO}/releases/tags/${BuildConfig.DEV_UPDATE_TAG}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "OmniCam/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            check(connection.responseCode in 200..299) { "Update server returned HTTP ${connection.responseCode}" }
            val assets = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONArray("assets")
            val regex = Regex("OmniCam-dev-v(\\d+)\\.apk")
            buildList {
                for (index in 0 until assets.length()) {
                    val asset = assets.getJSONObject(index)
                    val name = asset.optString("name")
                    val match = regex.matchEntire(name) ?: continue
                    val version = match.groupValues[1].toIntOrNull() ?: continue
                    val url = asset.optString("browser_download_url")
                    if (url.isNotBlank()) add(DevUpdateInfo(version, name, url))
                }
            }.maxByOrNull { it.versionCode }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadApk(update: DevUpdateInfo): File = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
        val target = File(directory, update.assetName)
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "OmniCam/${BuildConfig.VERSION_NAME}")
        }
        try {
            check(connection.responseCode in 200..299) { "APK download returned HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> target.outputStream().buffered().use { output -> input.copyTo(output) } }
        } finally {
            connection.disconnect()
        }
        check(target.length() > 100_000) { "Downloaded APK is unexpectedly small" }
        target
    }
}
