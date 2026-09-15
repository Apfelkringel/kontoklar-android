package de.kontoklar.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

data class AppRelease(val version: String, val apkUrl: String, val sha256: String, val notes: String)

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
        val apkUrl = apk.getString("browser_download_url")
        val sha256 = apk.optString("digest").removePrefix("sha256:")
        require(URL(apkUrl).let { it.protocol == "https" && it.host == "github.com" }) { "Ungültige APK-Adresse." }
        require(isValidSha256(sha256)) { "Für diese APK fehlt ein gültiger SHA-256-Prüfwert." }
        AppRelease(
            version = release.getString("tag_name").removePrefix("v"),
            apkUrl = apkUrl,
            sha256 = sha256.lowercase(),
            notes = release.optString("body").take(700)
        )
    } finally {
        connection.disconnect()
    }
}

fun isValidSha256(value: String): Boolean = value.matches(Regex("[0-9a-fA-F]{64}"))

suspend fun downloadVerifiedApk(cacheDir: File, release: AppRelease): File = withContext(Dispatchers.IO) {
    require(isValidSha256(release.sha256)) { "Ungültiger SHA-256-Prüfwert." }
    val updatesDir = File(cacheDir, "updates").apply { check(isDirectory || mkdirs()) { "Update-Speicher ist nicht verfügbar." } }
    val partial = File(updatesDir, "kontoklar-${release.version}.apk.part")
    val verified = File(updatesDir, "kontoklar-${release.version}.apk")
    val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "KontoKlar-Android-Updater")
        require(connection.responseCode in 200..299) { "Download nicht verfügbar." }
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        connection.inputStream.use { input ->
            partial.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= 150L * 1024 * 1024) { "Die APK ist unerwartet groß." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        val expectedDigest = release.sha256.lowercase().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(totalBytes > 0 && MessageDigest.isEqual(digest.digest(), expectedDigest)) {
            "Die APK-Prüfung ist fehlgeschlagen. Es wurde nichts installiert."
        }
        check(!verified.exists() || verified.delete()) { "Vorheriger Download konnte nicht ersetzt werden." }
        check(partial.renameTo(verified)) { "APK konnte nicht bereitgestellt werden." }
        verified
    } catch (failure: Exception) {
        partial.delete()
        throw failure
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
