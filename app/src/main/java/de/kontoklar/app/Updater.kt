package de.kontoklar.app

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
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
    val connection = openVerifiedUpdateConnection(release.apkUrl)
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
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

private val ALLOWED_UPDATE_HOSTS = setOf(
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com"
)

internal fun isAllowedUpdateHost(host: String): Boolean = host.lowercase() in ALLOWED_UPDATE_HOSTS

/** Follows only HTTPS redirects that stay within GitHub's download hosts. */
private fun openVerifiedUpdateConnection(initialUrl: String): HttpURLConnection {
    var currentUrl = URL(initialUrl)
    repeat(4) { attempt ->
        require(currentUrl.protocol == "https" && isAllowedUpdateHost(currentUrl.host)) {
            "Die APK-Weiterleitung führt zu einem nicht erlaubten Ziel."
        }
        val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val status = connection.responseCode
        if (status in 200..299) return connection
        if (status in 300..399 && attempt < 3) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "Die APK-Weiterleitung enthält kein Ziel." }
            currentUrl = URL(currentUrl, location)
        } else {
            connection.disconnect()
            error("Download nicht verfügbar.")
        }
    }
    error("Zu viele APK-Weiterleitungen.")
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

internal fun isInstallablePackageUpdate(
    installedPackage: String?,
    installedVersionCode: Long,
    candidatePackage: String?,
    candidateVersionCode: Long
): Boolean = installedPackage != null && installedPackage == candidatePackage && candidateVersionCode > installedVersionCode

internal fun sameSigningCertificates(installed: Set<String>, candidate: Set<String>): Boolean =
    installed.isNotEmpty() && installed == candidate

/** Check the APK's Android package metadata and signing identity before handing it to the installer. */
fun verifyUpdateApk(context: Context, apk: File) {
    val packageManager = context.packageManager
    val installedInfo = packageManager.getPackageInfoCompat(context.packageName)
    val candidateInfo = packageManager.getPackageArchiveInfoCompat(apk.absolutePath)
        ?: error("Die heruntergeladene Datei ist kein gültiges Android-Installationspaket.")

    require(isInstallablePackageUpdate(
        installedPackage = installedInfo.packageName,
        installedVersionCode = installedInfo.longVersionCodeCompat(),
        candidatePackage = candidateInfo.packageName,
        candidateVersionCode = candidateInfo.longVersionCodeCompat()
    )) { "Das APK gehört nicht zu KontoKlar oder ist keine neuere Version." }

    val installedCertificates = installedInfo.signingCertificates()
    val candidateCertificates = candidateInfo.signingCertificates()
    require(sameSigningCertificates(installedCertificates, candidateCertificates)) {
        "Der Signaturschlüssel des Updates passt nicht zu dieser Installation. Es wurde nichts installiert."
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    else getPackageInfo(packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

@Suppress("DEPRECATION")
private fun PackageManager.getPackageArchiveInfoCompat(path: String): PackageInfo? =
    if (Build.VERSION.SDK_INT >= 33) getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
    else getPackageArchiveInfo(path, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

@Suppress("DEPRECATION")
private fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signingCertificates(): Set<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures
    return signatures.orEmpty().mapTo(linkedSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }
}
