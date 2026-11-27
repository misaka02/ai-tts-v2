package com.aitts.v2.engine.provider

import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.VoiceModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class DiscoveryCatalog(
    val models: List<String> = emptyList(),
    val voices: List<VoiceModel> = emptyList(),
    val stylesByVoice: Map<String, List<String>> = emptyMap(),
    val resolvedEndpoint: String = "",
    val fromRemote: Boolean = false,
    val fetchedAtMs: Long = System.currentTimeMillis()
)

/**
 * 24-hour disk cache for real-time upstream voice/model discovery results.
 */
class DiscoveryDiskCache(
    private val cacheDir: File,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        if (!cacheDir.exists()) cacheDir.mkdirs()
    }

    fun get(
        type: ProviderType,
        baseUrl: String,
        modelName: String,
        credentialScope: String = ""
    ): DiscoveryCatalog? {
        val file = keyFile(type, baseUrl, modelName, credentialScope)
        if (!file.exists()) return null
        val parsed = runCatching { json.decodeFromString<DiscoveryCatalog>(file.readText()) }.getOrNull()
            ?: return null
        if (System.currentTimeMillis() - parsed.fetchedAtMs > ttlMs) return null
        return parsed
    }

    fun put(
        type: ProviderType,
        baseUrl: String,
        modelName: String,
        catalog: DiscoveryCatalog,
        credentialScope: String = ""
    ) {
        if (!catalog.fromRemote) return
        runCatching {
            keyFile(type, baseUrl, modelName, credentialScope).writeText(json.encodeToString(catalog))
        }
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun keyFile(
        type: ProviderType,
        baseUrl: String,
        modelName: String,
        credentialScope: String
    ): File {
        val raw = "${type.name}|${baseUrl.trim()}|${modelName.trim()}|${credentialScope.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(cacheDir, "disc_$hex.json")
    }

    companion object {
        const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
