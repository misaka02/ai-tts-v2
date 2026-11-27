package com.aitts.v2.engine.provider

import com.aitts.v2.engine.audio.PcmFrameAligner
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.engine.network.SseReader
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64

/**
 * Xiaomi MiMo-V2.5-TTS provider.
 * Single speed policy:
 * - Batch mode uses Director speed instruction (capabilities.supportsNativeSpeedInBatch = true).
 * - Streaming SSE emits fixed-clock PCM16, so client Sonic handles speed (supportsNativeSpeedInStream = false),
 *   and Director speed prompt is pinned to standard cadence to prevent double speed scaling.
 */
class MimoTtsProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.MIMO
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = true,
        supportsNativeSpeedInBatch = true,
        supportsNativeSpeedInStream = false,
        supportsDirectorPrompt = true,
        supportsVoiceDiscovery = false,
        supportsModelDiscovery = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = PresetVoices.mimoVoices

    override suspend fun fetchModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<String> = fetchModelsInternal(config, credentials).first

    override suspend fun discoverCatalog(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): DiscoveryCatalog {
        val (models, fromRemote) = fetchModelsInternal(config, credentials)
        return DiscoveryCatalog(
            models = models,
            voices = PresetVoices.mimoVoices,
            resolvedEndpoint = resolveEndpoint(config.baseUrl),
            fromRemote = fromRemote
        )
    }

    private suspend fun fetchModelsInternal(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): Pair<List<String>, Boolean> = withContext(Dispatchers.IO) {
        val fallback = listOf("mimo-v2.5-tts", "mimo-v2-tts")
        if (credentials.apiKey.isBlank()) return@withContext fallback to false
        val base = resolveEndpoint(config.baseUrl).substringBefore("/chat/completions").trimEnd('/')
        val req = Request.Builder()
            .url("$base/models")
            .addHeader("Authorization", "Bearer ${credentials.apiKey.trim()}")
            .addHeader("api-key", credentials.apiKey.trim())
            .build()
        var fromRemote = false
        val fetched = runCatching {
            HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                if (!resp.isSuccessful) return@executeTracked emptyList()
                val root = HttpCallSupport.json.decodeFromString<JsonObject>(resp.body?.string().orEmpty())
                root["data"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                    ?.filter { it.contains("mimo", ignoreCase = true) || it.contains("tts", ignoreCase = true) }
                    ?.also { if (it.isNotEmpty()) fromRemote = true }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
        fetched.ifEmpty { fallback } to fromRemote
    }

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写小米 MiMo API Key"))
            }
            val cfg = request.config
            val format = if (cfg.audioFormat.contains("wav", ignoreCase = true)) "wav" else "mp3"
            val httpReq = buildRequest(cfg, apiKey, request.text, stream = false, format = format)

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("MiMo HTTP ${resp.code}: ${bodyStr.take(300)}")
                    }
                    val root = HttpCallSupport.json.decodeFromString<JsonObject>(bodyStr)
                    root["error"]?.let { err ->
                        val msg = runCatching { err.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() ?: err.toString()
                        throw IOException("MiMo 错误: $msg")
                    }
                    val b64 = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject?.get("audio")?.jsonObject?.get("data")?.jsonPrimitive?.content
                        ?: root["data"]?.jsonPrimitive?.content
                        ?: root["audio"]?.jsonPrimitive?.content
                        ?: throw IOException("MiMo 响应中未包含音频数据")
                    SynthesizedAudio(
                        bytes = Base64.getMimeDecoder().decode(b64),
                        sampleRate = cfg.sampleRate,
                        isRawPcm = false
                    )
                }
            }
        }

    override suspend fun synthesizeStream(
        request: ProviderSynthesisRequest,
        onChunk: suspend (PcmStreamChunk) -> Unit
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        val apiKey = request.credentials.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IOException("请先填写小米 MiMo API Key"))
        }
        val cfg = request.config
        val httpReq = buildRequest(cfg, apiKey, request.text, stream = true, format = "pcm16")
        val aligner = PcmFrameAligner()
        val collector = ByteArrayOutputStream()

        runCatching {
            HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                val body = resp.body ?: throw IOException("MiMo 流式响应为空")
                if (!resp.isSuccessful) {
                    throw IOException("MiMo 流式 HTTP ${resp.code}: ${body.string().take(300)}")
                }
                val sawSse = SseReader.readDataEvents(body) { payloadJson ->
                    val chunkObj = runCatching {
                        HttpCallSupport.json.decodeFromString<JsonObject>(payloadJson)
                    }.getOrNull() ?: return@readDataEvents
                    val b64 = chunkObj["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("delta")?.jsonObject?.get("audio")?.jsonObject?.get("data")?.jsonPrimitive?.content
                        ?: return@readDataEvents
                    val raw = Base64.getMimeDecoder().decode(b64)
                    val aligned = aligner.push(raw)
                    if (aligned.isNotEmpty()) {
                        collector.write(aligned)
                        onChunk(PcmStreamChunk(aligned, 24000))
                    }
                }
                if (!sawSse) throw IOException("MiMo 未返回有效 SSE 事件流")
            }
            val full = collector.toByteArray()
            if (full.isEmpty()) throw IOException("MiMo 流式未收到音频分块")
            SynthesizedAudio(bytes = full, sampleRate = 24000, isRawPcm = true)
        }
    }

    private fun buildRequest(
        cfg: TtsProviderConfig,
        apiKey: String,
        text: String,
        stream: Boolean,
        format: String
    ): Request {
        val url = resolveEndpoint(cfg.baseUrl)
        val directorPrompt = buildDirectorPrompt(cfg, includeSpeedClause = !stream)
        val payload = buildJsonObject {
            put("model", cfg.modelName.ifBlank { "mimo-v2.5-tts" })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", directorPrompt)
                })
                add(buildJsonObject {
                    put("role", "assistant")
                    put("content", text)
                })
            })
            put("audio", buildJsonObject {
                put("format", format)
                put("voice", cfg.voiceId.ifBlank { "茉莉" })
                if (!stream) put("speed", cfg.speed)
            })
            put("stream", stream)
        }.toString()

        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", if (stream) "text/event-stream, application/json" else "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("api-key", apiKey)
            .build()
    }

    private fun resolveEndpoint(raw: String): String {
        val url = raw.trim().trimEnd('/')
        return when {
            url.isBlank() -> "https://api.xiaomimimo.com/v1/chat/completions"
            url.endsWith("/chat/completions") || url.contains("/tts") -> url
            else -> "$url/chat/completions"
        }
    }

    private fun buildDirectorPrompt(cfg: TtsProviderConfig, includeSpeedClause: Boolean): String {
        val clauses = mutableListOf<String>()
        if (includeSpeedClause) {
            clauses.add(speedClause(cfg.speed))
        } else {
            clauses.add("请用标准自然流畅的语速朗读")
        }
        when {
            cfg.pitch <= 0.85f -> clauses.add("声音偏低沉浑厚，富含磁性")
            cfg.pitch >= 1.15f -> clauses.add("声音偏清脆明亮，高昂悦耳")
        }
        cfg.promptInstruction.trim().takeIf { it.isNotBlank() }?.let { clauses.add(it) }
        return clauses.joinToString("，") + "。"
    }

    private fun speedClause(speed: Float): String = when {
        speed <= 0.65f -> "请用极其舒缓柔和的语速朗读，声音沉静从容"
        speed <= 0.80f -> "请用偏慢从容的语速朗读，节奏平稳"
        speed <= 0.95f -> "请用稍慢而流畅的语速朗读"
        speed <= 1.10f -> "请用标准自然流畅的语速朗读"
        speed <= 1.35f -> "请用稍快轻巧的语速朗读，节奏利落"
        else -> "请用快速流利的语速朗读，节奏紧凑"
    }
}
