package de.kontoklar.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val version: String, val apkUrl: String, val notes: String)

suspend fun fetchLatestRelease(): AppRelease = withContext(Dispatchers.IO) {
    val connection = URL("https://api.github.com/repos/Apfelkringel/kontoklar-android/releases/latest")
        .openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "KontoKlar-Android-Updater")
        if (connection.responseCode !in 200..299) error("GitHub antwortet mit HTTP ${connection.responseCode}.")
        val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val apk = release.getJSONArray("assets").let { assets ->
            (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        } ?: error("Das neueste Release enthält noch keine APK.")
        AppRelease(
            version = release.getString("tag_name").removePrefix("v"),
            apkUrl = apk.getString("browser_download_url"),
            notes = release.optString("body").take(700)
        )
    } finally {
        connection.disconnect()
    }
}

fun isNewerVersion(remote: String, local: String): Boolean {
    val remoteParts = remote.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    val localParts = local.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(remoteParts.size, localParts.size)) {
        val comparison = (remoteParts.getOrElse(index) { 0 }).compareTo(localParts.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    return false
}
