package de.kontoklar.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val RECEIPT_IV_BYTES = 12
private const val RECEIPT_GCM_TAG_BITS = 128
private const val MAX_STORED_RECEIPT_BYTES = 60L * 1024 * 1024

internal object ReceiptFileCodec {
    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val iv = ByteArray(RECEIPT_IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(RECEIPT_GCM_TAG_BITS, iv))
        val buffered = BufferedOutputStream(output)
        buffered.write(iv)
        CipherOutputStream(buffered, cipher).use { encrypted -> copyLimited(input, encrypted) }
    }

    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey) {
        val buffered = BufferedInputStream(input)
        val iv = ByteArray(RECEIPT_IV_BYTES)
        var offset = 0
        while (offset < iv.size) {
            val read = buffered.read(iv, offset, iv.size - offset)
            require(read > 0) { "Der verschlüsselte Beleg ist unvollständig." }
            offset += read
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(RECEIPT_GCM_TAG_BITS, iv))
        CipherInputStream(buffered, cipher).use { decrypted -> copyLimited(decrypted, output) }
    }

    private fun copyLimited(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_STORED_RECEIPT_BYTES) { "Ein Beleg darf höchstens 60 MB groß sein." }
            output.write(buffer, 0, read)
        }
    }
}

internal fun isEncryptedReceipt(context: Context, uri: Uri): Boolean =
    uri.authority == "${context.packageName}.files" && uri.pathSegments.firstOrNull() == "receipt_images" &&
        uri.lastPathSegment?.endsWith(".enc") == true && receiptFile(context, uri).isFile

internal fun encryptReceipt(context: Context, source: Uri): Uri {
    if (isEncryptedReceipt(context, source)) return source
    val directory = File(context.filesDir, "receipts").apply {
        check(isDirectory || mkdirs()) { "Belegspeicher ist nicht verfügbar." }
    }
    val extension = receiptExtension(context, source)
    val input = openReceiptInputStream(context, source)
        ?: error("Der ausgewählte Beleg kann nicht gelesen werden.")
    val saved = encryptReceiptInput(context, input, extension)
    deleteLegacyInternalReceipt(context, source)
    runCatching { context.contentResolver.releasePersistableUriPermission(source, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    return saved
}

internal fun encryptReceiptFile(context: Context, source: File, extension: String): Uri =
    source.inputStream().use { encryptReceiptInput(context, it, extension) }

private fun encryptReceiptInput(context: Context, input: InputStream, extension: String): Uri {
    val directory = File(context.filesDir, "receipts").apply {
        check(isDirectory || mkdirs()) { "Belegspeicher ist nicht verfügbar." }
    }
    val safeExtension = extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "pdf", "xml") } ?: "bin"
    val destination = File(directory, "receipt-${java.util.UUID.randomUUID()}.$safeExtension.enc")
    try {
        input.use { sourceStream -> destination.outputStream().use { encryptedStream ->
            ReceiptFileCodec.encrypt(sourceStream, encryptedStream, LocalDataEncryptionKey.key())
        } }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", destination)
    } catch (error: Throwable) {
        destination.delete()
        throw error
    }
}

internal fun openReceiptInputStream(context: Context, uri: Uri): InputStream? {
    if (!isEncryptedReceipt(context, uri)) return context.contentResolver.openInputStream(uri)
    val input = receiptFile(context, uri).inputStream()
    return try {
        val buffered = BufferedInputStream(input)
        val iv = ByteArray(RECEIPT_IV_BYTES)
        var offset = 0
        while (offset < iv.size) {
            val read = buffered.read(iv, offset, iv.size - offset)
            require(read > 0) { "Der verschlüsselte Beleg ist unvollständig." }
            offset += read
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, LocalDataEncryptionKey.key(), GCMParameterSpec(RECEIPT_GCM_TAG_BITS, iv))
        CipherInputStream(buffered, cipher)
    } catch (error: Throwable) {
        input.close()
        throw error
    }
}

internal fun decryptedReceiptForViewing(context: Context, source: Uri): Uri {
    if (!isEncryptedReceipt(context, source)) return source
    val extension = source.lastPathSegment.orEmpty().removeSuffix(".enc").substringAfterLast('.', "bin")
    val directory = File(context.cacheDir, "receipt-previews").apply {
        check(isDirectory || mkdirs()) { "Vorschauordner ist nicht verfügbar." }
    }
    val file = File(directory, "preview-${java.util.UUID.randomUUID()}.$extension")
    try {
        openReceiptInputStream(context, source)!!.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}

internal fun migrateLegacyReceipt(context: Context, source: Uri): Uri {
    if (isEncryptedReceipt(context, source)) return source
    if (source.authority != "${context.packageName}.files" || source.pathSegments.firstOrNull() != "receipt_images") return source
    return encryptReceipt(context, source)
}

internal fun cleanupReceiptWorkingCopies(context: Context) {
    listOf("receipt-capture", "receipt-previews").forEach { name ->
        File(context.cacheDir, name).listFiles()?.forEach(File::delete)
    }
}

private fun receiptFile(context: Context, uri: Uri): File {
    val directory = File(context.filesDir, "receipts").canonicalFile
    val file = File(directory, uri.lastPathSegment.orEmpty()).canonicalFile
    require(file.parentFile == directory) { "Ungültiger Belegpfad." }
    return file
}

private fun deleteLegacyInternalReceipt(context: Context, uri: Uri) {
    if (uri.authority != "${context.packageName}.files") return
    val path = uri.pathSegments
    val base = when (path.firstOrNull()) {
        "receipt_images" -> File(context.filesDir, "receipts")
        "receipt_camera_capture" -> File(context.cacheDir, "receipt-capture")
        else -> return
    }.canonicalFile
    val file = File(base, uri.lastPathSegment.orEmpty()).canonicalFile
    if (file.parentFile == base && !file.name.endsWith(".enc")) file.delete()
}

private fun receiptExtension(context: Context, uri: Uri): String {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
    } ?: uri.lastPathSegment.orEmpty()
    return name.substringAfterLast('.', "bin").lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp", "pdf", "xml") } ?: "bin"
}
