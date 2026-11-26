package com.aitts.v2.engine.provider

import com.aitts.v2.engine.audio.PcmFrameAligner
import com.aitts.v2.engine.network.HttpClientManager
import com.aitts.v2.model.PresetVoices
import com.aitts.v2.model.ProviderCapabilities
import com.aitts.v2.model.ProviderCredentials
import com.aitts.v2.model.ProviderType
import com.aitts.v2.model.TtsProviderConfig
import com.aitts.v2.model.VoiceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Unified provider for OpenAI `/v1/audio/speech` and compatible services:
 * OpenAI, SiliconFlow (CosyVoice2 / ChatTTS), StepFun (Step-Audio), and third-party proxies.
 */
class OpenAiCompatibleProvider(
    private val httpManager: HttpClientManager
) : TtsProvider {

    override val type = ProviderType.OPENAI_COMPATIBLE
    override val capabilities = ProviderCapabilities(
        supportsNativePcmStreaming = true,
        supportsNativeSpeedInBatch = true,
        supportsNativeSpeedInStream = true,
        supportsDirectorPrompt = false,
        supportsVoiceDiscovery = true,
        supportsModelDiscovery = true
    )

    override suspend fun fetchVoices(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<VoiceModel> = PresetVoices.openAiVoices

    override suspend fun fetchModels(
        config: TtsProviderConfig,
        credentials: ProviderCredentials
    ): List<String> = withContext(Dispatchers.IO) {
        val fallback = listOf("tts-1", "tts-1-hd", "FunAudioLLM/CosyVoice2-0.5B", "stepaudio-2.5-tts")
        if (credentials.apiKey.isBlank()) return@withContext fallback

        val modelsUrl = resolveModelsUrl(config.baseUrl)
        val req = Request.Builder()
            .url(modelsUrl)
            .addHeader("Authorization", "Bearer ${credentials.apiKey.trim()}")
            .build()
        runCatching {
            HttpCallSupport.executeTracked(httpManager, "", req) { resp ->
                if (!resp.isSuccessful) return@executeTracked fallback
                val body = resp.body?.string().orEmpty()
                val arr = HttpCallSupport.json.decodeFromString<JsonObject>(body)["data"]?.jsonArray
                    ?: return@executeTracked fallback
                arr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                    .filter {
                        it.contains("tts", ignoreCase = true) ||
                            it.contains("audio", ignoreCase = true) ||
                            it.contains("cosyvoice", ignoreCase = true)
                    }
                    .ifEmpty { fallback }
            }
        }.getOrDefault(fallback)
    }

    override suspend fun synthesize(request: ProviderSynthesisRequest): Result<SynthesizedAudio> =
        withContext(Dispatchers.IO) {
            val apiKey = request.credentials.apiKey.trim()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写 API Key (${request.config.name})"))
            }
            val cfg = request.config
            val format = if (cfg.audioFormat.equals("wav", ignoreCase = true)) "wav" else "mp3"
            val httpReq = buildSpeechRequest(cfg, apiKey, request.text, format)

            runCatching {
                HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}: ${String(bytes, Charsets.UTF_8).take(300)}")
                    }
                    if (bytes.isEmpty()) throw IOException("响应音频为空")
                    SynthesizedAudio(bytes = bytes, sampleRate = cfg.sampleRate, isRawPcm = false)
                }
            }
        }

    override suspend fun synthesizeStream(
        request: ProviderSynthesisRequest,
        onChunk: suspend (PcmStreamChunk) -> Unit
    ): Result<SynthesizedAudio> = withContext(Dispatchers.IO) {
        val apiKey = request.credentials.apiKey.trim()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IOException("请先填写 API Key (${request.config.name})"))
        }
        val cfg = request.config
        val httpReq = buildSpeechRequest(cfg, apiKey, request.text, "pcm")
        val aligner = PcmFrameAligner()
        val collector = ByteArrayOutputStream()

        runCatching {
            HttpCallSupport.executeTracked(httpManager, request.sessionId, httpReq) { resp ->
                val body = resp.body ?: throw IOException("流式响应体为空")
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: ${body.string().take(300)}")
                }
                val buf = ByteArray(4096)
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buf)
                        if (read == -1) break
                        if (read > 0) {
                            val aligned = aligner.push(buf.copyOf(read))
                            if (aligned.isNotEmpty()) {
                                collector.write(aligned)
                                onChunk(PcmStreamChunk(aligned, cfg.sampleRate))
                            }
                        }
                    }
                }
            }
            val full = collector.toByteArray()
            if (full.isEmpty()) throw IOException("流式未收到有效 PCM 音频")
            SynthesizedAudio(bytes = full, sampleRate = cfg.sampleRate, isRawPcm = true)
        }
    }

    private fun buildSpeechRequest(
        cfg: TtsProviderConfig,
        apiKey: String,
        text: String,
        format: String
    ): Request {
        val url = resolveSpeechUrl(cfg.baseUrl)
        val payload = buildJsonObject {
            put("model", cfg.modelName.ifBlank { "tts-1" })
            put("input", text)
            put("voice", cfg.voiceId.ifBlank { "nova" })
            put("response_format", format)
            put("speed", cfg.speed)
        }.toString()

        return Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .build()
    }

    private fun resolveSpeechUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        return when {
            url.isBlank() -> "https://api.openai.com/v1/audio/speech"
            url.endsWith("/audio/speech") || url.contains("/speech") -> url
            url.endsWith("/v1") -> "$url/audio/speech"
            else -> "$url/v1/audio/speech"
        }
    }

    private fun resolveModelsUrl(raw: String): String {
        val url = raw.trim().trimEnd('/')
        return when {
            url.isBlank() -> "https://api.openai.com/v1/models"
            url.contains("/audio/speech") -> url.replace("/audio/speech", "/models")
            url.endsWith("/models") -> url
            url.endsWith("/v1") -> "$url/models"
            else -> "$url/v1/models"
        }
    }
}
