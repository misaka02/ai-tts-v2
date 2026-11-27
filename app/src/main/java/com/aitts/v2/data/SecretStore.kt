package com.aitts.v2.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.aitts.v2.model.ProviderCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore AES-256-GCM encrypted store for API keys,
 * keeping secrets isolated from exportable DataStore settings.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("aitts_v2_secrets", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getCredentials(providerId: String): ProviderCredentials {
        val raw = prefs.getString(providerId, null) ?: return ProviderCredentials()
        return runCatching {
            val plaintext = decrypt(raw)
            json.decodeFromString<ProviderCredentials>(plaintext)
        }.getOrDefault(ProviderCredentials())
    }

    fun saveCredentials(providerId: String, credentials: ProviderCredentials): Boolean {
        if (credentials.apiKey.isBlank() && credentials.secondaryApiKey.isBlank()) {
            prefs.edit().remove(providerId).apply()
            return true
        }
        return runCatching {
            val plaintext = json.encodeToString(credentials)
            val encrypted = encrypt(plaintext)
            prefs.edit().putString(providerId, encrypted).apply()
        }.isSuccess
    }

    fun removeCredentials(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }

    fun getSecretString(key: String): String? {
        val raw = prefs.getString(key, null) ?: return null
        return runCatching { decrypt(raw) }.getOrNull()
    }

    fun saveSecretString(key: String, value: String): Boolean {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return true
        }
        return runCatching {
            val encrypted = encrypt(value)
            prefs.edit().putString(key, encrypted).apply()
        }.isSuccess
    }

    fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun exportAllCredentials(providerIds: Iterable<String>): Map<String, ProviderCredentials> {
        return providerIds.mapNotNull { id ->
            val creds = getCredentials(id)
            if (creds.apiKey.isBlank() && creds.secondaryApiKey.isBlank()) null else id to creds
        }.toMap()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$ivB64:$ctB64"
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        if (parts.size != 2) error("Invalid ciphertext format")
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ct = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aitts_v2_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
