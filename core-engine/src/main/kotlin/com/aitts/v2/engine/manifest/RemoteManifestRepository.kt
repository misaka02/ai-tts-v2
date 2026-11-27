package com.aitts.v2.engine.manifest

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.offline.OfflineModelInstaller
import com.aitts.v2.engine.provider.HttpCallSupport
import com.aitts.v2.model.GlobalSettings
import com.aitts.v2.model.OfflineCatalogData
import com.aitts.v2.model.OfflineModelSpec
import com.aitts.v2.model.ProviderDescriptorRegistry
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.UpstreamManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * Fetches and caches the remote upstream manifest (`catalog/manifest.json`)
 * so default endpoints, Edge GEC version, and offline models update without app rebuilds.
 */
class RemoteManifestRepository(
    private val cacheFile: File,
    private val httpManager: HttpClientManager
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _manifestFlow = MutableStateFlow(loadCachedOrDefault())
    val manifestFlow: StateFlow<UpstreamManifest> = _manifestFlow.asStateFlow()

    fun current(): UpstreamManifest = _manifestFlow.value

    fun defaultEndpointFor(type: ProviderType): String =
        _manifestFlow.value.defaultEndpoints[type.name]
            ?.takeIf { isTrustedEndpoint(type, it) }
            ?: ProviderDescriptorRegistry.descriptorFor(type).defaultBaseUrl

    fun edgeGecVersion(): String =
        _manifestFlow.value.edgeGecVersion.takeIf { it.isNotBlank() } ?: DEFAULT_EDGE_GEC

    fun offlineModels(): List<OfflineModelSpec> =
        _manifestFlow.value.offlineModels.ifEmpty { OfflineCatalogData.models }

    suspend fun refresh(manifestUrl: String = GlobalSettings.DEFAULT_MANIFEST_URL): Result<UpstreamManifest> =
        withContext(Dispatchers.IO) {
            val primary = manifestUrl.trim().ifBlank { GlobalSettings.DEFAULT_MANIFEST_URL }
            val candidates = buildList {
                add(primary)
                if (primary.startsWith("https://raw.githubusercontent.com/")) {
                    add("https://ghfast.top/$primary")
                }
            }

            var lastErr: Throwable? = null
            for (url in candidates) {
                val attempt = runCatching {
                    val req = Request.Builder().url(url).addHeader("Accept", "application/json").build()
                    HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                        if (!resp.isSuccessful) throw IOException("Manifest HTTP ${resp.code}")
                        val body = resp.body?.string().orEmpty()
                        json.decodeFromString<UpstreamManifest>(body)
                    }
                }
                if (attempt.isSuccess) {
                    val fetched = sanitizeManifest(attempt.getOrThrow())
                    _manifestFlow.value = fetched
                    runCatching {
                        cacheFile.parentFile?.mkdirs()
                        cacheFile.writeText(json.encodeToString(fetched))
                    }
                    return@withContext Result.success(fetched)
                }
                lastErr = attempt.exceptionOrNull()
            }
            Result.failure(lastErr ?: IOException("无法同步远程上游清单"))
        }

    private fun loadCachedOrDefault(): UpstreamManifest {
        if (cacheFile.exists()) {
            runCatching { json.decodeFromString<UpstreamManifest>(cacheFile.readText()) }
                .getOrNull()
                ?.let { return sanitizeManifest(it) }
        }
        return UpstreamManifest()
    }

    private fun sanitizeManifest(raw: UpstreamManifest): UpstreamManifest {
        val safeEndpoints = raw.defaultEndpoints.filter { (key, url) ->
            val type = runCatching { ProviderType.valueOf(key) }.getOrNull() ?: return@filter false
            isTrustedEndpoint(type, url)
        }
        val safeModels = raw.offlineModels.filter { spec ->
            OfflineModelInstaller.sanitizeModelId(spec.id) != null &&
                spec.sizeMb in 1..1024 &&
                (spec.githubUrl.startsWith("https://") || spec.mirrorUrl.startsWith("https://"))
        }
        return raw.copy(defaultEndpoints = safeEndpoints, offlineModels = safeModels)
    }

    private fun isTrustedEndpoint(type: ProviderType, rawUrl: String): Boolean {
        val clean = rawUrl.trim()
        if (!clean.startsWith("https://")) return false
        val host = runCatching { URI(clean).host?.lowercase() }.getOrNull() ?: return false
        val allowed = TRUSTED_HOSTS[type].orEmpty()
        return allowed.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    companion object {
        const val DEFAULT_EDGE_GEC = "1-143.0.3650.75"
        private val TRUSTED_HOSTS: Map<ProviderType, List<String>> = mapOf(
            ProviderType.EDGE_TTS to listOf("speech.platform.bing.com"),
            ProviderType.AZURE to listOf("tts.speech.microsoft.com"),
            ProviderType.OPENAI_COMPATIBLE to listOf("api.openai.com"),
            ProviderType.GEMINI to listOf("generativelanguage.googleapis.com"),
            ProviderType.MIMO to listOf("api.xiaomimimo.com"),
            ProviderType.MINIMAX to listOf("api.minimax.chat", "api.minimaxi.com"),
            ProviderType.DOUBAO to listOf("openspeech.bytedance.com"),
            ProviderType.FISH_AUDIO to listOf("api.fish.audio")
        )
    }
}
