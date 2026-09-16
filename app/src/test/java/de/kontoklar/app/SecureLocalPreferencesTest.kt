package de.kontoklar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SecureLocalPreferencesTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test fun encryptsAndRestoresUtf8Data() {
        val secret = "Rechnungen, IBAN DE89370400440532013000, Grüße"
        assertEquals(secret, AesGcmValueCodec.decrypt(AesGcmValueCodec.encrypt(secret, key), key))
    }

    @Test fun usesFreshIvForEachEncryption() {
        val first = AesGcmValueCodec.encrypt("same business data", key)
        val second = AesGcmValueCodec.encrypt("same business data", key)
        assertNotEquals(first, second)
        assertEquals("same business data", AesGcmValueCodec.decrypt(second, key))
    }

    @Test fun rejectsTamperedCiphertext() {
        val encrypted = AesGcmValueCodec.encrypt("sensitive", key)
        val bytes = java.util.Base64.getDecoder().decode(encrypted)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertThrows(Exception::class.java) { AesGcmValueCodec.decrypt(tampered, key) }
    }
}
