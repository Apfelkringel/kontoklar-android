package de.kontoklar.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SecureLocalPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("kontoklar_data_v1", Context.MODE_PRIVATE)

    init {
        migratePlaintext()
    }

    fun getString(key: String, defaultValue: String? = null): String? {
        val value = preferences.getString(key, null) ?: return defaultValue
        return if (value.startsWith(ENCRYPTED_PREFIX)) {
            runCatching { AesGcmValueCodec.decrypt(value.removePrefix(ENCRYPTED_PREFIX), LocalDataEncryptionKey.key()) }
                .getOrElse { throw IllegalStateException("Lokale Daten konnten nicht entschlüsselt werden.", it) }
        } else value
    }

    fun putString(key: String, value: String, commit: Boolean = false): Boolean {
        val encrypted = ENCRYPTED_PREFIX + AesGcmValueCodec.encrypt(value, LocalDataEncryptionKey.key())
        val editor = preferences.edit().putString(key, encrypted)
        return if (commit) editor.commit() else { editor.apply(); true }
    }

    fun putStrings(values: Map<String, String>): Boolean {
        val key = LocalDataEncryptionKey.key()
        val encrypted = values.mapValues { (_, value) -> ENCRYPTED_PREFIX + AesGcmValueCodec.encrypt(value, key) }
        val editor = preferences.edit()
        encrypted.forEach { (name, value) -> editor.putString(name, value) }
        return editor.commit()
    }

    private fun migratePlaintext() {
        val oldValues = preferences.all.mapNotNull { (key, value) ->
            (value as? String)?.takeUnless { it.startsWith(ENCRYPTED_PREFIX) }?.let { key to it }
        }.toMap()
        if (oldValues.isNotEmpty() && !putStrings(oldValues)) {
            throw IllegalStateException("Vorhandene lokale Daten konnten nicht sicher migriert werden.")
        }
    }

    private companion object {
        const val ENCRYPTED_PREFIX = "KKENC1:"
    }
}

internal object LocalDataEncryptionKey {
    private const val KEY_ALIAS = "kontoklar_local_data_aes_v1"

    @Synchronized fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}

internal object AesGcmValueCodec {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(plainText: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }

    fun decrypt(encoded: String, key: SecretKey): String {
        val payload = Base64.getDecoder().decode(encoded)
        require(payload.size > IV_LENGTH_BYTES) { "Ungültige verschlüsselte lokale Daten." }
        val iv = payload.copyOfRange(0, IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(payload, IV_LENGTH_BYTES, payload.size - IV_LENGTH_BYTES), StandardCharsets.UTF_8)
    }
}
