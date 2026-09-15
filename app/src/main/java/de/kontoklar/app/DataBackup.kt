package de.kontoklar.app

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_BACKUP_JSON_BYTES = 8L * 1024 * 1024
private const val MAX_RECEIPT_BYTES = 60L * 1024 * 1024
private const val MAX_BACKUP_BYTES = 300L * 1024 * 1024
private val backupAttachmentPattern = Regex("attachments/[a-fA-F0-9-]{1,64}\\.(jpg|jpeg|png|webp|pdf|bin)")

fun exportBackup(context: Context, destination: Uri, store: LocalData) {
    val snapshot = store.exportSnapshot()
    val expenseJson = snapshot.getJSONArray("expenses")
    val attachments = mutableListOf<Pair<String, Uri>>()
    for (index in 0 until expenseJson.length()) {
        val expense = expenseJson.getJSONObject(index)
        val uriString = expense.optString("receiptUri").takeIf(String::isNotBlank) ?: continue
        val id = expense.getString("id")
        require(id.matches(Regex("[a-fA-F0-9-]{1,64}"))) { "Eine Ausgabe hat eine ungültige Kennung; die Sicherung wurde abgebrochen." }
        val sourceUri = Uri.parse(uriString)
        val entryName = "attachments/$id.${receiptExtension(context, sourceUri)}"
        expense.put("backupReceipt", entryName)
        attachments += entryName to sourceUri
    }
    val output = context.contentResolver.openOutputStream(destination, "w")
        ?: error("Die Sicherungsdatei kann nicht geschrieben werden.")
    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
        var totalBytes = 0L
        zip.putNextEntry(ZipEntry("data.json"))
        val jsonBytes = snapshot.toString().toByteArray(Charsets.UTF_8)
        require(jsonBytes.size <= MAX_BACKUP_JSON_BYTES) { "Die Sicherungsdaten sind unerwartet groß." }
        totalBytes += jsonBytes.size
        zip.write(jsonBytes)
        zip.closeEntry()

        for ((entryName, sourceUri) in attachments) {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: error("Ein angehängter Beleg ist nicht lesbar. Die Sicherung wurde nicht abgeschlossen.")
            zip.putNextEntry(ZipEntry(entryName))
            input.use { stream ->
                val buffer = ByteArray(32 * 1024)
                var receiptBytes = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    receiptBytes += read
                    require(receiptBytes <= MAX_RECEIPT_BYTES) { "Ein Beleg ist größer als 60 MB; die Sicherung wurde abgebrochen." }
                    totalBytes += read
                    require(totalBytes <= MAX_BACKUP_BYTES) { "Die Sicherung ist größer als 300 MB." }
                    zip.write(buffer, 0, read)
                }
                require(receiptBytes > 0) { "Ein angehängter Beleg ist leer; die Sicherung wurde abgebrochen." }
            }
            zip.closeEntry()
        }
    }
}

fun restoreBackup(context: Context, source: Uri, store: LocalData) {
    val restoreDirectory = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Temporärer Sicherungsspeicher ist nicht verfügbar." }
    }
    val restoredReceiptFiles = mutableListOf<File>()
    var committed = false
    try {
        var snapshot: JSONObject? = null
        val attachments = linkedMapOf<String, File>()
        var totalBytes = 0L
        val input = context.contentResolver.openInputStream(source)
            ?: error("Die Sicherungsdatei kann nicht gelesen werden.")
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.isDirectory -> Unit
                    entry.name == "data.json" -> {
                        require(snapshot == null) { "Die Sicherung enthält data.json mehrfach." }
                        val bytes = readLimited(zip, MAX_BACKUP_JSON_BYTES, "Sicherungsdaten")
                        totalBytes += bytes.size
                        snapshot = JSONObject(bytes.toString(Charsets.UTF_8))
                    }
                    backupAttachmentPattern.matches(entry.name) -> {
                        require(entry.name !in attachments) { "Die Sicherung enthält einen Beleg doppelt." }
                        val safeFile = File(restoreDirectory, "attachment-${attachments.size}.tmp")
                        val copied = copyLimited(zip, safeFile, MAX_RECEIPT_BYTES)
                        totalBytes += copied
                        require(totalBytes <= MAX_BACKUP_BYTES) { "Die Sicherung überschreitet die zulässige Größe." }
                        attachments[entry.name] = safeFile
                    }
                    else -> error("Die Sicherung enthält einen unbekannten oder ungültigen Dateipfad.")
                }
                require(totalBytes <= MAX_BACKUP_BYTES) { "Die Sicherung überschreitet die zulässige Größe." }
                zip.closeEntry()
            }
        }

        val data = requireNotNull(snapshot) { "Die Sicherung enthält keine Daten." }
        val expenseJson = data.getJSONArray("expenses")
        val referencedAttachments = linkedSetOf<String>()
        for (index in 0 until expenseJson.length()) {
            val expense = expenseJson.getJSONObject(index)
            val backupReceipt = expense.optString("backupReceipt").takeIf(String::isNotBlank)
            if (backupReceipt != null) {
                require(backupAttachmentPattern.matches(backupReceipt)) { "Die Sicherung enthält einen ungültigen Belegpfad." }
                referencedAttachments += backupReceipt
                val stagedFile = attachments[backupReceipt] ?: error("Ein Beleg fehlt in der Sicherung.")
                val extension = backupReceipt.substringAfterLast('.')
                val receiptDirectory = File(context.filesDir, "receipts").apply {
                    check(isDirectory || mkdirs()) { "Belegspeicher ist nicht verfügbar." }
                }
                val restoredFile = File(receiptDirectory, "backup-${UUID.randomUUID()}.$extension")
                stagedFile.copyTo(restoredFile, overwrite = false)
                restoredReceiptFiles += restoredFile
                expense.put("receiptUri", FileProvider.getUriForFile(context, "${context.packageName}.files", restoredFile).toString())
            } else if (expense.optString("receiptUri").isNotBlank()) {
                error("Ein Beleg wurde nicht mitgesichert. Die Sicherung wurde nicht wiederhergestellt.")
            }
            expense.remove("backupReceipt")
        }
        require(attachments.keys == referencedAttachments) { "Die Sicherung enthält nicht zugeordnete Belege." }
        store.restoreSnapshot(data)
        committed = true
    } finally {
        if (!committed) restoredReceiptFiles.forEach(File::delete)
        restoreDirectory.deleteRecursively()
    }
}

private fun receiptExtension(context: Context, uri: Uri): String {
    val mime = context.contentResolver.getType(uri)
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        ?: uri.lastPathSegment?.substringAfterLast('.', "")
    return extension?.lowercase()?.takeIf { it in setOf("jpg", "jpeg", "png", "webp", "pdf") } ?: "bin"
}

private fun readLimited(input: ZipInputStream, limit: Long, label: String): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= limit) { "$label ist unerwartet groß." }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun copyLimited(input: ZipInputStream, destination: File, limit: Long): Long {
    var total = 0L
    destination.outputStream().buffered().use { output ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Ein Beleg in der Sicherung ist zu groß." }
            output.write(buffer, 0, read)
        }
    }
    require(total > 0) { "Ein Beleg in der Sicherung ist leer." }
    return total
}
