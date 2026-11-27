package com.aitts.v2.engine.provider

import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class FishAudioTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.FISH_AUDIO
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = false,
        supportsNativeSpeedInBatch = true,
        supportsVoiceDiscovery = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = fetchVoicesInternal(config, credentials).first

    override suspend fun discoverCatalog(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): DiscoveryCatalog {
        val (voices, fromRemote) = fetchVoicesInternal(config, credentials)
        return DiscoveryCatalog(
            models = listOf("speech-1.5", "speech-1.4"),
            voices = voices,
            resolvedEndpoint = config.baseUrl.trim().ifBlank { DEFAULT_TTS_URL },
            fromRemote = fromRemote
        )
    }

    override suspend fun fetchModels(config: TtsProviderConfig, credentials: ProviderCredentials): List<String> =
        listOf("speech-1.5", "speech-1.4")

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写 Fish Audio API Key"))
            }
            val cfg = request.config
            val url = cfg.baseUrl.trim().ifBlank { DEFAULT_TTS_URL }
            val latency = cfg.extraParams["latency"]?.takeIf { it == "normal" || it == "balanced" } ?: "balanced"
            val payload = buildJsonObject {
                put("text", request.text)
                put("reference_id", cfg.voiceId.ifBlank { "7f92f8afb8ec43bf81429cc1c9199cb1" })
                put("format", if (cfg.audioFormat.equals("wav", ignoreCase = true)) "wav" else "mp3")
                put("latency", latency)
                put("prosody", buildJsonObject { put("speed", cfg.speed.coerceIn(0.5f, 2.0f)) })
                put("streaming", false)
            }.toString()

            val httpReq = Request.Builder()
                .url(url)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .build()

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) {
                        throw IOException("Fish Audio HTTP ${resp.code}: ${String(bytes, Charsets.UTF_8).take(300)}")
                    }
                    if (bytes.isEmpty()) throw IOException("Fish Audio 返回音频为空")
                    SynthesizedAudio(bytes = bytes, sampleRate = cfg.sampleRate, isRawPcm = false)
                }
            }
        }

    private suspend fun fetchVoicesInternal(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): Pair<List<VoiceModel>, Boolean> = withContext(Dispatchers.IO) {
        val apiKey = credentials.apiKey.trim()
        if (apiKey.isBlank()) return@withContext PresetVoices.fishAudioVoices to false

        val modelsUrl = "${resolveApiRoot(config.baseUrl)}/model?page_size=30"
        val req = Request.Builder()
            .url(modelsUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        var fromRemote = false
        val fetched = runCatching {
            HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                if (!resp.isSuccessful) return@executeTracked emptyList()
                val root = HttpCallSupport.json.decodeFromString<JsonObject>(resp.body?.string().orEmpty())
                root["items"]?.jsonArray?.mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val id = obj["_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = obj["title"]?.jsonPrimitive?.content ?: id
                    VoiceModel(id = id, name = title, gender = "Neutral", locale = "zh-CN")
                }?.also { if (it.isNotEmpty()) fromRemote = true }.orEmpty()
            }
        }.getOrDefault(emptyList())
        fetched.ifEmpty { PresetVoices.fishAudioVoices } to fromRemote
    }

    private fun resolveApiRoot(rawBase: String): String {
        val clean = rawBase.trim().trimEnd('/')
        if (clean.isBlank()) return "https://api.fish.audio"
        return when {
            clean.endsWith("/v1/tts") -> clean.removeSuffix("/v1/tts")
            clean.endsWith("/tts") -> clean.removeSuffix("/tts")
            else -> clean
        }
    }

    companion object {
        private const val DEFAULT_TTS_URL = "https://api.fish.audio/v1/tts"
    }
}
