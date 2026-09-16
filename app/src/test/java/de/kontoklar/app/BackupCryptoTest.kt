package de.kontoklar.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test fun encryptedBackupRoundTripsAndAuthenticatesContents() {
        val password = "a-long-test-password".toCharArray()
        val source = ByteArray(64 * 1024) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        encryptedBackupOutput(output, password).use { it.write(source) }
        val encrypted = output.toByteArray()

        assertTrue(backupHasEncryptionHeader(encrypted.copyOfRange(0, 8)))
        decryptedBackupInput(ByteArrayInputStream(encrypted), password).use { assertArrayEquals(source, it.readBytes()) }

        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            decryptedBackupInput(ByteArrayInputStream(encrypted), password).use { it.readBytes() }
        }
    }

    @Test fun rejectsShortPassphraseAndIdentifiesLegacyZip() {
        assertThrows(IllegalArgumentException::class.java) {
            encryptedBackupOutput(ByteArrayOutputStream(), "short".toCharArray())
        }
        assertFalse(backupHasEncryptionHeader(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0, 0, 0)))
    }
}
