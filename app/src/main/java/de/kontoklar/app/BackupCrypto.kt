package de.kontoklar.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private val BACKUP_MAGIC = "KKBKUP01".toByteArray(Charsets.US_ASCII)
private const val BACKUP_SALT_BYTES = 16
private const val BACKUP_IV_BYTES = 12
private const val BACKUP_KDF_ITERATIONS = 310_000

internal fun encryptedBackupOutput(output: OutputStream, password: CharArray): OutputStream {
    require(password.size >= 12) { "Das Sicherungspasswort muss mindestens 12 Zeichen lang sein." }
    val random = SecureRandom()
    val salt = ByteArray(BACKUP_SALT_BYTES).also(random::nextBytes)
    val iv = ByteArray(BACKUP_IV_BYTES).also(random::nextBytes)
    val header = DataOutputStream(output)
    header.write(BACKUP_MAGIC)
    header.writeByte(BACKUP_SALT_BYTES)
    header.write(salt)
    header.writeByte(BACKUP_IV_BYTES)
    header.write(iv)
    header.flush()
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, deriveBackupKey(password, salt), GCMParameterSpec(128, iv))
        updateAAD(BACKUP_MAGIC)
    }
    return CipherOutputStream(output, cipher)
}

internal fun decryptedBackupInput(input: InputStream, password: CharArray): InputStream {
    val header = DataInputStream(input)
    val magic = ByteArray(BACKUP_MAGIC.size)
    header.readFully(magic)
    require(magic.contentEquals(BACKUP_MAGIC)) { "Die Datei ist keine KontoKlar-Sicherung." }
    val saltLength = header.readUnsignedByte()
    require(saltLength == BACKUP_SALT_BYTES) { "Der Sicherungskopf ist ungültig." }
    val salt = ByteArray(saltLength).also(header::readFully)
    val ivLength = header.readUnsignedByte()
    require(ivLength == BACKUP_IV_BYTES) { "Der Sicherungskopf ist ungültig." }
    val iv = ByteArray(ivLength).also(header::readFully)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, deriveBackupKey(password, salt), GCMParameterSpec(128, iv))
        updateAAD(BACKUP_MAGIC)
    }
    return CipherInputStream(input, cipher)
}

internal fun backupHasEncryptionHeader(prefix: ByteArray): Boolean =
    prefix.size >= BACKUP_MAGIC.size && prefix.copyOfRange(0, BACKUP_MAGIC.size).contentEquals(BACKUP_MAGIC)

private fun deriveBackupKey(password: CharArray, salt: ByteArray): SecretKeySpec {
    val spec = PBEKeySpec(password, salt, BACKUP_KDF_ITERATIONS, 256)
    return try {
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    } finally {
        spec.clearPassword()
    }
}
